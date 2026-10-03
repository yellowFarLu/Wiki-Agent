package com.wikiagent.service.agent;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.application.prompt.PromptTemplateService;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.prompt.RenderedPrompt;
import com.wikiagent.infrastructure.llm.ChatModelProviderChain;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 缺陷13：agent-rag-generate span 的 outputData 必须带实际命中的提示词 code@version。 */
class AgentRagPromptVersionTest {

    private ChatModel chatModel;
    private RetrievalService retrieval;
    private ChatStreamer streamer;
    private RagTraceRecorder traceRecorder;
    private SseSender sse;
    private final WikiAgentProperties props = new WikiAgentProperties(null, null, null,
            new WikiAgentProperties.Agent(true, 1, 3));

    private static final String GRADE_OK = "{\"sufficient\":true,\"refinedQuery\":\"\"}";

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        retrieval = mock(RetrievalService.class);
        streamer = mock(ChatStreamer.class);
        traceRecorder = mock(RagTraceRecorder.class);
        sse = new SseSender(mock(SseEmitter.class));
        when(retrieval.newAccumulator()).thenReturn(new RetrievalService.Accumulator());
        when(retrieval.assemble(any(), any())).thenReturn(new RetrievalService.RetrievalResult(
                List.of(new RetrievalService.Source(1, "doc1", 1, null, "片段", null, 0.9, "a.md")),
                "证据"));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"mode\":\"search\",\"queries\":[\"q1\"]}")))))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(GRADE_OK)))));
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(bean);
        return op;
    }

    private String generateOutputData() {
        ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
        verify(traceRecorder).record(eq("u1"), eq("s1"), eq("agent-rag-generate"), eq("generate"),
                eq("问题"), output.capture(), eq("OK"), isNull());
        return output.getValue();
    }

    @Test
    void 无模板服务时outputData记录fallback版本() {
        AgentRagService service = new AgentRagService(props, chatModel, retrieval,
                mock(QueryRewriteService.class), streamer, mock(FallbackAnswerService.class),
                traceRecorder);

        service.run("u1", "s1", "问题", sse);

        String output = generateOutputData();
        assertTrue(output.contains("sources=1"), output);
        assertTrue(output.contains(
                "prompt=chat.system@fallback,chat.user@fallback"), output);
    }

    @Test
    void 命中模板时outputData记录具体版本号() {
        PromptTemplateService tpl = mock(PromptTemplateService.class);
        when(tpl.render(eq(PromptComposer.CODE_CHAT_SYSTEM), anyMap()))
                .thenReturn(Optional.of(new RenderedPrompt("系统词", 3)));
        when(tpl.render(eq(PromptComposer.CODE_CHAT_USER), anyMap()))
                .thenReturn(Optional.of(new RenderedPrompt("用户词", 2)));
        AgentRagService service = new AgentRagService(props, chatModel, retrieval,
                mock(QueryRewriteService.class), streamer, mock(FallbackAnswerService.class),
                traceRecorder, provider((ModelCallRecorder) null), provider(tpl),
                "qwen-plus", provider((ChatModelProviderChain) null));

        service.run("u1", "s1", "问题", sse);

        String output = generateOutputData();
        assertTrue(output.contains("sources=1"), output);
        assertTrue(output.contains("prompt=chat.system@v3,chat.user@v2"), output);
    }
}
