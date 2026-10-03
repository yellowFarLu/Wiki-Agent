package com.wikiagent.interfaces.observability;

import com.wikiagent.infrastructure.persistence.ConflictResolutionJpaDao;
import com.wikiagent.infrastructure.persistence.ContentViolationLogJpaDao;
import com.wikiagent.infrastructure.persistence.GatewayAuditLogJpaDao;
import com.wikiagent.infrastructure.persistence.KbFeedbackJpaDao;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 9 看板观测指标：knowledgeDetails 旁挂 dedupConflictEvents——
 * 近 7 天去重/冲突打点按 eventType 分组计数（五个 key 固定输出，未命中为 0，
 * 其他 eventType 不计入）。
 */
class ObservabilityControllerTest {

    private final MetricEventJpaDao metricEventDao = mock(MetricEventJpaDao.class);
    private final KbFeedbackJpaDao feedbackDao = mock(KbFeedbackJpaDao.class);
    private final KnowledgeMetadataJpaDao metadataDao = mock(KnowledgeMetadataJpaDao.class);
    private final GatewayAuditLogJpaDao gatewayAuditDao = mock(GatewayAuditLogJpaDao.class);
    private final ContentViolationLogJpaDao violationDao = mock(ContentViolationLogJpaDao.class);
    private final ConflictResolutionJpaDao conflictDao = mock(ConflictResolutionJpaDao.class);

    private final ObservabilityController controller = new ObservabilityController(
            metricEventDao, feedbackDao, metadataDao, gatewayAuditDao, violationDao, conflictDao);

    private static MetricEventEntity event(String chunkId, String eventType) {
        MetricEventEntity e = new MetricEventEntity();
        e.setChunkId(chunkId);
        e.setEventType(eventType);
        return e;
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboard_去重与冲突打点按eventType分组计数() {
        when(metricEventDao.findSince(any())).thenReturn(List.of(
                event("c1", "DEDUP_EXACT_SKIPPED"),
                event("c2", "DEDUP_EXACT_SKIPPED"),
                event("c3", "DEDUP_NEAR_MERGED"),
                event("c4", "CONFLICT_GUARD_DETECTED"),
                event("c5", "CONFLICT_GUARD_REMOVED"),
                event("c6", "CONFLICT_GUARD_KEPT_BOTH"),
                event("c7", "RETRIEVED")));

        ResponseEntity<Map<String, Object>> resp = controller.dashboard();

        Map<String, Object> knowledgeDetails =
                (Map<String, Object>) resp.getBody().get("knowledgeDetails");
        Map<String, Long> counts =
                (Map<String, Long>) knowledgeDetails.get("dedupConflictEvents");
        assertThat(counts)
                .containsEntry("DEDUP_EXACT_SKIPPED", 2L)
                .containsEntry("DEDUP_NEAR_MERGED", 1L)
                .containsEntry("CONFLICT_GUARD_DETECTED", 1L)
                .containsEntry("CONFLICT_GUARD_REMOVED", 1L)
                .containsEntry("CONFLICT_GUARD_KEPT_BOTH", 1L)
                .doesNotContainKey("RETRIEVED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboard_无打点时五个eventType计数为零() {
        when(metricEventDao.findSince(any())).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> resp = controller.dashboard();

        Map<String, Object> knowledgeDetails =
                (Map<String, Object>) resp.getBody().get("knowledgeDetails");
        Map<String, Long> counts =
                (Map<String, Long>) knowledgeDetails.get("dedupConflictEvents");
        assertThat(counts).containsOnlyKeys(
                "DEDUP_EXACT_SKIPPED", "DEDUP_NEAR_MERGED",
                "CONFLICT_GUARD_DETECTED", "CONFLICT_GUARD_REMOVED", "CONFLICT_GUARD_KEPT_BOTH");
        assertThat(counts.values()).containsOnly(0L);
    }
}
