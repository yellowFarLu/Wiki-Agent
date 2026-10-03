package com.wikiagent.service.agent;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.application.prompt.PromptTemplateService;
import com.wikiagent.application.ragcache.AnswerCacheService;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.retrieve.RetrievalSecurityContext;
import com.wikiagent.infrastructure.llm.ChatModelProviderChain;
import com.wikiagent.infrastructure.trace.RagTraceRecorder;
import com.wikiagent.service.chat.ChatStreamer;
import com.wikiagent.service.chat.FallbackAnswerService;
import com.wikiagent.service.chat.SseSender;
import com.wikiagent.service.retrieve.QueryRewriteService;
import com.wikiagent.service.retrieve.RetrievalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 缺陷16：答案缓存读路径接 AgentRagService.run()——
 * 开启后同问第二次直接回放缓存，零模型调用、零流式生成；不同身份不串缓存。
 */
class AgentRagAnswerCacheTest {

    private ChatModel chatModel;
    private RetrievalService retrieval;
    private ChatStreamer streamer;
    private SseEmitter emitter;
    private SseSender sse;
    private AgentRagService service;

    /** 内存 Redis 桩（value + set + expire + collection delete）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static StringRedisTemplate fakeRedis(Map<String, String> store, Map<String, Set<String>> sets) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> vops = mock(ValueOperations.class);
        SetOperations<String, String> sops = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(vops);
        when(vops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(vops).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redis.opsForSet()).thenReturn(sops);
        when(sops.add(anyString(), any(String[].class))).thenAnswer(inv -> {
            Object[] all = inv.getArguments();
            String[] members = java.util.Arrays.copyOfRange(all, 1, all.length, String[].class);
            sets.computeIfAbsent(all[0].toString(), k -> new HashSet<>())
                    .addAll(java.util.Arrays.asList(members));
            return 1L;
        });
        when(sops.members(anyString())).thenAnswer(inv ->
                sets.getOrDefault(inv.getArgument(0), Set.of()));
        when(redis.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redis.delete(any(Collection.class))).thenAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            long n = 0;
            for (String k : keys) {
                if (store.remove(k) != null) n++;
                sets.remove(k);
            }
            return n;
        });
        return redis;
    }

    private static <T> ObjectProvider<T> providerOf(T bean) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(bean);
        return op;
    }

    private static ChatResponse resp(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        retrieval = mock(RetrievalService.class);
        streamer = mock(ChatStreamer.class);
        emitter = mock(SseEmitter.class);
        sse = new SseSender(emitter);

        when(retrieval.newAccumulator()).thenReturn(new RetrievalService.Accumulator());
        when(retrieval.assemble(any(), any())).thenReturn(new RetrievalService.RetrievalResult(
                List.of(new RetrievalService.Source(1, "d1", 1, 1, "片段", null, 0.9, "f.md")),
                "证据"));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(resp("{\"mode\":\"search\",\"queries\":[\"q1\"]}"))
                .thenReturn(resp("{\"sufficient\":true,\"refinedQuery\":\"\"}"));
        // 模拟流式生成：透传一段答案后正常结束（触发缓存回写装饰器）
        doAnswer(inv -> {
            SseSender out = inv.getArgument(1);
            out.send("delta", Map.of("text", "缓存答案"));
            out.send("done", Map.of());
            out.complete();
            return null;
        }).when(streamer).stream(any(Prompt.class), any(SseSender.class),
                any(), anyString(), anyString());

        Map<String, String> store = new HashMap<>();
        AnswerCacheService cache = new AnswerCacheService(
                providerOf(fakeRedis(store, new HashMap<>())), true, 600);

        WikiAgentProperties props = new WikiAgentProperties(null, null, null,
                new WikiAgentProperties.Agent(true, 1, 3));
        service = new AgentRagService(props, chatModel, retrieval, mock(QueryRewriteService.class),
                streamer, mock(FallbackAnswerService.class), mock(RagTraceRecorder.class),
                providerOf((ModelCallRecorder) null), providerOf((PromptTemplateService) null),
                "qwen-plus", providerOf((ChatModelProviderChain) null), providerOf(cache));
    }

    @AfterEach
    void tearDown() {
        RetrievalSecurityContext.clear();
    }

    @Test
    void 同问第二次命中缓存零模型调用且不流式生成() {
        // 首次：路由+评估模型各一次，流式生成一次并回写缓存
        service.run("u1", "s1", "同样的问题", sse);
        verify(chatModel, times(2)).call(any(Prompt.class));
        verify(streamer, times(1)).stream(any(Prompt.class), any(SseSender.class),
                any(), anyString(), anyString());

        Mockito.clearInvocations(chatModel, streamer, emitter);
        // 第二次：直接回放缓存
        service.run("u1", "s1", "同样的问题", new SseSender(emitter));

        verifyNoInteractions(chatModel);
        verify(streamer, times(0)).stream(any(Prompt.class), any(SseSender.class),
                any(), anyString(), anyString());
        verify(emitter, times(1)).complete();
    }

    @Test
    void 不同业务身份不串缓存() {
        RetrievalSecurityContext.setIdentity("user-a");
        service.run("u1", "s1", "同样的问题", sse);
        Mockito.clearInvocations(chatModel, streamer);

        RetrievalSecurityContext.setIdentity("user-b");
        service.run("u1", "s1", "同样的问题", sse);
        // 身份不同 → key 不同 → 未命中，照常路由评估与流式生成
        verify(chatModel, times(2)).call(any(Prompt.class));
        verify(streamer, times(1)).stream(any(Prompt.class), any(SseSender.class),
                any(), anyString(), anyString());
    }

    @Test
    void 身份头优先于userId作为缓存身份段() {
        RetrievalSecurityContext.setIdentity("user-a");
        assertEquals("user-a", invokeCacheIdentity("u1"));
        RetrievalSecurityContext.clear();
        assertEquals("u1", invokeCacheIdentity("u1"));
        assertEquals("anon", invokeCacheIdentity(null));
    }

    private static String invokeCacheIdentity(String userId) {
        try {
            var m = AgentRagService.class.getDeclaredMethod("cacheIdentity", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, userId);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
