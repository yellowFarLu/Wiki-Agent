package com.wikiagent.application.knowledge;

import com.wikiagent.infrastructure.persistence.KbFeedbackJpaDao;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataEntity;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalEntity;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalJpaDao;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * v4 §6.6.2 知识指标每日聚合作业。
 * <p>
 * 每日 {@code wikiagent.metrics.aggregation-cron}（默认 02:00）聚合最近 30 天的
 * metric_event 与 kb_feedback，计算：带标签检索精确率、使用频率分布，
 * 并基于 kb_feedback 识别<b>疑似无用知识</b>（知识被检索访问过，且无用反馈次数
 * 占反馈总数（有用+无用）比例 &gt; {@code useless-ratio-threshold}（默认 0.5，严格大于）），
 * 供知识看板集中治理；
 * 并按下述过期模型识别疑似过期知识（stale_score &lt; {@code stale-threshold}），公式见 {@link StaleScore}。
 * <p>
 * <b>指标口径（业界标准对齐，禁止捏造）：</b>
 * <ul>
 *   <li><b>精确率 precisionRate（带标签检索精确率）</b>＝窗口内 chunk 级 USEFUL 反馈数
 *       / (USEFUL+USELESS) 反馈数。相关性标签来自用户对检索知识的反馈（USEFUL=相关 /
 *       USELESS=不相关）；无反馈标签的检索不进分母——与离线评测 retrievalHitRate 同一口径。
 *       会话级反馈（chunkId=null）标注的是整条答案而非检索项相关性，不计入。
 *       分母为 0 → null → 看板显示"暂无数据"，绝不显示 0%。</li>
 *   <li><b>召回率 recall：线上不输出</b>。业界召回率＝检索到的相关项 / 语料中全部相关项，
 *       需要全量相关性标注（golden set），线上流量无法诚实计算（原 CITED/RETRIEVED 在
 *       本管道"召回即引用"恒等于 1.0，是零信息恒真式，已移除）。召回率仅由离线评测子系统
 *       基于黄金集计算（RAGAS context recall 口径），见 docs/operations/eval-baseline.md。</li>
 *   <li><b>有用/无用原始计数</b>：与检索计数同为近 30 天窗口，避免跨窗口并排误读。</li>
 * </ul>
 * <p>
 * 结果保存在内存快照（{@link #latest()}）供看板读取，并在 Micrometer 可用时
 * 上报同名 Gauge（Prometheus 抓取）。
 * <p>
 * <b>诚实声明</b>：快照仅存单实例内存，多实例部署时各节点各自聚合相同数据（幂等只读）；
 * 看板实时接口 MetricsController 仍直接查库，本快照用于定时观测与 Prometheus。
 */
@Component
public class MetricsAggregationJob {

    private static final Logger log = LoggerFactory.getLogger(MetricsAggregationJob.class);

    private final KnowledgeMetadataJpaDao metadataDao;
    private final MetricEventJpaDao metricEventDao;
    private final KbFeedbackJpaDao feedbackDao;
    private final RagAnswerEvalJpaDao answerEvalDao;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final long tauTimeDays;
    private final double staleThreshold;
    private final double uselessRatioThreshold;

    private final AtomicReference<MetricsSnapshot> latest = new AtomicReference<>();

    public MetricsAggregationJob(KnowledgeMetadataJpaDao metadataDao,
                                 MetricEventJpaDao metricEventDao,
                                 KbFeedbackJpaDao feedbackDao,
                                 RagAnswerEvalJpaDao answerEvalDao,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider,
                                 @Value("${wikiagent.metrics.tau-time-days:180}") long tauTimeDays,
                                 @Value("${wikiagent.metrics.stale-threshold:0.3}") double staleThreshold,
                                 @Value("${wikiagent.metrics.useless-ratio-threshold:0.5}") double uselessRatioThreshold) {
        this.metadataDao = metadataDao;
        this.metricEventDao = metricEventDao;
        this.feedbackDao = feedbackDao;
        this.answerEvalDao = answerEvalDao;
        this.meterRegistryProvider = meterRegistryProvider;
        this.tauTimeDays = tauTimeDays;
        this.staleThreshold = staleThreshold;
        this.uselessRatioThreshold = uselessRatioThreshold;
    }

    /** 每日 02:00 聚合（cron 可配）。 */
    @Scheduled(cron = "${wikiagent.metrics.aggregation-cron:0 0 2 * * ?}")
    public void scheduledAggregate() {
        MetricsSnapshot snapshot = aggregate();
        log.info("知识指标聚合完成: 知识总数={} 疑似无用知识={} 疑似过期={}",
                snapshot.totalKnowledge(), snapshot.suspectUselessKnowledge().size(),
                snapshot.staleKnowledge().size());
    }

    /**
     * 执行一次聚合（定时与手动共用）。
     */
    public MetricsSnapshot aggregate() {
        Instant now = Instant.now();
        Instant since30d = now.minus(Duration.ofDays(30));

        List<KnowledgeMetadataEntity> knowledge = metadataDao.findAllActive();
        List<MetricEventEntity> events = metricEventDao.findSince(since30d);

        long retrievals = events.stream().filter(e -> "RETRIEVED".equals(e.getEventType())).count();
        long citations = events.stream().filter(e -> "CITED".equals(e.getEventType())).count();
        Map<String, Long> feedbackCounts = toCountMap(feedbackDao.countByCreatedAtAfterGroupByType(since30d));
        long useful = feedbackCounts.getOrDefault("USEFUL", 0L);
        long useless = feedbackCounts.getOrDefault("USELESS", 0L);
        Double precisionRate = labeledPrecision(since30d);

        // chunk → 近 30 天检索次数
        Map<String, Long> retrievalCountByChunk = events.stream()
                .filter(e -> "RETRIEVED".equals(e.getEventType()) && e.getChunkId() != null)
                .collect(Collectors.groupingBy(MetricEventEntity::getChunkId, Collectors.counting()));

        List<StaleKnowledge> staleKnowledge = new ArrayList<>();
        for (KnowledgeMetadataEntity k : knowledge) {
            long ageDays = k.getCreatedAt() == null
                    ? Long.MAX_VALUE
                    : Duration.between(k.getCreatedAt(), now).toDays();
            long recentCount = retrievalCountByChunk.getOrDefault(k.getChunkId(), 0L);
            double staleScore = StaleScore.score(ageDays, recentCount, tauTimeDays);
            if (staleScore < staleThreshold) {
                staleKnowledge.add(new StaleKnowledge(
                        k.getChunkId(), k.getDocId(), k.getDomainTag(), k.getSubDomainTag(),
                        k.getCreatedBy(), k.getCreatedAt(), recentCount,
                        Math.round(staleScore * 1000.0) / 1000.0));
            }
        }

        List<SuspectUselessKnowledge> suspectUselessKnowledge = suspectUselessKnowledge(knowledge);

        MetricsSnapshot snapshot = new MetricsSnapshot(
                now, knowledge.size(), retrievals, citations, useful, useless,
                precisionRate,
                staleKnowledge, suspectUselessKnowledge, answerEvalSummary());
        latest.set(snapshot);
        publishGauges(snapshot);
        return snapshot;
    }

    /**
     * 带标签检索精确率（业界 precision 口径：相关检索项 / 全部已标注检索项）。
     * 分子：窗口内 chunk 级 USEFUL 反馈数；分母：窗口内 chunk 级 USEFUL+USELESS 反馈数；
     * 无标签样本不进分母；分母为 0 返回 null（看板显示"暂无数据"，不显示 0%）。
     */
    private Double labeledPrecision(Instant since) {
        Map<String, Long> labeled = toCountMap(feedbackDao.countChunkLevelByCreatedAtAfterGroupByType(since));
        long labeledUseful = labeled.getOrDefault("USEFUL", 0L);
        long labeledTotal = labeledUseful + labeled.getOrDefault("USELESS", 0L);
        return labeledTotal > 0 ? round3((double) labeledUseful / labeledTotal) : null;
    }

    private static Map<String, Long> toCountMap(List<Object[]> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.merge((String) row[0], ((Number) row[1]).longValue(), Long::sum);
        }
        return counts;
    }

    /** 最近一次聚合快照（未聚合过返回 null）。 */
    public MetricsSnapshot latest() {
        return latest.get();
    }

    /**
     * 疑似无用知识识别。
     * <p>
     * 口径（用户治理视角，避免"有用率"分子分母争议）：
     * <ol>
     *   <li>知识被检索访问过（metric_event 全量 RETRIEVED 次数 &gt; 0）；</li>
     *   <li>该 chunk 有反馈（kb_feedback，有用+无用总数 &gt; 0）；</li>
     *   <li>无用次数 / (有用+无用) 严格大于阈值 {@code useless-ratio-threshold}（默认 0.5）。</li>
     * </ol>
     * 有用/无用口径以 kb_feedback 表为准；会话级反馈 chunkId 为 null，不参与 chunk 维度判定。
     * 结果按无用占比降序、无用次数降序排列，供看板集中治理。
     */
    private List<SuspectUselessKnowledge> suspectUselessKnowledge(List<KnowledgeMetadataEntity> knowledge) {
        Map<String, long[]> feedbackByChunk = new HashMap<>();
        for (Object[] row : feedbackDao.countGroupedByChunkAndType()) {
            long[] counts = feedbackByChunk.computeIfAbsent((String) row[0], k -> new long[2]);
            if ("USEFUL".equals(row[1])) {
                counts[0] += ((Number) row[2]).longValue();
            } else if ("USELESS".equals(row[1])) {
                counts[1] += ((Number) row[2]).longValue();
            }
        }
        Map<String, Long> retrievedByChunk = new HashMap<>();
        for (Object[] row : metricEventDao.countByChunkIdGroupByEventType("RETRIEVED")) {
            retrievedByChunk.put((String) row[0], ((Number) row[1]).longValue());
        }

        List<SuspectUselessKnowledge> suspects = new ArrayList<>();
        for (KnowledgeMetadataEntity k : knowledge) {
            long[] fb = feedbackByChunk.get(k.getChunkId());
            if (fb == null) {
                continue;
            }
            long usefulCnt = fb[0];
            long uselessCnt = fb[1];
            long total = usefulCnt + uselessCnt;
            if (total == 0) {
                continue;
            }
            long retrievalCnt = retrievedByChunk.getOrDefault(k.getChunkId(), 0L);
            if (retrievalCnt <= 0) {
                continue;
            }
            double uselessRatio = (double) uselessCnt / total;
            if (uselessRatio <= uselessRatioThreshold) {
                continue;
            }
            suspects.add(new SuspectUselessKnowledge(
                    k.getChunkId(), k.getDocId(), k.getDomainTag(), k.getSubDomainTag(),
                    k.getSourceFilename(), k.getCreatedBy(), retrievalCnt,
                    usefulCnt, uselessCnt, total, round3(uselessRatio)));
        }
        suspects.sort(Comparator
                .comparingDouble(SuspectUselessKnowledge::uselessRatio).reversed()
                .thenComparing(Comparator.comparingLong(SuspectUselessKnowledge::uselessCount).reversed()));
        return suspects;
    }

    /**
     * RAG 回答评测三率聚合（方案 docs/rag-accuracy-eval.md）。
     * 诚实口径：每个率独立分母（维度非 null 的行数），分母为 0 → null → 看板显示"暂无数据"。
     */
    private AnswerEvalSummary answerEvalSummary() {
        long judged = answerEvalDao.countByStatus(RagAnswerEvalEntity.STATUS_JUDGED);
        long pending = answerEvalDao.countByStatus(RagAnswerEvalEntity.STATUS_PENDING);
        long failed = answerEvalDao.countByStatus(RagAnswerEvalEntity.STATUS_FAILED);
        long fJudged = answerEvalDao.countByFaithfulnessIsNotNull();
        long rJudged = answerEvalDao.countByRelevanceIsNotNull();
        long aJudged = answerEvalDao.countByFaithfulnessIsNotNullAndRelevanceIsNotNull();
        Double fRate = fJudged > 0 ? round3((double) answerEvalDao.countByFaithfulness(1) / fJudged) : null;
        Double rRate = rJudged > 0 ? round3((double) answerEvalDao.countByRelevance(1) / rJudged) : null;
        Double aRate = aJudged > 0
                ? round3((double) answerEvalDao.countByFaithfulnessAndRelevance(1, 1) / aJudged) : null;
        return new AnswerEvalSummary(judged, pending, failed, aRate, fRate, rRate, aJudged, fJudged, rJudged);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private void publishGauges(MetricsSnapshot s) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.gauge("wikiagent.knowledge.total", s.totalKnowledge());
        registry.gauge("wikiagent.knowledge.stale.count", s.staleKnowledge().size());
        registry.gauge("wikiagent.knowledge.suspect.useless.count", s.suspectUselessKnowledge().size());
        // 带标签检索精确率：分母为 0（无反馈标签）时不上报，不伪造 0 值
        if (s.precisionRate() != null) {
            registry.gauge("wikiagent.retrieval.precision.rate", s.precisionRate());
        }
        AnswerEvalSummary eval = s.answerEval();
        if (eval != null) {
            if (eval.accuracyRate() != null) {
                registry.gauge("wikiagent.rag.eval.accuracy.rate", eval.accuracyRate());
            }
            if (eval.faithfulnessRate() != null) {
                registry.gauge("wikiagent.rag.eval.faithfulness.rate", eval.faithfulnessRate());
            }
            if (eval.relevanceRate() != null) {
                registry.gauge("wikiagent.rag.eval.relevance.rate", eval.relevanceRate());
            }
        }
    }

    /**
     * 聚合快照。
     *
     * @param usefulCount   近 30 天全部 USEFUL 反馈数（含会话级）
     * @param uselessCount  近 30 天全部 USELESS 反馈数（含会话级）
     * @param precisionRate 带标签检索精确率＝窗口内 chunk 级 USEFUL/(USEFUL+USELESS)；
     *                      无反馈标签时为 null（不显示 0%）。召回率线上不输出（需要
     *                      golden set 全量标注，仅离线评测可算，见类级 javadoc）。
     */
    public record MetricsSnapshot(
            Instant aggregatedAt,
            long totalKnowledge,
            long totalRetrievals,
            long totalCitations,
            long usefulCount,
            long uselessCount,
            Double precisionRate,
            List<StaleKnowledge> staleKnowledge,
            List<SuspectUselessKnowledge> suspectUselessKnowledge,
            AnswerEvalSummary answerEval) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("aggregatedAt", aggregatedAt);
            m.put("totalKnowledge", totalKnowledge);
            m.put("totalRetrievals", totalRetrievals);
            m.put("totalCitations", totalCitations);
            m.put("usefulCount", usefulCount);
            m.put("uselessCount", uselessCount);
            m.put("precisionRate", precisionRate);
            m.put("scanPeriod", "30d");
            m.put("staleKnowledgeCount", staleKnowledge.size());
            m.put("staleKnowledge", staleKnowledge);
            m.put("suspectUselessKnowledgeCount", suspectUselessKnowledge.size());
            m.put("suspectUselessKnowledge", suspectUselessKnowledge);
            m.put("answerEval", answerEval);
            return m;
        }
    }

    /**
     * RAG 回答评测汇总（三率独立分母，无数据为 null 不捏造）。
     *
     * @param accuracyRate    Σ(f=1∧r=1)/Σ(两维均≠null)
     * @param faithfulnessRate Σ(f=1)/Σ(f≠null)
     * @param relevanceRate    Σ(r=1)/Σ(r≠null)
     */
    public record AnswerEvalSummary(
            long judgedCount,
            long pendingCount,
            long failedCount,
            Double accuracyRate,
            Double faithfulnessRate,
            Double relevanceRate,
            long accuracyJudged,
            long faithfulnessJudged,
            long relevanceJudged) {
    }

    /** 疑似过期知识条目。 */
    public record StaleKnowledge(
            String chunkId,
            String docId,
            String domainTag,
            String subDomainTag,
            String createdBy,
            Instant createdAt,
            long recentRetrievals,
            double staleScore) {}

    /**
     * 疑似无用知识条目：被检索访问过，且无用反馈占反馈总数（有用+无用）比例超过阈值（默认 50%）。
     *
     * @param usefulCount    kb_feedback 中该 chunk 的 USEFUL 次数
     * @param uselessCount   kb_feedback 中该 chunk 的 USELESS 次数
     * @param totalFeedback  有用+无用总次数
     * @param uselessRatio   无用占比 = uselessCount / totalFeedback
     * @param retrievalCount metric_event 全量 RETRIEVED 次数（&gt; 0 才会入选）
     */
    public record SuspectUselessKnowledge(
            String chunkId,
            String docId,
            String domainTag,
            String subDomainTag,
            String sourceFilename,
            String createdBy,
            long retrievalCount,
            long usefulCount,
            long uselessCount,
            long totalFeedback,
            double uselessRatio) {}
}
