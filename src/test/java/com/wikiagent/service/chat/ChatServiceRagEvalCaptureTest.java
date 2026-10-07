package com.wikiagent.service.chat;

import com.wikiagent.application.agent.AgentOrchestrator;
import com.wikiagent.application.gateway.GatewayAuditService;
import com.wikiagent.application.knowledge.RagEvalSampleRecorder;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatService RAG 回答评测采集接线单测（legacy 固定管道，全部依赖桩）：
 * ① 合规答案 → recorder 收到 (sessionId, question, answer, sources, channel=legacy-rag)；
 * ② 输出网关 blocked → 不采集（违规答案不得计入准确率）；
 * ③ recorder 未装配（ObjectProvider 空）→ 链路正常零影响。
 */
class ChatServiceRagEvalCaptureTest {

    private WikiAgentProperties props;
    private QueryRewriteService rewriter;
    private RetrievalService retrieval;
    private ChatStreamer streamer;
    private MultiAgentOrchestrator multiAgent;
    private GuardrailAdvisorChain chain;
    private FallbackAnswerService fallback;
    private ChatHistoryService history;
    private RagEvalSampleRecorder recorder;
    private SseEmitter emitter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        props = new WikiAgentProperties(null,
                new WikiAgentProperties.Retrieve(20, 10, 60, 12000), null, null);
        rewriter = mock(QueryRewriteService.class);
        when(rewriter.rewrite(anyString())).thenReturn("改写后的问题");
        retrieval = mock(RetrievalService.class);
        RetrievalService.RetrievalResult hit = new RetrievalService.RetrievalResult(
                List.of(
                        new RetrievalService.Source(1, "d1", 1, 1, "片段一", "a1", 0.9, "f1.txt"),
                        new RetrievalService.Source(2, "d2", 1, 2, "片段二", null, 0.8, "f2.txt")),
                "知识库上下文");
        when(retrieval.retrieve(any(String.class))).thenReturn(hit);
        streamer = mock(ChatStreamer.class);
        multiAgent = mock(MultiAgentOrchestrator.class);
        fallback = mock(FallbackAnswerService.class);
        history = mock(ChatHistoryService.class);
        recorder = mock(RagEvalSampleRecorder.class);
        emitter = mock(SseEmitter.class);

        GatewayAuditService audit = mock(GatewayAuditService.class);
        when(audit.logAudit(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyDouble(), any(), any(), anyString())).thenReturn(1L);
        chain = new GuardrailAdvisorChain(
                List.of(new KeywordBlacklistDetector(), new LlmJudgeDetector("0.7", ""),
                        new BlockOnMarker()),
                audit, true, true);
    }

    /** 命中 BLOCKME 的输出阻断检测器（关键词黑名单/规则 LLM judge 不会阻断该文本）。 */
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

    private ChatService service(RagEvalSampleRecorder sampleRecorder) {
        ObjectProvider<AgentOrchestrator> v1v2 = mock(ObjectProvider.class);
        when(v1v2.getIfAvailable()).thenReturn(null);
        ObjectProvider<RagEvalSampleRecorder> recorderProvider = mock(ObjectProvider.class);
        when(recorderProvider.getIfAvailable()).thenReturn(sampleRecorder);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.wikiagent.application.business.BusinessIntentGateway> businessGateway =
                mock(ObjectProvider.class);
        when(businessGateway.getIfAvailable()).thenReturn(null);
        return new ChatService(props, mock(AgentRagService.class), rewriter, retrieval, streamer,
                multiAgent, v1v2, chain, fallback, history, recorderProvider, businessGateway, false, false);
    }

    private void stubStream(String answer) {
        doAnswer(inv -> {
            SseSender sse = inv.getArgument(1);
            sse.send("delta", Map.of("text", answer));
            sse.send("done", Map.of());
            sse.complete();
            return null;
        }).when(streamer).stream(any(Prompt.class), any(SseSender.class));
    }

    @Test
    void 合规答案采集含来源链路与问题() {
        stubStream("这是合规的完整答案");

        service(recorder).chat(new ChatRequest("知识库问题", null, null, null, "sess-ev-1"), emitter);

        verify(recorder).record(eq("sess-ev-1"), eq("anonymous"), eq("知识库问题"),
                eq("这是合规的完整答案"),
                eq(List.of(
                        new RetrievalService.Source(1, "d1", 1, 1, "片段一", "a1", 0.9, "f1.txt"),
                        new RetrievalService.Source(2, "d2", 1, 2, "片段二", null, 0.8, "f2.txt"))),
                eq("legacy-rag"));
    }

    @Test
    void 输出网关blocked不采集() {
        stubStream("前文 BLOCKME 违规内容");

        service(recorder).chat(new ChatRequest("问题", null, null, null, "sess-ev-2"), emitter);

        verify(recorder, never()).record(any(), any(), any(), any(), any(), any());
        verify(history).save(eq("sess-ev-2"), eq("blocked"), contains("TOXIC_CONTENT"));
    }

    @Test
    void 采集器未装配时链路零影响() {
        stubStream("合规答案");

        service(null).chat(new ChatRequest("问题", null, null, null, "sess-ev-3"), emitter);

        verify(history).save(eq("sess-ev-3"), eq("assistant"), eq("合规答案"));
        verify(recorder, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 无引用路径sources为空仍采集() {
        RetrievalService.RetrievalResult miss = new RetrievalService.RetrievalResult(List.of(), "");
        when(retrieval.retrieve(any(String.class))).thenReturn(miss);
        doAnswer(inv -> {
            SseSender sse = inv.getArgument(1);
            sse.send("delta", Map.of("text", "模型自身知识回答"));
            sse.send("done", Map.of());
            sse.complete();
            return null;
        }).when(fallback).answer(anyString(), any(SseSender.class));

        service(recorder).chat(new ChatRequest("知识库没有的问题", null, null, null, "sess-ev-4"), emitter);

        // sources 事件仍会发出（空列表）→ 采集到的来源为空列表，落库时 source_doc_ids 为 null
        verify(recorder).record(eq("sess-ev-4"), eq("anonymous"), eq("知识库没有的问题"),
                eq("模型自身知识回答"), eq(List.of()), eq("legacy-rag"));
    }
}
