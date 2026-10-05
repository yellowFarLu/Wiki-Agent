package com.wikiagent.interfaces.metrics;

import com.wikiagent.application.knowledge.MetricsAggregationJob;
import com.wikiagent.application.knowledge.MetricsAggregationJob.MetricsSnapshot;
import com.wikiagent.application.knowledge.RagAnswerJudgeService;
import com.wikiagent.infrastructure.persistence.KbFeedbackEntity;
import com.wikiagent.infrastructure.persistence.KbFeedbackJpaDao;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataEntity;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalEntity;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalJpaDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * v4 §6.6.2.4 知识指标看板 API。
 * <p>
 * GET /api/metrics/aggregation    — 定时聚合快照（含 RAG 回答评测三率 answerEval）
 * GET /api/metrics/dashboard      — 知识看板概览（召回率、精确率、有用率、无用率、使用频率、过期知识）
 * GET /api/metrics/knowledge      — 知识元数据列表（含创建时间、创建者、创建身份）
 * GET /api/metrics/chunk/{chunkId} — 单条知识 chunk 的指标明细
 * GET /api/metrics/answer-eval/samples — 最近 RAG 评测样本明细
 * POST /api/metrics/answer-eval/judge  — 手动触发一批 LLM 评判
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private static final Logger log = LoggerFactory.getLogger(MetricsController.class);

    private final KnowledgeMetadataJpaDao metadataDao;
    private final MetricEventJpaDao metricEventDao;
    private final KbFeedbackJpaDao feedbackDao;
    private final RagAnswerEvalJpaDao answerEvalDao;
    private final MetricsAggregationJob aggregationJob;
    private final ObjectProvider<RagAnswerJudgeService> judgeServiceProvider;

    public MetricsController(KnowledgeMetadataJpaDao metadataDao,
                             MetricEventJpaDao metricEventDao,
                             KbFeedbackJpaDao feedbackDao,
                             RagAnswerEvalJpaDao answerEvalDao,
                             MetricsAggregationJob aggregationJob,
                             ObjectProvider<RagAnswerJudgeService> judgeServiceProvider) {
        this.metadataDao = metadataDao;
        this.metricEventDao = metricEventDao;
        this.feedbackDao = feedbackDao;
        this.answerEvalDao = answerEvalDao;
        this.aggregationJob = aggregationJob;
        this.judgeServiceProvider = judgeServiceProvider;
    }

    /**
     * v4 §6.6.2 定时聚合快照（含疑似过期知识明细）。
     * GET /api/metrics/aggregation?refresh=true 可手动触发一次聚合。
     */
    @GetMapping("/aggregation")
    public ResponseEntity<Map<String, Object>> aggregation(@RequestParam(defaultValue = "false") boolean refresh) {
        MetricsSnapshot snapshot = refresh ? aggregationJob.aggregate() : aggregationJob.latest();
        if (snapshot == null) {
            // Map.of 拒绝 null 值，改用 LinkedHashMap 承载空快照提示
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("aggregatedAt", null);
            empty.put("message", "尚未聚合，调用 refresh=true 立即聚合");
            return ResponseEntity.ok(empty);
        }
        return ResponseEntity.ok(snapshot.toMap());
    }

    /**
     * 知识看板概览。
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard() {
        List<KnowledgeMetadataEntity> allKnowledge = metadataDao.findAllActive();
        long totalKnowledge = allKnowledge.size();

        // 最近 30 天的指标事件
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<MetricEventEntity> recentEvents = metricEventDao.findSince(since);

        // 统计
        long totalRetrievals = recentEvents.stream()
                .filter(e -> "RETRIEVED".equals(e.getEventType())).count();
        long totalCitations = recentEvents.stream()
                .filter(e -> "CITED".equals(e.getEventType())).count();
        long usefulCount = feedbackDao.findAll().stream()
                .filter(f -> "USEFUL".equals(f.getFeedbackType())).count();
        long uselessCount = feedbackDao.findAll().stream()
                .filter(f -> "USELESS".equals(f.getFeedbackType())).count();

        double usefulnessRate = (usefulCount + uselessCount) > 0
                ? (double) usefulCount / (usefulCount + uselessCount) : 0.0;

        // 简化的召回率/精确率计算（实际需要更多数据）
        double recallRate = totalRetrievals > 0 ? (double) totalCitations / totalRetrievals : 0.0;
        double precisionRate = totalRetrievals > 0 ? Math.min(1.0, (double) usefulCount / Math.max(1, totalRetrievals)) : 0.0;

        // 过期知识检测（stale_score < 0.3）
        Instant staleCutoff = Instant.now().minus(180, ChronoUnit.DAYS);
        long staleCount = allKnowledge.stream()
                .filter(k -> k.getCreatedAt() != null && k.getCreatedAt().isBefore(staleCutoff))
                .count();

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("totalKnowledge", totalKnowledge);
        dashboard.put("totalRetrievals", totalRetrievals);
        dashboard.put("totalCitations", totalCitations);
        dashboard.put("usefulCount", usefulCount);
        dashboard.put("uselessCount", uselessCount);
        dashboard.put("usefulnessRate", usefulnessRate);
        dashboard.put("uselessnessRate", 1.0 - usefulnessRate);
        dashboard.put("recallRate", recallRate);
        dashboard.put("precisionRate", precisionRate);
        dashboard.put("staleKnowledgeCount", staleCount);
        dashboard.put("staleThreshold", 0.3);
        dashboard.put("scanPeriod", "30d");

        return ResponseEntity.ok(dashboard);
    }

    /**
     * 知识元数据列表（含创建时间、创建者、创建身份）。
     */
    @GetMapping("/knowledge")
    public ResponseEntity<List<KnowledgeMetadataEntity>> listKnowledge(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String subDomain,
            @RequestParam(required = false) String createdBy) {
        List<KnowledgeMetadataEntity> result;
        if (domain != null && subDomain != null) {
            result = metadataDao.findByDomainTagAndSubDomainTag(domain, subDomain);
        } else if (createdBy != null) {
            result = metadataDao.findByCreatedBy(createdBy);
        } else {
            result = metadataDao.findAllActive();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 单条知识 chunk 的指标明细。
     */
    @GetMapping("/chunk/{chunkId}")
    public ResponseEntity<Map<String, Object>> chunkMetrics(@PathVariable String chunkId) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("chunkId", chunkId);

        // 元数据
        metadataDao.findByChunkId(chunkId).ifPresentOrElse(
                meta -> {
                    metrics.put("metadata", meta);
                    metrics.put("domain", meta.getDomainTag());
                    metrics.put("subDomain", meta.getSubDomainTag());
                    metrics.put("createdAt", meta.getCreatedAt());
                    metrics.put("createdBy", meta.getCreatedBy());
                    metrics.put("createdIdentity", meta.getCreatedIdentity());
                },
                () -> metrics.put("metadata", null)
        );

        // 指标事件
        List<MetricEventEntity> events = metricEventDao.findByChunkId(chunkId);
        long retrievals = events.stream().filter(e -> "RETRIEVED".equals(e.getEventType())).count();
        long citations = events.stream().filter(e -> "CITED".equals(e.getEventType())).count();
        long useful = events.stream().filter(e -> "FEEDBACK_USEFUL".equals(e.getEventType())).count();
        long useless = events.stream().filter(e -> "FEEDBACK_USELESS".equals(e.getEventType())).count();

        metrics.put("retrievals", retrievals);
        metrics.put("citations", citations);
        metrics.put("usefulCount", useful);
        metrics.put("uselessCount", useless);
        metrics.put("usefulnessRate", (useful + useless) > 0 ? (double) useful / (useful + useless) : 0.0);
        metrics.put("lastUsedAt", events.isEmpty() ? null : events.get(events.size() - 1).getCreatedAt());

        return ResponseEntity.ok(metrics);
    }

    /**
     * 最近 RAG 回答评测样本明细（方案 docs/rag-accuracy-eval.md）。
     * question/answer 截 200 字展示；faithfulness/relevance 为 null 表示无法评判。
     */
    @GetMapping("/answer-eval/samples")
    public ResponseEntity<List<Map<String, Object>>> answerEvalSamples(
            @RequestParam(defaultValue = "20") int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        List<RagAnswerEvalEntity> rows = answerEvalDao.findAll(
                PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        List<Map<String, Object>> result = new ArrayList<>();
        for (RagAnswerEvalEntity e : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.getId());
            item.put("sessionId", e.getSessionId());
            item.put("question", truncate(e.getQuestion(), 200));
            item.put("answer", truncate(e.getAnswer(), 200));
            item.put("channel", e.getChannel());
            item.put("judgeModel", e.getJudgeModel());
            item.put("faithfulness", e.getFaithfulness());
            item.put("relevance", e.getRelevance());
            item.put("verdictReason", e.getVerdictReason());
            item.put("status", e.getStatus());
            item.put("error", e.getError());
            item.put("createdAt", e.getCreatedAt());
            item.put("judgedAt", e.getJudgedAt());
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 手动触发一批 LLM 评判（评测服务未启用时返回 409 + 说明，不假装成功）。
     */
    @PostMapping("/answer-eval/judge")
    public ResponseEntity<Map<String, Object>> judgeAnswerEval() {
        RagAnswerJudgeService judgeService = judgeServiceProvider.getIfAvailable();
        if (judgeService == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("enabled", false);
            body.put("message", "RAG 回答评测未启用（wikiagent.answer-eval.enabled=false），看板无评测数据");
            return ResponseEntity.status(409).body(body);
        }
        RagAnswerJudgeService.JudgeBatchResult r = judgeService.judgePendingBatch();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", true);
        body.put("judged", r.judged());
        body.put("failed", r.failed());
        return ResponseEntity.ok(body);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max);
    }
}
