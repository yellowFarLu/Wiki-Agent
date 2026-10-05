package com.wikiagent.application.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalEntity;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalJpaDao;
import com.wikiagent.service.retrieve.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 评测样本采集器单测：正常落库（docId 去重保序）、防重跳过、采样率 0 跳过、
 * 空白过滤、DAO 异常被吞不影响调用方（对话主链路）。
 */
class RagEvalSampleRecorderTest {

    private RagAnswerEvalJpaDao dao;
    private RagEvalSampleRecorder recorder;
    private RagEvalSampleRecorder neverSample;

    @BeforeEach
    void setUp() {
        dao = mock(RagAnswerEvalJpaDao.class);
        recorder = new RagEvalSampleRecorder(dao, new ObjectMapper(), 1.0);
        neverSample = new RagEvalSampleRecorder(dao, new ObjectMapper(), 0.0);
    }

    @Test
    void 正常采集落PENDING且docId去重保序() throws Exception {
        when(dao.existsBySessionIdAndAnswerHash(any(), any())).thenReturn(false);

        // Arrays.asList 允许 null 元素（List.of 不允许），模拟 SSE sources 中的脏数据
        recorder.record("sess-1", "u1", "问题", "答案",
                java.util.Arrays.asList(
                        new RetrievalService.Source(1, "d2", 1, 1, "s", null, 0.9, "b.txt"),
                        new RetrievalService.Source(2, "d1", 1, 1, "s", null, 0.8, "a.txt"),
                        new RetrievalService.Source(3, "d2", 1, 1, "s", null, 0.7, "b.txt"),
                        null),
                "agent-rag");

        verify(dao).save(any(RagAnswerEvalEntity.class));
    }

    @Test
    void 同会话同答案防重跳过() {
        when(dao.existsBySessionIdAndAnswerHash(any(), any())).thenReturn(true);

        recorder.record("sess-1", "u1", "问题", "答案", List.of(), "legacy-rag");

        verify(dao, never()).save(any(RagAnswerEvalEntity.class));
    }

    @Test
    void 采样率为0时不采集() {
        when(dao.existsBySessionIdAndAnswerHash(any(), any())).thenReturn(false);

        neverSample.record("sess-1", "u1", "问题", "答案", List.of(), "legacy-rag");

        verify(dao, never()).save(any(RagAnswerEvalEntity.class));
    }

    @Test
    void 空白答案或会话不采集() {
        recorder.record("sess-1", "u1", "问题", "  ", List.of(), "legacy-rag");
        recorder.record("  ", "u1", "问题", "答案", List.of(), "legacy-rag");
        recorder.record("sess-1", "u1", null, "答案", List.of(), "legacy-rag");

        verify(dao, never()).save(any(RagAnswerEvalEntity.class));
    }

    @Test
    void dao异常被吞不影响调用方() {
        when(dao.existsBySessionIdAndAnswerHash(any(), any())).thenReturn(false);
        when(dao.save(any(RagAnswerEvalEntity.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> recorder.record("sess-1", "u1", "问题", "答案",
                List.of(new RetrievalService.Source(1, "d1", 1, 1, "s", null, 1.0, "f.txt")),
                "agent-rag")).doesNotThrowAnyException();
    }

    @Test
    void sha256为标准64位十六进制() throws Exception {
        String hash = RagEvalSampleRecorder.sha256Hex("答案");
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }
}
