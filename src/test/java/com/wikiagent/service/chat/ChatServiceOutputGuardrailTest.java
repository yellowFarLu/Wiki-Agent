package com.wikiagent.service.chat;

import com.wikiagent.application.agent.AgentOrchestrator;
import com.wikiagent.application.gateway.GatewayAuditService;
import com.wikiagent.application.multiagent.MultiAgentOrchestrator;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.dto.ChatRequest;
import com.wikiagent.infrastructure.gateway.GuardrailAdvisorChain;
import com.wikiagent.infrastructure.gateway.GuardrailDetector;
import com.wikiagent.infrastructure.gateway.GuardrailResult;
import com.wikiagent.infrastructure.gateway.KeywordBlacklistDetector;
import com.wikiagent.infrastructure.gateway.LlmJudgeDetector;
import com.wikiagent.service.agent.AgentRagService;
import com.wikiagent.service.retrieve.QueryRewriteService;
import com.wikiagent.service.retrieve.RetrievalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * AC-J2 ChatService 接线单测（legacy 固定管道，全部依赖桩）：
 * ① 流式答案命中输出网关 → 已发 delta 不撤回，但 done 被拦截改发 blocked，
 *    答案不落 assistant 历史、写 blocked 标记，SSE 正常 complete；
 * ② 合规答案 → assistant 历史落完整答案；
 * ③ MDC traceId 在对话处理链路中可见且方法返回后请求线程 MDC 原样保留（内联 copy/finally 恢复）。
 */
class ChatServiceOutputGuardrailTest {

    private WikiAgentProperties props;
    private AgentRagService agentRag;
    private QueryRewriteService rewriter;
    private RetrievalService retrieval;
    private ChatStreamer streamer;
    private MultiAgentOrchestrator multiAgent;
    private ObjectProvider<AgentOrchestrator> v1v2;
    private GuardrailAdvisorChain chain;
    private FallbackAnswerService fallback;
    private ChatHistoryService history;
    private SseEmitter emitter;

    /** 命中 BLOCKME 的输出阻断检测器。 */
    private static final class BlockOnMarker implements GuardrailDetector {
        @Override
        public String name() {
            return "test_output_block";
        }

        @Override
        public String direction() {
            return "OUTPUT";
        }

        @Override
        public GuardrailResult check(String content, String userId, String sessionId) {
            return content.contains("BLOCKME")
                    ? GuardrailResult.block(name(), 0.99, "测试桩命中 BLOCKME", "TOXIC_CONTENT")
                    : GuardrailResult.pass(name());
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        props = new WikiAgentProperties(null,
                new WikiAgentProperties.Retrieve(20, 10, 60, 12000), null, null);
        agentRag = mock(AgentRagService.class);
        rewriter = mock(QueryRewriteService.class);
        when(rewriter.rewrite(anyString())).thenReturn("改写后的问题");
        retrieval = mock(RetrievalService.class);
        RetrievalService.RetrievalResult hit = new RetrievalService.RetrievalResult(
                List.of(new RetrievalService.Source(1, "d1", 1, 1, "片段", "a1", 1.0, "f.txt")),
                "知识库上下文");
        when(retrieval.retrieve(any(String.class))).thenReturn(hit);
        streamer = mock(ChatStreamer.class);
        multiAgent = mock(MultiAgentOrchestrator.class);
        v1v2 = mock(ObjectProvider.class);
        when(v1v2.getIfAvailable()).thenReturn(null);
        fallback = mock(FallbackAnswerService.class);
        history = mock(ChatHistoryService.class);
        emitter = mock(SseEmitter.class);

        GatewayAuditService audit = mock(GatewayAuditService.class);
        when(audit.logAudit(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyDouble(), any(), any(), anyString())).thenReturn(1L);
        // 输入：真实关键词/降级 LLM judge 检测器（无 key 规则模式）；输出：测试桩阻断器
        chain = new GuardrailAdvisorChain(
                List.of(new KeywordBlacklistDetector(), new LlmJudgeDetector("0.7", ""),
                        new BlockOnMarker()),
                audit, true, true);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private ChatService service() {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.wikiagent.application.knowledge.RagEvalSampleRecorder> ragEvalRecorder =
                mock(ObjectProvider.class);
        when(ragEvalRecorder.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.wikiagent.application.business.BusinessIntentGateway> businessGateway =
                mock(ObjectProvider.class);
        when(businessGateway.getIfAvailable()).thenReturn(null);
        return new ChatService(props, agentRag, rewriter, retrieval, streamer, multiAgent,
                v1v2, chain, fallback, history, ragEvalRecorder, businessGateway, false, false);
    }

    private void stubStream(String answer, AtomicReference<String> mdcSnapshot) {
        doAnswer(inv -> {
            SseSender sse = inv.getArgument(1);
            if (mdcSnapshot != null) {
                mdcSnapshot.set(MDC.get("traceId"));
            }
            sse.send("delta", Map.of("text", answer));
            sse.send("done", Map.of());
            // 与真实 ChatStreamer 一致：done 后结束 SSE
            sse.complete();
            return null;
        }).when(streamer).stream(any(Prompt.class), any(SseSender.class));
    }

    @Test
    void 违规流式答案不落assistant历史而写blocked标记并结束SSE() {
        stubStream("前文 BLOCKME 违规内容", null);
        MDC.put("traceId", "trace-j2-1");

        service().chat(new ChatRequest("正常的业务问题", null, null, null, "sess-j2-1"), emitter);

        // 用户问题照常落库；助手答案不得落库
        verify(history).save(eq("sess-j2-1"), eq("user"), eq("正常的业务问题"));
        verify(history, never()).save(eq("sess-j2-1"), eq("assistant"), anyString());
        // 改为 blocked 标记
        verify(history).save(eq("sess-j2-1"), eq("blocked"),
                org.mockito.ArgumentMatchers.contains("TOXIC_CONTENT"));
        // blocked 时网关自身 complete 一次；真实流式完成回调再 complete 一次（幂等）
        verify(emitter, org.mockito.Mockito.atLeastOnce()).complete();
    }

    @Test
    void 合规流式答案落assistant历史() {
        stubStream("这是合规的完整答案", null);

        service().chat(new ChatRequest("正常的业务问题", null, null, null, "sess-j2-2"), emitter);

        // user 与 assistant 各保存一次，assistant 内容完整
        verify(history).save(eq("sess-j2-2"), eq("user"), eq("正常的业务问题"));
        verify(history).save(eq("sess-j2-2"), eq("assistant"), eq("这是合规的完整答案"));
        verify(history, never()).save(eq("sess-j2-2"), eq("blocked"), anyString());
        verify(emitter).complete();
    }

    @Test
    void mdcTraceId跨越流式链路且返回后保持() {
        AtomicReference<String> seenInStream = new AtomicReference<>();
        stubStream("合规答案带 trace", seenInStream);
        MDC.put("traceId", "trace-j2-3");

        service().chat(new ChatRequest("问题", null, null, null, "sess-j2-3"), emitter);

        assertThat(seenInStream.get()).isEqualTo("trace-j2-3");
        // 直连（无 Spring 代理）同线程执行后 MDC 仍在
        assertThat(MDC.get("traceId")).isEqualTo("trace-j2-3");
    }
}
