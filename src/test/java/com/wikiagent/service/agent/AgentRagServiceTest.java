package com.wikiagent.service.agent;

import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.infrastructure.trace.RagTraceRecorder;
import com.wikiagent.service.chat.ChatStreamer;
import com.wikiagent.service.chat.FallbackAnswerService;
import com.wikiagent.service.chat.PromptComposer;
import com.wikiagent.service.chat.SseSender;
import com.wikiagent.service.retrieve.QueryRewriteService;
import com.wikiagent.service.retrieve.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Agentic RAG 编排逻辑测试：路由、规划、迭代检索、充分性评估与降级路径。 */
class AgentRagServiceTest {

    private ChatModel chatModel;
    private RetrievalService retrieval;
    private QueryRewriteService rewriter;
    private ChatStreamer streamer;
    private FallbackAnswerService fallback;
    private RagTraceRecorder traceRecorder;
    private SseSender sse;
    private SseEmitter emitter;
    private AgentRagService service;
    private WikiAgentProperties props;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        retrieval = mock(RetrievalService.class);
        rewriter = mock(QueryRewriteService.class);
        streamer = mock(ChatStreamer.class);
        fallback = mock(FallbackAnswerService.class);
        traceRecorder = mock(RagTraceRecorder.class);
        emitter = mock(SseEmitter.class);
        sse = new SseSender(emitter);
        props = new WikiAgentProperties(null, null, null,
                new WikiAgentProperties.Agent(true, 2, 3));
        service = new AgentRagService(props, chatModel, retrieval, rewriter, streamer,
                fallback, traceRecorder);
        when(retrieval.newAccumulator()).thenReturn(new RetrievalService.Accumulator());
    }

    private void llmReturns(String... outputs) {
        var stub = when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(outputs[0]));
        for (int i = 1; i < outputs.length; i++) {
            stub = stub.thenReturn(chatResponse(outputs[i]));
        }
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 评估器判"证据充分"的标准输出。 */
    private static final String GRADE_OK = "{\"sufficient\":true,\"refinedQuery\":\"\"}";

    private static RetrievalService.RetrievalResult result(String context) {
        return new RetrievalService.RetrievalResult(
                context.isEmpty() ? List.of()
                        : List.of(new RetrievalService.Source(1, "doc1", 1, null, "片段", null, 0.9, "a.md")),
                context);
    }

    @Test
    void 路由为direct时应跳过检索直接回答() {
        llmReturns("{\"mode\":\"direct\",\"queries\":[]}");

        service.run("u1", "s1", "你好，你是谁？", sse);

        verifyNoInteractions(retrieval, rewriter);
        verify(streamer, times(1)).stream(any(Prompt.class), eq(sse), isNull(), eq("u1"), eq("s1"));
        // 捕获 direct 回答的 system 提示词
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(streamer).stream(captor.capture(), eq(sse), isNull(), eq("u1"), eq("s1"));
        assertTrue(captor.getValue().getInstructions().stream()
                .anyMatch(m -> m.getText() != null && m.getText().contains("问候")), "应使用 direct 模式提示词");
    }

    @Test
    void 路由为web时应跳过检索直接联网兜底() {
        llmReturns("{\"mode\":\"web\",\"queries\":[]}");

        service.run("u1", "s1", "今天天气如何？", sse);

        verifyNoInteractions(retrieval, rewriter);
        verify(streamer, never()).stream(any(Prompt.class), eq(sse), any(), any(), any());
        // 实时问题 → 直接走联网兜底（enable_search）
        verify(fallback, times(1)).answer(eq("今天天气如何？"), eq(sse));
    }

    @Test
    void 规划的多查询应在一轮内全部检索并去重累积() {
        llmReturns("{\"mode\":\"search\",\"queries\":[\"报销时限\",\"差旅标准\"]}", GRADE_OK);
        when(retrieval.assemble(any(), any())).thenReturn(result("证据"));

        service.run("u1", "s1", "报销时限和差旅标准是什么？", sse);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> queriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrieval, times(1)).search(any(RetrievalService.Accumulator.class), queriesCaptor.capture());
        assertEquals(List.of("报销时限", "差旅标准"), queriesCaptor.getValue());
        // 证据充分 → 规划 + 第 1 轮评估（maxRounds=2，最后一轮才跳过评估）
        verify(chatModel, times(2)).call(any(Prompt.class));
        verify(streamer, times(1)).stream(any(Prompt.class), eq(sse), isNull(), eq("u1"), eq("s1"));
    }

    @Test
    void 评估不充分时应带改写查询进入第二轮且末轮再评估() {
        llmReturns(
                "{\"mode\":\"search\",\"queries\":[\"q1\"]}",
                "{\"sufficient\":false,\"refinedQuery\":\"q2 更好\"}",
                GRADE_OK);
        when(retrieval.assemble(any(), any())).thenReturn(result("第一轮证据"), result("第二轮证据"));

        service.run("u1", "s1", "问题", sse);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> queriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrieval, times(2)).search(any(RetrievalService.Accumulator.class), queriesCaptor.capture());
        assertEquals(List.of("q1"), queriesCaptor.getAllValues().get(0));
        assertEquals(List.of("q2 更好"), queriesCaptor.getAllValues().get(1));
        // 规划 + 第 1 轮评估（不足）+ 第 2 轮（末轮）评估（充分）= 3 次
        verify(chatModel, times(3)).call(any(Prompt.class));
        verify(streamer, times(1)).stream(any(Prompt.class), eq(sse), isNull(), eq("u1"), eq("s1"));
    }

    @Test
    void 单轮检索时末轮证据仍应评估以决定是否兜底() {
        WikiAgentProperties oneRound = new WikiAgentProperties(null, null, null,
                new WikiAgentProperties.Agent(true, 1, 3));
        service = new AgentRagService(oneRound, chatModel, retrieval, rewriter, streamer,
                fallback, traceRecorder);
        llmReturns("{\"mode\":\"search\",\"queries\":[\"q1\"]}", GRADE_OK);
        when(retrieval.assemble(any(), any())).thenReturn(result("证据"));

        service.run("u1", "s1", "问题", sse);

        verify(retrieval, times(1)).search(any(), anyList());
        // 规划 + 末轮评估 = 2 次（末轮评估用于弱命中兜底判定）
        verify(chatModel, times(2)).call(any(Prompt.class));
        verify(streamer, times(1)).stream(any(Prompt.class), eq(sse), isNull(), eq("u1"), eq("s1"));
    }

    @Test
    void 规划输出无法解析时应降级为原始问题检索() {
        llmReturns("抱歉，我不会输出 JSON");
        when(retrieval.assemble(any(), any())).thenReturn(result("证据"));

        service.run("u1", "s1", "原始问题", sse);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> queriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrieval, times(1)).search(any(RetrievalService.Accumulator.class), queriesCaptor.capture());
        assertEquals(List.of("原始问题"), queriesCaptor.getValue());
        verify(streamer, times(1)).stream(any(Prompt.class), eq(sse), isNull(), eq("u1"), eq("s1"));
    }

    @Test
    void 证据为空时应改写重检最终进入未命中兜底() {
        llmReturns("{\"mode\":\"search\",\"queries\":[\"q1\"]}");
        when(retrieval.assemble(any(), any())).thenReturn(result(""));
        when(rewriter.rewrite("问题")).thenReturn("改写后的问题");

        service.run("u1", "s1", "问题", sse);

        verify(rewriter, times(1)).rewrite("问题");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> queriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrieval, times(2)).search(any(RetrievalService.Accumulator.class), queriesCaptor.capture());
        assertEquals(List.of("改写后的问题"), queriesCaptor.getAllValues().get(1));
        verify(streamer, never()).stream(any(Prompt.class), eq(sse), any(), any(), any());
        // 两轮均无证据 → 交给未命中兜底（联网搜索 / 模型自身知识）
        verify(fallback, times(1)).answer(eq("问题"), eq(sse));
    }

    @Test
    void 末轮评估证据不足时即使有弱命中也应走未命中兜底() {
        WikiAgentProperties oneRound = new WikiAgentProperties(null, null, null,
                new WikiAgentProperties.Agent(true, 1, 3));
        service = new AgentRagService(oneRound, chatModel, retrieval, rewriter, streamer,
                fallback, traceRecorder);
        llmReturns("{\"mode\":\"search\",\"queries\":[\"年假\"]}",
                "{\"sufficient\":false,\"refinedQuery\":\"\"}");
        // Milvus 总会返回 topK 近邻（无阈值），context 非空但不相关
        when(retrieval.assemble(any(), any())).thenReturn(result("一份无关的简历内容"));

        service.run("u1", "s1", "公司年假制度是怎样的？", sse);

        verify(streamer, never()).stream(any(Prompt.class), eq(sse), any(), any(), any());
        verify(fallback, times(1)).answer(eq("公司年假制度是怎样的？"), eq(sse));
    }

    @Test
    void 最终回答应使用证据与原始问题组装的提示词() {
        llmReturns("{\"mode\":\"search\",\"queries\":[\"q1\"]}", GRADE_OK);
        when(retrieval.assemble(any(), any())).thenReturn(result("[1] 来源: a.md\n事实内容"));

        service.run("u1", "s1", "问题", sse);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(streamer).stream(captor.capture(), eq(sse), isNull(), eq("u1"), eq("s1"));
        String joined = captor.getValue().getInstructions().stream()
                .map(m -> m.getText() == null ? "" : m.getText())
                .reduce("", String::concat);
        assertTrue(joined.contains(PromptComposer.SYSTEM.substring(0, 20)), "应包含系统提示词");
        assertTrue(joined.contains("事实内容"), "应包含证据上下文");
        assertTrue(joined.contains("问题"), "应包含用户问题");
    }
}
