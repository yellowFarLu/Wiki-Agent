package com.wikiagent.service.retrieve;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.service.retrieve.ConflictCandidateDetector.RepChunkView;
import com.wikiagent.service.retrieve.ConflictLlmJudge.ConflictVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 6 ConflictGuard 精判（qwen-flash 结构化输出，fail-open）。
 * 覆盖 Review Focus #2：非法 JSON / 候选集外 chunkId / 模型异常 → 一律 empty + warn，
 * 绝不阻断问答主链路。
 */
@ExtendWith(MockitoExtension.class)
class ConflictLlmJudgeTest {

    private static final RepChunkView CHUNK_A =
            new RepChunkView("chunk-a", "doc-1", "红富士苹果售价5元每箱", 0.95);
    private static final RepChunkView CHUNK_B =
            new RepChunkView("chunk-b", "doc-2", "红富士苹果售价3元每箱", 0.93);
    private static final List<RepChunkView> PAIR = List.of(CHUNK_A, CHUNK_B);
    private static final String QUERY = "苹果多少钱";

    @Mock
    ChatModel chatModel;
    @Mock
    ModelCallRecorder recorder;
    @Mock
    ObjectProvider<ModelCallRecorder> recorderProvider;

    ConflictLlmJudge judge;

    @BeforeEach
    void setUp() {
        when(recorderProvider.getIfAvailable()).thenReturn(recorder);
        judge = new ConflictLlmJudge(chatModel, recorderProvider, "qwen-flash");
    }

    private void mockModelReturns(String text) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    @Test
    void 合法JSON且conflict为true时解析为ConflictVerdict() {
        mockModelReturns("{\"conflict\":true,\"entity\":\"苹果\",\"attribute\":\"价格\",\"valueA\":\"5元\",\"valueB\":\"3元\"}");

        Optional<ConflictVerdict> v = judge.judge(QUERY, PAIR);

        assertThat(v).isPresent();
        assertThat(v.get().chunkIdA()).isEqualTo("chunk-a");
        assertThat(v.get().chunkIdB()).isEqualTo("chunk-b");
        assertThat(v.get().entity()).isEqualTo("苹果");
        assertThat(v.get().attribute()).isEqualTo("价格");
        assertThat(v.get().valueA()).isEqualTo("5元");
        assertThat(v.get().valueB()).isEqualTo("3元");
    }

    @Test
    void conflict为false时返回empty() {
        mockModelReturns("{\"conflict\":false}");

        Optional<ConflictVerdict> v = judge.judge(QUERY, PAIR);

        assertThat(v).isEmpty();
    }

    @Test
    void 返回非法JSON时按无冲突放行() {
        mockModelReturns("这不是 JSON，是模型幻觉输出");

        Optional<ConflictVerdict> v = judge.judge(QUERY, PAIR);

        assertThat(v).isEmpty();
    }

    @Test
    void 返回候选集外chunkId时丢弃() {
        mockModelReturns("{\"conflict\":true,\"entity\":\"苹果\",\"attribute\":\"价格\",\"valueA\":\"5元\",\"valueB\":\"3元\",\"chunkIdA\":\"chunk-a\",\"chunkIdB\":\"chunk-x\"}");

        Optional<ConflictVerdict> v = judge.judge(QUERY, PAIR);

        assertThat(v).isEmpty();
    }

    @Test
    void 返回chunkId顺序调换时按输入对规整且value随id交换() {
        mockModelReturns("{\"conflict\":true,\"entity\":\"苹果\",\"attribute\":\"价格\",\"valueA\":\"3元\",\"valueB\":\"5元\",\"chunkIdA\":\"chunk-b\",\"chunkIdB\":\"chunk-a\"}");

        Optional<ConflictVerdict> v = judge.judge(QUERY, PAIR);

        assertThat(v).isPresent();
        // 顺序始终规整为输入对 (A,B)，且 value 随 chunkId 一并交换，保证 id 与值对应
        assertThat(v.get().chunkIdA()).isEqualTo("chunk-a");
        assertThat(v.get().chunkIdB()).isEqualTo("chunk-b");
        assertThat(v.get().valueA()).isEqualTo("5元");
        assertThat(v.get().valueB()).isEqualTo("3元");
    }

    @Test
    void 模型抛异常时按无冲突放行不阻断() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("qwen-flash timeout"));

        Optional<ConflictVerdict> v = judge.judge(QUERY, PAIR);

        assertThat(v).isEmpty();
    }

    @Test
    void 模型返回空响应时按无冲突放行() {
        when(chatModel.call(any(Prompt.class))).thenReturn(null);

        Optional<ConflictVerdict> v = judge.judge(QUERY, PAIR);

        assertThat(v).isEmpty();
    }

    @Test
    void 空query或pair不合法时不调模型直接返回empty() {
        assertThat(judge.judge(null, PAIR)).isEmpty();
        assertThat(judge.judge("", PAIR)).isEmpty();
        assertThat(judge.judge(QUERY, null)).isEmpty();
        assertThat(judge.judge(QUERY, List.of())).isEmpty();
        assertThat(judge.judge(QUERY, List.of(CHUNK_A))).isEmpty();
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void 调用打点是CONFLICT_JUDGE并带模型名() {
        mockModelReturns("{\"conflict\":false}");

        judge.judge(QUERY, PAIR);

        verify(recorder, atLeastOnce()).record(
                eq(ModelCallLogPurpose.CONFLICT_JUDGE),
                eq("dashscope"),
                eq("qwen-flash"),
                any(), any(), any(), eq(true),
                isNull(), isNull(), isNull());
    }

    @Test
    void 解析失败时打点ok为false() {
        mockModelReturns("这不是 JSON，是模型幻觉输出");

        judge.judge(QUERY, PAIR);

        verify(recorder, atLeastOnce()).record(
                eq(ModelCallLogPurpose.CONFLICT_JUDGE),
                eq("dashscope"),
                eq("qwen-flash"),
                any(), any(), any(), eq(false),
                isNull(), isNull(), isNull());
    }
}
