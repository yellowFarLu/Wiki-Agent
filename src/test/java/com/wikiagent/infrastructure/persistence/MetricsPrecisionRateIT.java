package com.wikiagent.infrastructure.persistence;

import com.wikiagent.application.knowledge.MetricsAggregationJob;
import com.wikiagent.application.knowledge.MetricsAggregationJob.MetricsSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 带标签检索精确率（precisionRate）口径集成测试（H2 MODE=MySQL + Flyway，不 mock 中间件）。
 * <p>
 * 业界 precision 口径：相关检索项 / 全部已标注检索项；相关性标签来自 kb_feedback
 * chunk 级反馈（USEFUL=相关 / USELESS=不相关）。规则：
 * <ol>
 *   <li>窗口外（&gt;30 天）反馈不计入分子分母；</li>
 *   <li>会话级反馈（chunkId=null）标注整条答案而非检索项相关性，不计入精确率，但计入原始计数；</li>
 *   <li>分母为 0 → null（看板显示"暂无数据"，绝不显示 0%）；</li>
 *   <li>线上不输出召回率（需要 golden set 全量标注，仅离线评测可算）。</li>
 * </ol>
 * 单测试方法按阶段累加数据（聚合为只读累积口径，各阶段期望已包含此前数据）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class MetricsPrecisionRateIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-metrics-precision-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    @Autowired
    private KbFeedbackJpaDao feedbackDao;

    @Autowired
    private MetricsAggregationJob aggregationJob;

    @Test
    void 精确率只采纳窗口内chunk级反馈且分母为零时为null() {
        // 阶段 1：仅 31 天前的 chunk 级反馈 → 窗口外，分母为 0 → precision=null，原始计数为 0
        feedback("chunk-old", "USEFUL", 2, Instant.now().minus(31, ChronoUnit.DAYS));
        MetricsSnapshot s1 = aggregationJob.aggregate();
        assertThat(s1.usefulCount()).isZero();
        assertThat(s1.uselessCount()).isZero();
        assertThat(s1.precisionRate()).isNull();
        Map<String, Object> view1 = s1.toMap();
        assertThat(view1).containsKey("precisionRate");
        assertThat(view1.get("precisionRate")).isNull();
        assertThat(view1).doesNotContainKey("recallRate");

        // 阶段 2：追加窗口内会话级反馈（无 chunkId）→ 计入原始计数，但不进入精确率分母
        feedback(null, "USELESS", 3, Instant.now());
        MetricsSnapshot s2 = aggregationJob.aggregate();
        assertThat(s2.usefulCount()).isZero();
        assertThat(s2.uselessCount()).isEqualTo(3);
        assertThat(s2.precisionRate()).isNull();

        // 阶段 3：追加窗口内 chunk 级反馈 3 有用 / 1 无用 → precision = 3/4 = 0.75
        feedback("chunk-a", "USEFUL", 3, Instant.now());
        feedback("chunk-b", "USELESS", 1, Instant.now());
        MetricsSnapshot s3 = aggregationJob.aggregate();
        assertThat(s3.usefulCount()).isEqualTo(3);
        assertThat(s3.uselessCount()).isEqualTo(4);
        assertThat(s3.precisionRate()).isCloseTo(0.75, within(0.0001));
    }

    private void feedback(String chunkId, String type, int times, Instant at) {
        for (int i = 0; i < times; i++) {
            KbFeedbackEntity f = new KbFeedbackEntity();
            f.setUserId("u1");
            f.setSessionId("sess-" + (chunkId == null ? "session" : chunkId) + "-" + i);
            f.setConversationId("conv-" + (chunkId == null ? "session" : chunkId) + "-" + i);
            f.setChunkId(chunkId);
            f.setFeedbackType(type);
            f.setCreatedAt(at);
            feedbackDao.saveAndFlush(f);
        }
    }
}
