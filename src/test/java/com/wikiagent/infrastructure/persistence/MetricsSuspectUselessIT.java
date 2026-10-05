package com.wikiagent.infrastructure.persistence;

import com.wikiagent.application.knowledge.MetricsAggregationJob;
import com.wikiagent.application.knowledge.MetricsAggregationJob.MetricsSnapshot;
import com.wikiagent.application.knowledge.MetricsAggregationJob.SuspectUselessKnowledge;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 疑似无用知识聚合口径集成测试（H2 MODE=MySQL + Flyway，不 mock 中间件）。
 * 规则：知识被检索访问过（RETRIEVED ≥ 1），且 kb_feedback 中无用次数 / (有用+无用)
 * 严格大于 50% 才进入看板清单；等于 50%、未访问、无反馈均不入选；会话级反馈（chunkId=null）不参与。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class MetricsSuspectUselessIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-metrics-suspect-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    @Autowired
    private KnowledgeMetadataJpaDao metadataDao;

    @Autowired
    private KbFeedbackJpaDao feedbackDao;

    @Autowired
    private MetricEventJpaDao metricEventDao;

    @Autowired
    private MetricsAggregationJob aggregationJob;

    @Test
    void 访问过且无用占比严格超过一半才入选() {
        metadata("chunk-bad", "doc-1");    // 访问过，1 有用 / 2 无用（66.7%）→ 入选
        metadata("chunk-good", "doc-2");   // 访问过，2 有用 / 1 无用（33.3%）→ 不入选
        metadata("chunk-half", "doc-3");   // 访问过，1 有用 / 1 无用（=50%）→ 不入选
        metadata("chunk-idle", "doc-4");   // 未访问，0 有用 / 2 无用 → 不入选

        retrieved("chunk-bad");
        retrieved("chunk-good");
        retrieved("chunk-half");

        feedback("chunk-bad", "USEFUL", 1);
        feedback("chunk-bad", "USELESS", 2);
        feedback("chunk-good", "USEFUL", 2);
        feedback("chunk-good", "USELESS", 1);
        feedback("chunk-half", "USEFUL", 1);
        feedback("chunk-half", "USELESS", 1);
        feedback("chunk-idle", "USELESS", 2);
        // 会话级反馈（无 chunkId）不得进入 chunk 维度判定
        feedback(null, "USELESS", 3);

        MetricsSnapshot snapshot = aggregationJob.aggregate();

        assertThat(snapshot.suspectUselessKnowledge()).hasSize(1);
        SuspectUselessKnowledge item = snapshot.suspectUselessKnowledge().get(0);
        assertThat(item.chunkId()).isEqualTo("chunk-bad");
        assertThat(item.docId()).isEqualTo("doc-1");
        assertThat(item.retrievalCount()).isEqualTo(1);
        assertThat(item.usefulCount()).isEqualTo(1);
        assertThat(item.uselessCount()).isEqualTo(2);
        assertThat(item.totalFeedback()).isEqualTo(3);
        assertThat(item.uselessRatio()).isCloseTo(0.667, within(0.0001));

        // 有用/无用保留原始计数（近 30 天窗口，种子均为当前时刻），快照不再输出有用率
        assertThat(snapshot.usefulCount()).isEqualTo(4);
        assertThat(snapshot.uselessCount()).isEqualTo(9);
        // 带标签检索精确率：chunk 级 USEFUL 4 / (4+6)，会话级 3 条 USELESS 不进分母
        assertThat(snapshot.precisionRate()).isCloseTo(0.4, within(0.0001));
        Map<String, Object> view = snapshot.toMap();
        assertThat(view.get("suspectUselessKnowledgeCount")).isEqualTo(1);
        assertThat(view).containsKey("suspectUselessKnowledge");
        assertThat(view).doesNotContainKeys("usefulnessRate", "uselessnessRate", "recallRate");
    }

    private void metadata(String chunkId, String docId) {
        KnowledgeMetadataEntity e = new KnowledgeMetadataEntity();
        e.setChunkId(chunkId);
        e.setDocId(docId);
        e.setDomainTag("pms");
        e.setSubDomainTag("faq");
        e.setRequiredIdentity("business");
        e.setCreatedBy("tester");
        e.setSourceFilename(chunkId + ".pdf");
        metadataDao.saveAndFlush(e);
    }

    private void retrieved(String chunkId) {
        MetricEventEntity e = new MetricEventEntity();
        e.setChunkId(chunkId);
        e.setEventType("RETRIEVED");
        e.setUserId("u1");
        e.setSessionId("s-" + chunkId);
        metricEventDao.saveAndFlush(e);
    }

    private void feedback(String chunkId, String type, int times) {
        for (int i = 0; i < times; i++) {
            KbFeedbackEntity f = new KbFeedbackEntity();
            f.setUserId("u1");
            f.setSessionId("sess-" + (chunkId == null ? "session" : chunkId) + "-" + i);
            f.setConversationId("conv-" + (chunkId == null ? "session" : chunkId) + "-" + i);
            f.setChunkId(chunkId);
            f.setFeedbackType(type);
            feedbackDao.saveAndFlush(f);
        }
    }
}
