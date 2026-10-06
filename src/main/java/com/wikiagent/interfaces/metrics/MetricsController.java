package com.wikiagent.interfaces.metrics;

import com.wikiagent.application.knowledge.EvalDatasetCandidateExporter;
import com.wikiagent.application.knowledge.MetricsAggregationJob;
import com.wikiagent.application.knowledge.MetricsAggregationJob.MetricsSnapshot;
import com.wikiagent.application.knowledge.RagAnswerJudgeService;
import com.wikiagent.application.knowledge.RagasEvalExecutor;
import com.wikiagent.application.knowledge.StaleScore;
import com.wikiagent.infrastructure.persistence.KbFeedbackJpaDao;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataEntity;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalEntity;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalJpaDao;
import com.wikiagent.infrastructure.persistence.RagasEvalRunEntity;
import com.wikiagent.infrastructure.persistence.RagasEvalRunJpaDao;
import com.wikiagent.infrastructure.persistence.RagasEvalSampleEntity;
import com.wikiagent.infrastructure.persistence.RagasEvalSampleJpaDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * v4 §6.6.2.4 知识指标看板 API。
 * <p>
 * GET /api/metrics/aggregation    — 定时聚合快照（含疑似无用知识、RAG 回答评测三率 answerEval）
 * GET /api/metrics/dashboard      — 知识看板概览（带标签检索精确率、使用频率、过期知识、有用/无用原始计数；
 *                                   召回率线上不输出，仅离线 golden-set 评测可算）
 * GET /api/metrics/knowledge      — 知识元数据列表（含创建时间、创建者、创建身份）
 * GET /api/metrics/chunk/{chunkId} — 单条知识 chunk 的指标明细
 * GET /api/metrics/answer-eval/samples — 最近 RAG 评测样本明细
 * POST /api/metrics/answer-eval/judge  — 手动触发一批 LLM 评判
 * POST /api/metrics/ragas/run          — 触发一次 RAGAS 离线评测（异步，202/409）
 * GET /api/metrics/ragas/runs          — RAGAS 历史执行列表（最新在前）
 * GET /api/metrics/ragas/runs/{runId}  — 单次执行详情 + 评测用例集
 * POST /api/metrics/ragas/dataset/export-production — 阶段二导出生产日志评测候选（NDJSON，pending）
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private static final Logger log = LoggerFactory.getLogger(MetricsController.class);

    private final KnowledgeMetadataJpaDao metadataDao;
    private final MetricEventJpaDao metricEventDao;
    private final KbFeedbackJpaDao feedbackDao;
    private final RagAnswerEvalJpaDao answerEvalDao;
    private final RagasEvalRunJpaDao ragasRunDao;
    private final RagasEvalSampleJpaDao ragasSampleDao;
    private final MetricsAggregationJob aggregationJob;
    private final ObjectProvider<RagAnswerJudgeService> judgeServiceProvider;
    private final ObjectProvider<RagasEvalExecutor> ragasExecutorProvider;
    private final ObjectProvider<EvalDatasetCandidateExporter> datasetExporterProvider;
    private final long tauTimeDays;
    private final double staleThreshold;

    public MetricsController(KnowledgeMetadataJpaDao metadataDao,
                             MetricEventJpaDao metricEventDao,
                             KbFeedbackJpaDao feedbackDao,
                             RagAnswerEvalJpaDao answerEvalDao,
                             RagasEvalRunJpaDao ragasRunDao,
                             RagasEvalSampleJpaDao ragasSampleDao,
                             MetricsAggregationJob aggregationJob,
                             ObjectProvider<RagAnswerJudgeService> judgeServiceProvider,
                             ObjectProvider<RagasEvalExecutor> ragasExecutorProvider,
                             ObjectProvider<EvalDatasetCandidateExporter> datasetExporterProvider,
                             @Value("${wikiagent.metrics.tau-time-days:180}") long tauTimeDays,
                             @Value("${wikiagent.metrics.stale-threshold:0.3}") double staleThreshold) {
        this.metadataDao = metadataDao;
        this.metricEventDao = metricEventDao;
        this.feedbackDao = feedbackDao;
        this.answerEvalDao = answerEvalDao;
        this.ragasRunDao = ragasRunDao;
        this.ragasSampleDao = ragasSampleDao;
        this.aggregationJob = aggregationJob;
        this.judgeServiceProvider = judgeServiceProvider;
        this.ragasExecutorProvider = ragasExecutorProvider;
        this.datasetExporterProvider = datasetExporterProvider;
        this.tauTimeDays = tauTimeDays;
        this.staleThreshold = staleThreshold;
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
     * 知识看板概览（口径与 MetricsAggregationJob 一致，业界标准对齐）：
     * <ul>
     *   <li>precisionRate＝带标签检索精确率：近 30 天 chunk 级 USEFUL/(USEFUL+USELESS)；
     *       无反馈标签的检索不进分母；分母为 0 → null → 前端显示"-"，不显示 0%。</li>
     *   <li>召回率不输出：业界召回需要全量相关性标注（golden set），线上无法诚实计算
     *       （本管道"召回即引用"，CITED/RETRIEVED 恒等于 1.0，零信息），仅离线评测可算。</li>
     *   <li>有用/无用原始计数与检索计数同为近 30 天窗口；过期知识与聚合作业同一 staleScore 公式。</li>
     * </ul>
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard() {
        List<KnowledgeMetadataEntity> allKnowledge = metadataDao.findAllActive();
        long totalKnowledge = allKnowledge.size();

        // 最近 30 天的指标事件与反馈（同一窗口，避免跨窗口并排误读）
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<MetricEventEntity> recentEvents = metricEventDao.findSince(since);

        long totalRetrievals = recentEvents.stream()
                .filter(e -> "RETRIEVED".equals(e.getEventType())).count();
        long totalCitations = recentEvents.stream()
                .filter(e -> "CITED".equals(e.getEventType())).count();
        Map<String, Long> feedbackCounts = toCountMap(feedbackDao.countByCreatedAtAfterGroupByType(since));
        long usefulCount = feedbackCounts.getOrDefault("USEFUL", 0L);
        long uselessCount = feedbackCounts.getOrDefault("USELESS", 0L);

        // 带标签检索精确率：会话级反馈（chunkId=null）标注整条答案而非检索项相关性，不计入
        Map<String, Long> labeled = toCountMap(feedbackDao.countChunkLevelByCreatedAtAfterGroupByType(since));
        long labeledUseful = labeled.getOrDefault("USEFUL", 0L);
        long labeledTotal = labeledUseful + labeled.getOrDefault("USELESS", 0L);
        Double precisionRate = labeledTotal > 0
                ? Math.round((double) labeledUseful / labeledTotal * 1000.0) / 1000.0 : null;

        // 疑似过期知识：与 MetricsAggregationJob 同一 staleScore 公式与阈值
        Instant now = Instant.now();
        Map<String, Long> retrievalCountByChunk = recentEvents.stream()
                .filter(e -> "RETRIEVED".equals(e.getEventType()) && e.getChunkId() != null)
                .collect(Collectors.groupingBy(MetricEventEntity::getChunkId, Collectors.counting()));
        long staleCount = allKnowledge.stream()
                .filter(k -> {
                    long ageDays = k.getCreatedAt() == null
                            ? Long.MAX_VALUE
                            : java.time.Duration.between(k.getCreatedAt(), now).toDays();
                    return StaleScore.score(ageDays,
                            retrievalCountByChunk.getOrDefault(k.getChunkId(), 0L), tauTimeDays) < staleThreshold;
                })
                .count();

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("totalKnowledge", totalKnowledge);
        dashboard.put("totalRetrievals", totalRetrievals);
        dashboard.put("totalCitations", totalCitations);
        dashboard.put("usefulCount", usefulCount);
        dashboard.put("uselessCount", uselessCount);
        dashboard.put("precisionRate", precisionRate);
        dashboard.put("staleKnowledgeCount", staleCount);
        dashboard.put("staleThreshold", staleThreshold);
        dashboard.put("scanPeriod", "30d");

        return ResponseEntity.ok(dashboard);
    }

    private static Map<String, Long> toCountMap(List<Object[]> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.merge((String) row[0], ((Number) row[1]).longValue(), Long::sum);
        }
        return counts;
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

        // 指标事件（检索/引用）
        List<MetricEventEntity> events = metricEventDao.findByChunkId(chunkId);
        long retrievals = events.stream().filter(e -> "RETRIEVED".equals(e.getEventType())).count();
        long citations = events.stream().filter(e -> "CITED".equals(e.getEventType())).count();
        // 有用/无用口径以 kb_feedback 表为准（项目规约），不从 metric_event 反馈事件计数
        Map<String, Long> feedbackCounts = toCountMap(feedbackDao.countByChunkIdGroupByType(chunkId));
        long useful = feedbackCounts.getOrDefault("USEFUL", 0L);
        long useless = feedbackCounts.getOrDefault("USELESS", 0L);

        metrics.put("retrievals", retrievals);
        metrics.put("citations", citations);
        metrics.put("usefulCount", useful);
        metrics.put("uselessCount", useless);
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

    // ================= RAGAS 离线评测 =================

    /**
     * 触发一次 RAGAS 评测：执行器未启用 → 409；预检/单飞失败 → 409；
     * 已受理 → 202 + runId，前端轮询 detail 直到终态。
     */
    @PostMapping("/ragas/run")
    public ResponseEntity<Map<String, Object>> runRagas() {
        RagasEvalExecutor executor = ragasExecutorProvider.getIfAvailable();
        if (executor == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runId", null);
            body.put("message", "RAGAS 评测未启用（wikiagent.ragas.enabled=false）");
            return ResponseEntity.status(409).body(body);
        }
        RagasEvalExecutor.StartOutcome outcome = executor.start();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", outcome.runId());
        body.put("message", outcome.message());
        return ResponseEntity.status(outcome.code()).body(body);
    }

    /** RAGAS 历史执行列表（最新在前，最多 100 条）。 */
    @GetMapping("/ragas/runs")
    public ResponseEntity<List<RagasEvalRunEntity>> ragasRuns() {
        return ResponseEntity.ok(ragasRunDao.findTop100ByOrderByCreatedAtDesc());
    }

    /** 单次执行详情：run + 完整评测用例集；runId 不存在 → 404。 */
    @GetMapping("/ragas/runs/{runId}")
    public ResponseEntity<Map<String, Object>> ragasRunDetail(@PathVariable String runId) {
        RagasEvalRunEntity run = ragasRunDao.findByRunId(runId).orElse(null);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        List<RagasEvalSampleEntity> samples = ragasSampleDao.findByRunIdOrderByIdAsc(runId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run", run);
        body.put("samples", samples);
        return ResponseEntity.ok(body);
    }

    /**
     * 阶段二：导出生产日志评测候选（NDJSON 附件下载）。
     * 从 rag_answer_eval（困难样本优先）+ chat_history（真实提问分布）+ kb_feedback
     * 只读抽样，候选 reviewStatus=pending、contexts/reference 留空，须经专家审核
     * （eval/ragas/review_dataset.py）补标后才能进入 golden 基线。
     */
    @PostMapping("/ragas/dataset/export-production")
    public ResponseEntity<Object> exportProductionCandidates(
            @RequestParam(name = "days", defaultValue = "30") int days,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        EvalDatasetCandidateExporter exporter = datasetExporterProvider.getIfAvailable();
        if (exporter == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "RAGAS 评测未启用（wikiagent.ragas.enabled=false）");
            return ResponseEntity.status(409).body(body);
        }
        try {
            EvalDatasetCandidateExporter.ExportResult result = exporter.export(days, limit);
            if (result.candidateCount() == 0) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("message", "窗口内无可导出候选（judged 扫描 "
                        + result.judgedConsidered() + " / chat 扫描 " + result.chatConsidered()
                        + "，去重跳过 " + result.duplicateSkipped() + "，无答案跳过 "
                        + result.noAnswerSkipped() + "）");
                return ResponseEntity.status(409).body(body);
            }
            String fileName = result.filePath().getFileName().toString();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.parseMediaType("application/x-ndjson; charset=UTF-8"))
                    .body(result.ndjson());
        } catch (Exception e) {
            // MySQL 不可用等基础设施问题如实 500，不静默产出空集假装成功
            log.warn("生产评测候选导出失败: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "导出失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }
}
