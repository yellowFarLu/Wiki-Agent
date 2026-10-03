package com.wikiagent.service.retrieve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.service.agent.JsonExtractor;
import com.wikiagent.service.retrieve.ConflictCandidateDetector.RepChunkView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Task 6 ConflictGuard LLM 精判（qwen-flash 结构化输出，fail-open）。
 * <p>
 * 对候选 chunk 对做结构化冲突判定：prompt 要求仅输出单行 JSON，ObjectMapper 解析；
 * 返回的 chunkIdA/B 必须等于输入对的两个 id（顺序可调换），否则丢弃。
 * 所有异常/超时/非法 JSON/候选集外 chunkId 一律视为“无冲突”放行并告警，绝不阻断问答主链路。
 * <p>
 * Bean 装配：{@code wikiagent.conflict.guard.enabled=true} 时生效；
 * 注入 {@code intentChatModel}（与 {@link com.wikiagent.infrastructure.routing.DashScopeLlmRouter} 同一路由）。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.conflict.guard.enabled", havingValue = "true")
public class ConflictLlmJudge {

    private static final Logger log = LoggerFactory.getLogger(ConflictLlmJudge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SYSTEM_PROMPT = """
            你是知识库冲突判定器。你的任务是：给定一条用户 query 和两段知识库原文，
            只判断是否对同一实体同一属性给出了不同事实值。
            例如：
            - 苹果售价5元 vs 苹果售价3元 → 冲突（同一实体“苹果”，同一属性“售价”，事实值不同）
            - 苹果很好吃 vs 苹果很甜 → 不冲突（属性不同/无明确事实冲突）
            - 苹果是水果 vs 苹果是蔬菜 → 冲突（同一实体“苹果”，同一属性“类别”，事实值不同）
            必须只输出一个单行 JSON，不要输出任何其他文字、不要 markdown 代码块：
            {"conflict":true|false,"entity":"实体名称","attribute":"属性名称","valueA":"A的值","valueB":"B的值"}
            """;

    private final ChatModel chatModel;
    private final ModelCallRecorder recorder;
    private final String modelName;

    public ConflictLlmJudge(@Qualifier("intentChatModel") ChatModel chatModel,
                            ObjectProvider<ModelCallRecorder> recorder,
                            @Value("${wikiagent.routing.intent-model:qwen-flash}") String modelName) {
        this.chatModel = chatModel;
        this.recorder = recorder == null ? null : recorder.getIfAvailable();
        this.modelName = modelName;
    }

    /**
     * 判定两条 chunk 是否对用户 query 构成冲突。
     *
     * @param query 用户原始 query
     * @param pair  两条 RepChunkView，顺序无关
     * @return 结构化裁决；无冲突/异常时返回 empty（fail-open）
     */
    public Optional<ConflictVerdict> judge(String query, List<RepChunkView> pair) {
        if (query == null || query.isBlank() || pair == null || pair.size() != 2) {
            return Optional.empty();
        }
        String textA = Objects.requireNonNullElse(pair.get(0).content(), "");
        String textB = Objects.requireNonNullElse(pair.get(1).content(), "");
        if (textA.isBlank() || textB.isBlank()) {
            return Optional.empty();
        }

        String userText = buildUserText(query, pair.get(0), pair.get(1));
        long started = System.currentTimeMillis();
        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(userText))));
            long latency = System.currentTimeMillis() - started;
            String raw = extractText(response);
            ParseOutcome outcome = parseAndValidate(raw, pair.get(0).childId(), pair.get(1).childId());
            recordCall(tokensOf(response, true), tokensOf(response, false), latency, outcome.ok());
            return outcome.verdict();
        } catch (Exception e) {
            recordCall(null, null, System.currentTimeMillis() - started, false);
            log.warn("Conflict judge LLM 调用失败，按无冲突放行: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String buildUserText(String query, RepChunkView a, RepChunkView b) {
        return "用户 query：" + query + "\n\n"
                + "原文 A（chunkId=" + a.childId() + "）：" + a.content() + "\n\n"
                + "原文 B（chunkId=" + b.childId() + "）：" + b.content();
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private ParseOutcome parseAndValidate(String raw, String expectedA, String expectedB) {
        if (raw == null || raw.isBlank()) {
            return ParseOutcome.failed();
        }
        Map<String, Object> json;
        try {
            json = JsonExtractor.parseObject(raw);
            if (json.isEmpty()) {
                // JsonExtractor 在提取失败时返回空 Map，但可能包含嵌套解析问题；
                // 再尝试直接解析一次，作为兜底
                json = MAPPER.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("Conflict judge 输出非法 JSON，按无冲突放行: raw={}", raw);
            return ParseOutcome.failed();
        }

        Object conflict = json.get("conflict");
        if (conflict == null || !Boolean.TRUE.equals(conflict)) {
            // 解析成功但判定无冲突，属正常结果
            return ParseOutcome.noConflict();
        }

        String entity = toString(json.get("entity"));
        String attribute = toString(json.get("attribute"));
        String valueA = toString(json.get("valueA"));
        String valueB = toString(json.get("valueB"));

        // 若 LLM 返回了 chunkIdA/B，必须等于输入对的两个 id（顺序可调换），否则丢弃；
        // 未返回时直接用输入对 id（ prompt 未强制要求模型回传 id ）。
        String chunkIdA = toString(json.get("chunkIdA"));
        String chunkIdB = toString(json.get("chunkIdB"));
        if (chunkIdA != null || chunkIdB != null) {
            Set<String> expected = Set.of(expectedA, expectedB);
            if (chunkIdA == null || chunkIdB == null
                    || !expected.contains(chunkIdA) || !expected.contains(chunkIdB)) {
                log.warn("Conflict judge 返回 chunkId 不在候选集内，丢弃: chunkIdA={} chunkIdB={} expected={}",
                        chunkIdA, chunkIdB, expected);
                return ParseOutcome.failed();
            }
            // LLM 以 (B, A) 顺序返回时，valueA/valueB 随 id 一并交换，保证规整后 id 与值对应
            if (chunkIdA.equals(expectedB)) {
                String tmp = valueA;
                valueA = valueB;
                valueB = tmp;
            }
        }

        // 始终规整为输入对 (expectedA, expectedB) 的顺序，避免下游误判方向
        return ParseOutcome.ok(new ConflictVerdict(expectedA, expectedB, entity, attribute, valueA, valueB));
    }

    /** 解析结果：ok 表示 LLM 输出被成功解析且通过校验（含 conflict=false 的正常无冲突）。 */
    private record ParseOutcome(boolean ok, Optional<ConflictVerdict> verdict) {
        static ParseOutcome ok(ConflictVerdict verdict) {
            return new ParseOutcome(true, Optional.of(verdict));
        }

        static ParseOutcome noConflict() {
            return new ParseOutcome(true, Optional.empty());
        }

        static ParseOutcome failed() {
            return new ParseOutcome(false, Optional.empty());
        }
    }

    private static String toString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private void recordCall(Integer in, Integer out, long latency, boolean ok) {
        if (recorder != null) {
            recorder.record(ModelCallLogPurpose.CONFLICT_JUDGE, "dashscope", modelName,
                    in, out, latency, ok, null, null, null);
        }
    }

    private static Integer tokensOf(ChatResponse resp, boolean prompt) {
        try {
            if (resp == null || resp.getMetadata() == null) {
                return null;
            }
            Usage u = resp.getMetadata().getUsage();
            if (u == null) {
                return null;
            }
            return prompt ? u.getPromptTokens() : u.getCompletionTokens();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 结构化冲突裁决。
     *
     * @param chunkIdA  输入对第一条 chunk 的 ID
     * @param chunkIdB  输入对第二条 chunk 的 ID
     * @param entity    被判定为冲突的实体名称
     * @param attribute 被判定为冲突的属性名称
     * @param valueA    A 侧事实值
     * @param valueB    B 侧事实值
     */
    public record ConflictVerdict(String chunkIdA, String chunkIdB,
                                   String entity, String attribute,
                                   String valueA, String valueB) {
    }
}
