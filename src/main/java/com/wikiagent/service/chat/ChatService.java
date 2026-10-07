package com.wikiagent.service.chat;

import com.wikiagent.application.agent.AgentOrchestrator;
import com.wikiagent.application.knowledge.RagEvalSampleRecorder;
import com.wikiagent.application.multiagent.MultiAgentOrchestrator;
import com.wikiagent.application.multiagent.TenantKey;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.dto.ChatRequest;
import com.wikiagent.infrastructure.gateway.GuardrailAdvisorChain;
import com.wikiagent.infrastructure.security.StreamingOutputGuardrailSender;
import com.wikiagent.service.agent.AgentRagService;
import com.wikiagent.service.retrieve.QueryRewriteService;
import com.wikiagent.service.retrieve.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话编排入口（运行于 chatExecutor）。
 * <p>
 * <b>v5 §19 安全网关</b>：所有路径先过 {@link GuardrailAdvisorChain#checkInput}，
 * BLOCK 直接返回 blocked 事件不触达 LLM；SANITIZE 后用脱敏内容继续。
 * <p>
 * <b>四层链路切换</b>：
 * <ol>
 *   <li>{@code wikiagent.pero.enabled=true} + {@code wikiagent.multi-agent.enabled=true}
 *       且请求含 domain/subDomain → v6 MultiAgentOrchestrator（9×6×5 垂直隔离）</li>
 *   <li>{@code wikiagent.pero.enabled=false} → v1-v2 {@link AgentOrchestrator}
 *       （Route→Plan→Execute→Reflexion 单 Agent 主循环）</li>
 *   <li>{@code wikiagent.agent.enabled=true} → §2 Agentic RAG（AgentRagService）</li>
 *   <li>其他 → 简单 RAG 固定管道（改写 → 检索 → 生成）</li>
 * </ol>
 * 通过 SseEmitter 推送事件：stage / sources / delta / done / error / blocked。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final WikiAgentProperties props;
    private final AgentRagService agentRag;
    private final QueryRewriteService rewriter;
    private final RetrievalService retrieval;
    private final ChatStreamer streamer;
    private final MultiAgentOrchestrator multiAgent;
    private final ObjectProvider<AgentOrchestrator> v1v2OrchestratorProvider;
    private final GuardrailAdvisorChain guardrailChain;
    private final FallbackAnswerService fallback;
    private final ChatHistoryService historyService;
    /** RAG 回答质量评测样本采集器（wikiagent.answer-eval.enabled=false 时不装配，为 null 零影响）。 */
    private final RagEvalSampleRecorder ragEvalRecorder;
    private final boolean multiAgentEnabled;
    private final boolean peroEnabled;

    /**
     * J2：自身的 Spring 代理（@Lazy 打破自注入循环）。{@link #chat} 必须在请求线程同步执行
     * 以捕获 MDC，再经代理进入 {@code @Async} 边界；单测直连 new 构造时为 null，退化为同线程执行。
     */
    @Autowired
    @Lazy
    private ChatService self;

    public ChatService(WikiAgentProperties props, AgentRagService agentRag, QueryRewriteService rewriter,
                       RetrievalService retrieval, ChatStreamer streamer, MultiAgentOrchestrator multiAgent,
                       ObjectProvider<AgentOrchestrator> v1v2OrchestratorProvider,
                       GuardrailAdvisorChain guardrailChain,
                       FallbackAnswerService fallback,
                       ChatHistoryService historyService,
                       ObjectProvider<RagEvalSampleRecorder> ragEvalRecorderProvider,
                       @Value("${wikiagent.multi-agent.enabled:true}") boolean multiAgentEnabled,
                       @Value("${wikiagent.pero.enabled:true}") boolean peroEnabled) {
        this.props = props;
        this.agentRag = agentRag;
        this.rewriter = rewriter;
        this.retrieval = retrieval;
        this.streamer = streamer;
        this.multiAgent = multiAgent;
        this.v1v2OrchestratorProvider = v1v2OrchestratorProvider;
        this.guardrailChain = guardrailChain;
        this.fallback = fallback;
        this.historyService = historyService;
        this.ragEvalRecorder = ragEvalRecorderProvider == null ? null : ragEvalRecorderProvider.getIfAvailable();
        this.multiAgentEnabled = multiAgentEnabled;
        this.peroEnabled = peroEnabled;
    }

    /**
     * 对话入口（<b>请求线程同步执行</b>）：在 @Async 边界之前捕获 MDC 上下文，
     * 再经自身 Spring 代理提交到 chatExecutor，保证 traceId 跨越异步线程不丢失。
     */
    public void chat(ChatRequest request, SseEmitter emitter) {
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        ChatService proxy = self;
        if (proxy != null) {
            proxy.chatAsync(request, emitter, capturedMdc);
        } else {
            // 无 Spring 代理（旧装配/单测直连）：同线程执行，MDC 恢复语义保持一致
            chatAsync(request, emitter, capturedMdc);
        }
    }

    /**
     * 实际对话处理（运行于 chatExecutor）。
     *
     * @param capturedMdc 提交请求时在请求线程捕获的 MDC 快照；进入异步线程后原样恢复，
     *                    结束时（finally）恢复为工作线程原有 MDC，避免线程池线程串号
     */
    @Async("chatExecutor")
    public void chatAsync(ChatRequest request, SseEmitter emitter, Map<String, String> capturedMdc) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        if (capturedMdc != null) {
            MDC.setContextMap(capturedMdc);
        }
        try {
            doChat(request, emitter);
        } finally {
            // 内联恢复：不依赖任何外部 MDC 工具类（I 代理的装饰器独立演进）
            if (previousMdc != null) {
                MDC.setContextMap(previousMdc);
            } else {
                MDC.clear();
            }
        }
    }

    private void doChat(ChatRequest request, SseEmitter emitter) {
        SseSender sse = new SseSender(emitter);
        String rawQuestion = request.question() == null ? "" : request.question().strip();
        String userId = request.identity() == null ? "anonymous" : request.identity();
        // RAG 回答评测采集（方案 docs/rag-accuracy-eval.md）：闭包容器在 SSE 流期间被填充，
        // done 前（onAnswer 回调）统一读取——sources 由 RagEvalCaptureSender 拦截，
        // question/channel 由下方各分支赋值。
        AtomicReference<List<RetrievalService.Source>> evalSources = new AtomicReference<>();
        AtomicReference<String> evalQuestion = new AtomicReference<>();
        AtomicReference<String> evalChannel = new AtomicReference<>();
        try {
            if (rawQuestion.isEmpty()) {
                sse.send("error", Map.of("message", "问题不能为空"));
                sse.complete();
                return;
            }

            // 业务会话 ID：优先使用前端显式传入值（页面会话内复用，贯穿记忆/trace/审计/反馈），
            // 缺省时生成唯一 ID。无论来源如何，都通过 SSE session 事件回传给前端展示。
            String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                    ? "s-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                    : request.sessionId().strip();
            sse.send("session", Map.of("sessionId", sessionId));

            // 持久化用户消息
            historyService.save(sessionId, "user", rawQuestion);

            // J2 流式输出网关：最外层包装——delta 累积、done 前经输出检测器链校验完整答案；
            // 通过才发 done 并持久化（SANITIZE 时落脱敏文本），违规则改发 blocked、
            // 答案不落 assistant 历史而写 blocked 标记（已发 delta 受 SSE 本质限制不撤回）。
            // 阻断审计复用 GuardrailAdvisorChain.checkOutput 内既有 GatewayAuditService。
            // 最外层再套 RagEvalCaptureSender（capture(guardrail(base))）：拦截 sources 事件
            // 供评测采集；采集动作在 onAnswer（答案已过输出网关脱敏，blocked 路径不采集）。
            sse = new RagEvalCaptureSender(new StreamingOutputGuardrailSender(new SseSender(emitter),
                    guardrailChain, userId, sessionId,
                    new StreamingOutputGuardrailSender.AnswerFinalizer() {
                        @Override
                        public void onAnswer(String sid, String finalAnswer) {
                            if (finalAnswer != null && !finalAnswer.isBlank()) {
                                historyService.save(sid, "assistant", finalAnswer, evalSources.get());
                            }
                            if (ragEvalRecorder != null) {
                                ragEvalRecorder.record(sid, userId, evalQuestion.get(), finalAnswer,
                                        evalSources.get(), evalChannel.get());
                            }
                        }

                        @Override
                        public void onBlocked(String sid, String violationType, String reason) {
                            historyService.save(sid, "blocked",
                                    "[输出被安全网关拦截] type=" + violationType
                                            + " reason=" + truncateMarker(reason, 500));
                        }
                    }), evalSources);

            // === v5 §19 输入安全网关（注入检测 / PII 脱敏 / 合规）===
            GuardrailAdvisorChain.ChainResult inputResult =
                    guardrailChain.checkInput(rawQuestion, userId, sessionId);
            if (!inputResult.passed()) {
                log.warn("输入被安全网关拦截 userId={} reason={}", userId, inputResult.blockedReason());
                sse.send("blocked", Map.of(
                        "reason", inputResult.blockedReason() == null ? "内容不合规" : inputResult.blockedReason(),
                        "violationType", inputResult.violationType() == null ? "UNKNOWN" : inputResult.violationType()));
                sse.complete();
                return;
            }
            String question = inputResult.content() == null ? rawQuestion : inputResult.content().strip();
            evalQuestion.set(question);

            // === 优先级 1：v6 Multi-Agent（PERO 开启 + 9×6 路由字段齐全）===
            if (peroEnabled && multiAgentEnabled && multiAgent.enabled()
                    && request.domain() != null && request.subDomain() != null) {
                String identity = request.identity() == null ? "any" : request.identity();
                TenantKey key = new TenantKey(request.domain(), request.subDomain(), identity);
                evalChannel.set("multi-agent");
                multiAgent.run(key, userId, sessionId, question, sse);
                return;
            }

            // === 优先级 2：v1-v2 Plan-Execute-Reflexion 主循环（pero.enabled=false）===
            AgentOrchestrator v1v2 = v1v2OrchestratorProvider.getIfAvailable();
            if (!peroEnabled && v1v2 != null) {
                evalChannel.set("orchestrator-v1v2");
                v1v2.run(userId, sessionId, question, sse);
                return;
            }

            // === 优先级 3：§2 Agentic RAG ===
            if (props.agent() != null && props.agent().enabled()) {
                evalChannel.set("agent-rag");
                agentRag.run(userId, sessionId, question, sse);
                return;
            }

            // === 优先级 4：简单 RAG 固定管道 ===
            evalChannel.set("legacy-rag");
            legacyChat(question, sse);
        } catch (Exception e) {
            log.error("对话处理失败", e);
            sse.send("error", Map.of("message", "对话处理失败: " + messageOf(e)));
            sse.complete();
        }
    }

    /** 简单 RAG 固定管道（agent.enabled=false 时的回退链路）。 */
    private void legacyChat(String question, SseSender sse) {
        // 1. 查询改写
        sse.send("stage", Map.of("stage", "rewriting"));
        String rewritten = rewriter.rewrite(question);

        // 2. 混合检索 + 父文档替换
        sse.send("stage", Map.of("stage", "retrieving", "rewrittenQuery", rewritten));
        RetrievalService.RetrievalResult result = retrieval.retrieve(rewritten);
        sse.send("sources", result.sources());

        if (result.context().isEmpty()) {
            // 知识库完全未命中：两级兜底（联网搜索 → 模型自身知识；均关闭时拒答）
            fallback.answer(question, sse);
            return;
        }

        // 3. 组装 Prompt 并流式生成
        sse.send("stage", Map.of("stage", "generating"));
        streamer.stream(new Prompt(List.of(
                new SystemMessage(PromptComposer.SYSTEM),
                new UserMessage(PromptComposer.user(result.context(), question)))), sse);
    }

    private static String messageOf(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    /** 截断 blocked 标记中的原因文本，避免撑爆历史列。 */
    private static String truncateMarker(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= maxLen ? oneLine : oneLine.substring(0, maxLen);
    }

    /**
     * RAG 回答评测采集装饰器（方案 docs/rag-accuracy-eval.md）：拦截 {@code sources} SSE 事件
     * 缓存引用来源（父文档粒度），其余事件原样透传。采集动作不在本类——由 AnswerFinalizer.onAnswer
     * 统一触发（答案此时已过输出安全网关脱敏，blocked 路径天然不采集）。
     */
    private static final class RagEvalCaptureSender extends SseSender {

        private final SseSender delegate;
        private final AtomicReference<List<RetrievalService.Source>> out;

        RagEvalCaptureSender(SseSender delegate, AtomicReference<List<RetrievalService.Source>> out) {
            super();
            this.delegate = delegate;
            this.out = out;
        }

        @Override
        public boolean send(String event, Object data) {
            if ("sources".equals(event) && data instanceof List<?> list) {
                List<RetrievalService.Source> sources = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof RetrievalService.Source s) {
                        sources.add(s);
                    }
                }
                out.set(sources);
            }
            return delegate.send(event, data);
        }

        @Override
        public void complete() {
            delegate.complete();
        }
    }
}
