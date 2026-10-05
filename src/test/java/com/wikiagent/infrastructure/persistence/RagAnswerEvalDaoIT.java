package com.wikiagent.infrastructure.persistence;

import com.wikiagent.application.knowledge.MetricsAggregationJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V18 rag_answer_eval 轻量集成测试（H2 MODE=MySQL + Flyway）：
 * ① 迁移可执行、唯一约束 (session_id, answer_hash) 防重；
 * ② count 派生查询 null 语义（无法评判行不计分母）；
 * ③ 聚合快照 answerEval 三率诚实分母（分母 0 → null，不捏造 0%）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class RagAnswerEvalDaoIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-rag-eval-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    @Autowired
    private RagAnswerEvalJpaDao dao;

    @Autowired
    private MetricsAggregationJob aggregationJob;

    @AfterEach
    void clean() {
        // 三个测试方法共享同一个 H2 文件库，全局 count/聚合必须方法间隔离
        dao.deleteAll();
    }

    private static RagAnswerEvalEntity row(String sessionId, String answer, Integer f, Integer r, String status) {
        RagAnswerEvalEntity e = new RagAnswerEvalEntity();
        e.setSessionId(sessionId);
        e.setUserId("u1");
        e.setQuestion("问题-" + sessionId);
        e.setAnswer(answer);
        e.setAnswerHash(RagAnswerEvalDaoIT.hashOf(answer));
        e.setChannel("agent-rag");
        e.setFaithfulness(f);
        e.setRelevance(r);
        e.setStatus(status);
        return e;
    }

    private static String hashOf(String answer) {
        // 与采集器同口径的轻量 hash（测试内固定长度即可）
        return String.format("%064x", answer.hashCode());
    }

    @Test
    void V18迁移可执行且唯一约束防重() {
        dao.saveAndFlush(row("sess-uk-1", "答案A", 1, 1, RagAnswerEvalEntity.STATUS_JUDGED));

        assertThat(dao.existsBySessionIdAndAnswerHash("sess-uk-1", hashOf("答案A"))).isTrue();
        assertThat(dao.existsBySessionIdAndAnswerHash("sess-uk-1", hashOf("其他答案"))).isFalse();
        assertThat(dao.existsBySessionIdAndAnswerHash("sess-uk-2", hashOf("答案A"))).isFalse();

        // 同会话同答案：唯一约束拒绝
        assertThatThrownBy(() -> dao.saveAndFlush(row("sess-uk-1", "答案A", null, null, RagAnswerEvalEntity.STATUS_PENDING)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void count派生查询null不计分母() {
        dao.save(row("s-a", "a", 1, 1, RagAnswerEvalEntity.STATUS_JUDGED));
        dao.save(row("s-b", "b", 0, 1, RagAnswerEvalEntity.STATUS_JUDGED));
        dao.save(row("s-c", "c", null, 1, RagAnswerEvalEntity.STATUS_JUDGED));
        dao.save(row("s-d", "d", null, null, RagAnswerEvalEntity.STATUS_PENDING));

        assertThat(dao.countByStatus(RagAnswerEvalEntity.STATUS_JUDGED)).isEqualTo(3);
        assertThat(dao.countByStatus(RagAnswerEvalEntity.STATUS_PENDING)).isEqualTo(1);
        assertThat(dao.countByFaithfulnessIsNotNull()).isEqualTo(2);
        assertThat(dao.countByRelevanceIsNotNull()).isEqualTo(3);
        assertThat(dao.countByFaithfulnessIsNotNullAndRelevanceIsNotNull()).isEqualTo(2);
        assertThat(dao.countByFaithfulness(1)).isEqualTo(1);
        assertThat(dao.countByRelevance(1)).isEqualTo(3);
        assertThat(dao.countByFaithfulnessAndRelevance(1, 1)).isEqualTo(1);
    }

    @Test
    void 聚合快照answerEval三率诚实分母() {
        dao.save(row("s-e", "e", 1, 1, RagAnswerEvalEntity.STATUS_JUDGED));
        dao.save(row("s-f", "f", 0, 1, RagAnswerEvalEntity.STATUS_JUDGED));
        dao.save(row("s-g", "g", null, 1, RagAnswerEvalEntity.STATUS_JUDGED));
        dao.save(row("s-h", "h", null, null, RagAnswerEvalEntity.STATUS_PENDING));

        MetricsAggregationJob.MetricsSnapshot snapshot = aggregationJob.aggregate();
        MetricsAggregationJob.AnswerEvalSummary eval = snapshot.answerEval();

        assertThat(eval).isNotNull();
        assertThat(eval.judgedCount()).isEqualTo(3);
        assertThat(eval.pendingCount()).isEqualTo(1);
        assertThat(eval.failedCount()).isZero();
        // 忠实度：f≠null 2 行（1 通过 / 0 不通过）→ 0.5
        assertThat(eval.faithfulnessRate()).isEqualTo(0.5);
        assertThat(eval.faithfulnessJudged()).isEqualTo(2);
        // 相关性：r≠null 3 行全通过 → 1.0
        assertThat(eval.relevanceRate()).isEqualTo(1.0);
        assertThat(eval.relevanceJudged()).isEqualTo(3);
        // 准确率：两维均可判 2 行，均通过 1 行 → 0.5
        assertThat(eval.accuracyRate()).isEqualTo(0.5);
        assertThat(eval.accuracyJudged()).isEqualTo(2);
    }
}
