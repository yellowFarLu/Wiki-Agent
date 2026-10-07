package com.wikiagent.infrastructure.business;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.config.BusinessIntentProperties;
import com.wikiagent.domain.business.BusinessIntent;
import com.wikiagent.domain.business.Classification;
import com.wikiagent.domain.business.IntentClassifier;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.infrastructure.llm.DashScopeMultiModelFactory;
import com.wikiagent.infrastructure.llm.LlmCircuitBreaker;
import com.wikiagent.service.agent.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 业务意图分类器（qwen-flash，结构化 JSON 输出）+ 弹性组件（熔断 / 退避+抖动 / 纠错重提示）。
 * <p>
 * 四类失败统一确定性降级（§6）：
 * <ul>
 *   <li>无 API Key（NoOpChatModel）→ {@code no_key}</li>
 *   <li>熔断 OPEN → {@code circuit_open}</li>
 *   <li>语义错误（JSON 畸形）→ 带纠错重提示 1 次，仍失败 → {@code parse_failed}</li>
 *   <li>瞬态错误（超时/5xx）→ 指数退避+抖动重试，仍失败 → {@code llm_error}</li>
 * </ul>
 * 降级结果由网关映射为知识问答（用户无感）。
 */
@Service
public class BusinessIntentClassifier implements IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(BusinessIntentClassifier.class);

    private static final String PROVIDER = "business-intent";

    static final String INTENT_SYSTEM = """
            你是企业知识库的业务意图分类器。分析用户输入，只输出一个 JSON，不要输出任何其他文字：
            {"intent":"<intent_code>","confidence":0.9,"slots":{"orderNo":"SF1234567890"},"reason":"<简短理由>"}
            intent_code 必须是以下之一：
            - order_query：查询订单信息
            - trajectory_query：查询物流轨迹
            - customs_generate：清关信息生成（需要上传小包号/大包号映射表）
            - knowledge_qa：其它（知识问答等）
            slots 仅在能从原文直接提取时填写（order_query/trajectory_query 填 orderNo；customs_generate 通常不填）。
            判断原则：与订单/轨迹/清关最相关才归为对应意图；无法明确归类时归 knowledge_qa。confidence 取 0~1 小数。
            """;

    private final ChatModel model;
    private final BusinessIntentProperties props;
    private final LlmCircuitBreaker breaker;
    private final ObjectProvider<ModelCallRecorder> recorder;
    private final String intentModelName;

    public BusinessIntentClassifier(@Qualifier("intentChatModel") ChatModel model,
                                    BusinessIntentProperties props,
                                    ObjectProvider<ModelCallRecorder> recorder,
                                    @Value("${wikiagent.routing.intent-model:qwen-flash}") String intentModel) {
        this.model = model;
        this.props = props;
        this.recorder = recorder;
        this.intentModelName = intentModel;
        this.breaker = new LlmCircuitBreaker(props.getCircuitFailureThreshold(),
                props.getCircuitOpenSeconds(), Clock.systemUTC());
    }

    @Override
    public Classification classify(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return Classification.knowledgeQa("空输入");
        }
        if (model instanceof DashScopeMultiModelFactory.NoOpChatModel) {
            return Classification.fallback("no_key");
        }
        if (breaker.isOpen(PROVIDER)) {
            return Classification.fallback("circuit_open");
        }

        String prompt = rawInput;
        String lastFallback = "llm_error";
        for (int reprompt = 0; reprompt <= props.getRePromptMax(); reprompt++) {
            boolean semanticError = false;
            for (int attempt = 0; attempt <= props.getRetryMaxAttempts(); attempt++) {
                if (!breaker.allowRequest(PROVIDER)) {
                    return Classification.fallback("circuit_open");
                }
                long start = System.currentTimeMillis();
                try {
                    String out = callModel(prompt);
                    breaker.recordSuccess(PROVIDER);
                    Classification c = parse(out);
                    if (c != null) {
                        record(start, true, null, null);
                        return c;
                    }
                    semanticError = true;
                    lastFallback = "parse_failed";
                    record(start, false, null, null);
                    break; // 语义错误 → 纠错重提示
                } catch (Exception e) {
                    breaker.recordFailure(PROVIDER);
                    lastFallback = "llm_error";
                    record(start, false, null, null);
                    log.warn("业务意图分类调用失败（第 {} 次）: {}", attempt + 1, e.getMessage());
                    if (attempt < props.getRetryMaxAttempts()) {
                        sleepBackoff(attempt);
                    }
                }
            }
            if (!semanticError) {
                // 瞬态重试耗尽
                return Classification.fallback(lastFallback);
            }
            prompt = correctivePrompt(rawInput);
        }
        return Classification.fallback(lastFallback);
    }

    /** 解析 LLM 输出为分类结果；JSON 畸形返回 null（语义错误）。 */
    private Classification parse(String out) {
        Map<String, Object> json = JsonExtractor.parseObject(out);
        if (json.isEmpty()) {
            return null;
        }
        String intentCode = str(json.get("intent"));
        if (intentCode == null || intentCode.isBlank()) {
            return null;
        }
        double confidence = confidenceOf(json.get("confidence"));
        Map<String, String> slots = slotsOf(json.get("slots"));
        String reason = str(json.get("reason"));

        var businessIntent = BusinessIntent.fromCode(intentCode);
        if (businessIntent.isEmpty()) {
            return Classification.knowledgeQa(reason);
        }
        if (confidence < props.getConfidenceThreshold()) {
            return Classification.fallback("low_confidence");
        }
        return new Classification(businessIntent.get(), confidence, slots, reason, false, null);
    }

    private static Map<String, String> slotsOf(Object slotsObj) {
        Map<String, String> out = new HashMap<>();
        if (slotsObj instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()).trim());
                }
            }
        }
        return out;
    }

    private static double confidenceOf(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o != null) {
            try {
                return Double.parseDouble(String.valueOf(o).trim());
            } catch (NumberFormatException ignore) {
                // fallthrough
            }
        }
        return 0d;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).strip();
    }

    private String callModel(String userInput) {
        var resp = model.call(new Prompt(List.of(
                new SystemMessage(INTENT_SYSTEM), new UserMessage(userInput))));
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            throw new IllegalStateException("意图分类模型返回空");
        }
        return resp.getResult().getOutput().getText();
    }

    private String correctivePrompt(String rawInput) {
        return rawInput + "\n[系统纠错] 上次输出无法解析为要求的 JSON 结构，请严格只输出一个 "
                + "{\"intent\":...,\"confidence\":...,\"slots\":{...},\"reason\":...} JSON 对象，不要输出任何其他文字。";
    }

    /** 指数退避 + 抖动（§6 组件一）：delay = min(base×2^attempt + random(0,base), maxDelay)。 */
    private void sleepBackoff(int attempt) {
        long base = props.getRetryBaseDelayMs();
        long max = props.getRetryMaxDelayMs();
        long delay = Math.min(base * (1L << Math.min(attempt, 10)) + ThreadLocalRandom.current().nextLong(base + 1), max);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void record(long start, boolean ok, Integer in, Integer out) {
        ModelCallRecorder r = recorder == null ? null : recorder.getIfAvailable();
        if (r != null) {
            r.record(ModelCallLogPurpose.INTENT, "dashscope", intentModelName,
                    in, out, System.currentTimeMillis() - start, ok, null, null, null);
        }
    }
}
