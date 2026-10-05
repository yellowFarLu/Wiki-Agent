package com.wikiagent.interfaces.observability;

import com.wikiagent.infrastructure.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * v5 §18 统一可观测平台 Controller。
 * <p>
 * GET /api/observability/dashboard — 统一看板（4 tab：执行路径 / 业务指标 / 知识明细 / 反馈审计）
 * GET /api/observability/trace/{sessionId} — 执行路径（Agent trace 链路）
 * GET /api/observability/metrics           — 业务指标概览
 * GET /api/observability/knowledge          — 知识明细列表
 * GET /api/observability/feedback           — 反馈审计日志
 * GET /api/observability/gateway            — 网关审计日志
 */
@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityController.class);

    private final MetricEventJpaDao metricEventDao;
    private final KbFeedbackJpaDao feedbackDao;
    private final KnowledgeMetadataJpaDao metadataDao;
    private final GatewayAuditLogJpaDao gatewayAuditDao;
    private final ContentViolationLogJpaDao violationDao;
    private final ConflictResolutionJpaDao conflictDao;

    public ObservabilityController(MetricEventJpaDao metricEventDao,
                                   KbFeedbackJpaDao feedbackDao,
                                   KnowledgeMetadataJpaDao metadataDao,
                                   GatewayAuditLogJpaDao gatewayAuditDao,
                                   ContentViolationLogJpaDao violationDao,
                                   ConflictResolutionJpaDao conflictDao) {
        this.metricEventDao = metricEventDao;
        this.feedbackDao = feedbackDao;
        this.metadataDao = metadataDao;
        this.gatewayAuditDao = gatewayAuditDao;
        this.violationDao = violationDao;
        this.conflictDao = conflictDao;
    }

    /**
     * 统一看板概览（4 tab 汇总数据）。
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard() {
        Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
        Map<String, Object> dashboard = new LinkedHashMap<>();

        // Tab 1: 执行路径（trace 表数据由 MysqlTraceRepository 写入，这里返回提示）
        dashboard.put("executionPath", Map.of(
                "message", "执行路径数据由 /api/observability/trace/{sessionId} 查询",
                "totalTraces", "see /api/trace"
        ));

        // Tab 2: 业务指标（反馈计数与检索计数同为近 7 天窗口，避免跨窗口并排误读）
        List<MetricEventEntity> events = metricEventDao.findSince(since);
        long retrievals = events.stream().filter(e -> "RETRIEVED".equals(e.getEventType())).count();
        long citations = events.stream().filter(e -> "CITED".equals(e.getEventType())).count();
        Map<String, Long> feedbackCounts = new HashMap<>();
        for (Object[] row : feedbackDao.countByCreatedAtAfterGroupByType(since)) {
            feedbackCounts.merge((String) row[0], ((Number) row[1]).longValue(), Long::sum);
        }
        long useful = feedbackCounts.getOrDefault("USEFUL", 0L);
        long useless = feedbackCounts.getOrDefault("USELESS", 0L);
        // Task 7：检索侧冲突守卫指标（近 7 天，按 eventType 计数）
        long conflictDetected = events.stream()
                .filter(e -> "CONFLICT_GUARD_DETECTED".equals(e.getEventType())).count();
        long conflictRemoved = events.stream()
                .filter(e -> "CONFLICT_GUARD_REMOVED".equals(e.getEventType())).count();
        long conflictKeptBoth = events.stream()
                .filter(e -> "CONFLICT_GUARD_KEPT_BOTH".equals(e.getEventType())).count();
        dashboard.put("businessMetrics", Map.of(
                "period", "7d",
                "retrievals", retrievals,
                "citations", citations,
                "useful", useful,
                "useless", useless,
                "conflictGuardDetected", conflictDetected,
                "conflictGuardRemoved", conflictRemoved,
                "conflictGuardKeptBoth", conflictKeptBoth
        ));

        // Tab 3: 知识明细
        List<KnowledgeMetadataEntity> knowledge = metadataDao.findAllActive();
        long staleCount = knowledge.stream()
                .filter(k -> k.getCreatedAt() != null
                        && k.getCreatedAt().isBefore(Instant.now().minus(180, ChronoUnit.DAYS)))
                .count();
        long pendingConflicts = conflictDao.findByStatus("DETECTED").size();
        // Task 9：近 7 天去重/冲突打点按 eventType 分组计数（events 即 findSince(7d) 窗口；
        // 五个 key 固定输出便于看板消费，其他 eventType 不计入）
        Map<String, Long> dedupConflictEvents = new LinkedHashMap<>();
        dedupConflictEvents.put("DEDUP_EXACT_SKIPPED", 0L);
        dedupConflictEvents.put("DEDUP_NEAR_MERGED", 0L);
        dedupConflictEvents.put("CONFLICT_GUARD_DETECTED", 0L);
        dedupConflictEvents.put("CONFLICT_GUARD_REMOVED", 0L);
        dedupConflictEvents.put("CONFLICT_GUARD_KEPT_BOTH", 0L);
        for (MetricEventEntity e : events) {
            dedupConflictEvents.computeIfPresent(e.getEventType(), (k, v) -> v + 1);
        }
        dashboard.put("knowledgeDetails", Map.of(
                "totalKnowledge", knowledge.size(),
                "staleKnowledge", staleCount,
                "pendingConflicts", pendingConflicts,
                "dedupConflictEvents", dedupConflictEvents
        ));

        // Tab 4: 反馈 + 网关审计
        List<GatewayAuditLogEntity> blocked = gatewayAuditDao.findByDetectionResult("BLOCK");
        List<ContentViolationLogEntity> violations = violationDao.findAll();
        dashboard.put("feedbackAudit", Map.of(
                "totalFeedback", feedbackDao.count(),
                "gatewayBlocked", blocked.size(),
                "contentViolations", violations.size(),
                "violationTypes", violations.stream()
                        .map(ContentViolationLogEntity::getViolationType)
                        .distinct().toList()
        ));

        return ResponseEntity.ok(dashboard);
    }

    /**
     * 反馈审计日志。
     */
    @GetMapping("/feedback")
    public ResponseEntity<List<KbFeedbackEntity>> feedbackAudit(
            @RequestParam(required = false) String sessionId) {
        if (sessionId != null) {
            return ResponseEntity.ok(feedbackDao.findBySessionId(sessionId));
        }
        return ResponseEntity.ok(feedbackDao.findAll());
    }

    /**
     * 网关审计日志。
     */
    @GetMapping("/gateway")
    public ResponseEntity<List<GatewayAuditLogEntity>> gatewayAudit(
            @RequestParam(required = false) String detectorName,
            @RequestParam(required = false) String direction) {
        if (detectorName != null && direction != null) {
            return ResponseEntity.ok(gatewayAuditDao.findByDetectorNameAndDirection(detectorName, direction));
        }
        return ResponseEntity.ok(gatewayAuditDao.findAll());
    }

    /**
     * 知识明细列表。
     */
    @GetMapping("/knowledge")
    public ResponseEntity<List<KnowledgeMetadataEntity>> knowledgeList() {
        return ResponseEntity.ok(metadataDao.findAllActive());
    }
}
