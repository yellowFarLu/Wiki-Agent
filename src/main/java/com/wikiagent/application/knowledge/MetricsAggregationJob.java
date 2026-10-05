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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * v4 §6.6.2 知识指标每日聚合作业。
 * <p>
 * 每日 {@code wikiagent.metrics.aggregation-cron}（默认 02:00）聚合最近 30 天的
 * metric_event，计算：召回率、精确率、有用率、无用率、使用频率分布，
 * 并按下述过期模型识别疑似过期知识（stale_score &lt; {@code stale-threshold}）：
 * <pre>
 * staleScore = 0.5 * exp(-ageDays / tauTimeDays)
 *            + 0.5 * min(1, recentRetrievalCount / 5)
 * </pre>
 * 时间衰减常数 {@code tau-time-days}=180；频率参考窗口取事件近 30 天。
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

    private final AtomicReference<MetricsSnapshot> latest = new AtomicReference<>();

    public MetricsAggregationJob(KnowledgeMetadataJpaDao metadataDao,
                                 MetricEventJpaDao metricEventDao,
                                 KbFeedbackJpaDao feedbackDao,
                                 RagAnswerEvalJpaDao answerEvalDao,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider,
                                 @Value("${wikiagent.metrics.tau-time-days:180}") long tauTimeDays,
                                 @Value("${wikiagent.metrics.stale-threshold:0.3}") double staleThreshold) {
        this.metadataDao = metadataDao;
        this.metricEventDao = metricEventDao;
        this.feedbackDao = feedbackDao;
        this.answerEvalDao = answerEvalDao;
        this.meterRegistryProvider = meterRegistryProvider;
        this.tauTimeDays = tauTimeDays;
        this.staleThreshold = staleThreshold;
    }

    /** 每日 02:00 聚合（cron 可配）。 */
    @Scheduled(cron = "${wikiagent.metrics.aggregation-cron:0 0 2 * * ?}")
    public void scheduledAggregate() {
        MetricsSnapshot snapshot = aggregate();
        log.info("知识指标聚合完成: 知识总数={} 有用率={} 疑似过期={}",
                snapshot.totalKnowledge(), snapshot.usefulnessRate(), snapshot.staleKnowledge().size());
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
        long useful = feedbackDao.findAll().stream().filter(f -> "USEFUL".equals(f.getFeedbackType())).count();
        long useless = feedbackDao.findAll().stream().filter(f -> "USELESS".equals(f.getFeedbackType())).count();
        double usefulnessRate = (useful + useless) > 0 ? (double) useful / (useful + useless) : 0.0;
        double recallRate = retrievals > 0 ? (double) citations / retrievals : 0.0;
        double precisionRate = retrievals > 0
                ? Math.min(1.0, (double) useful / Math.max(1, retrievals)) : 0.0;

        // chunk → 近 30 天检索次数
        Map<String, Long> retrievalCountByChunk = events.stream()
                .filter(e -> "RETRIEVED".equals(e.getEventType()) && e.getChunkId() != null)
                .collect(Collectors.groupingBy(MetricEventEntity::getChunkId, Collectors.counting()));

        List<StaleKnowledge> staleKnowledge = new ArrayList<>();
        for (KnowledgeMetadataEntity k : knowledge) {
            long ageDays = k.getCreatedAt() == null
                    ? Long.MAX_VALUE
                    : Duration.between(k.getCreatedAt(), now).toDays();
            double timeDecay = ageDays >= Long.MAX_VALUE ? 0.0 : Math.exp(-(double) ageDays / tauTimeDays);
            long recentCount = retrievalCountByChunk.getOrDefault(k.getChunkId(), 0L);
            double freqScore = Math.min(1.0, recentCount / 5.0);
            double staleScore = 0.5 * timeDecay + 0.5 * freqScore;
            if (staleScore < staleThreshold) {
                staleKnowledge.add(new StaleKnowledge(
                        k.getChunkId(), k.getDocId(), k.getDomainTag(), k.getSubDomainTag(),
                        k.getCreatedBy(), k.getCreatedAt(), recentCount,
                        Math.round(staleScore * 1000.0) / 1000.0));
            }
        }

        MetricsSnapshot snapshot = new MetricsSnapshot(
                now, knowledge.size(), retrievals, citations, useful, useless,
                Math.round(usefulnessRate * 1000.0) / 1000.0,
                Math.round(recallRate * 1000.0) / 1000.0,
                Math.round(precisionRate * 1000.0) / 1000.0,
                staleKnowledge, answerEvalSummary());
        latest.set(snapshot);
        publishGauges(snapshot);
        return snapshot;
    }

    /** 最近一次聚合快照（未聚合过返回 null）。 */
    public MetricsSnapshot latest() {
        return latest.get();
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
        registry.gauge("wikiagent.feedback.usefulness.rate", s.usefulnessRate());
        registry.gauge("wikiagent.retrieval.recall.rate", s.recallRate());
        registry.gauge("wikiagent.retrieval.precision.rate", s.precisionRate());
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

    /** 聚合快照。 */
    public record MetricsSnapshot(
            Instant aggregatedAt,
            long totalKnowledge,
            long totalRetrievals,
            long totalCitations,
            long usefulCount,
            long uselessCount,
            double usefulnessRate,
            double recallRate,
            double precisionRate,
            List<StaleKnowledge> staleKnowledge,
            AnswerEvalSummary answerEval) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("aggregatedAt", aggregatedAt);
            m.put("totalKnowledge", totalKnowledge);
            m.put("totalRetrievals", totalRetrievals);
            m.put("totalCitations", totalCitations);
            m.put("usefulCount", usefulCount);
            m.put("uselessCount", uselessCount);
            m.put("usefulnessRate", usefulnessRate);
            m.put("uselessnessRate", 1.0 - usefulnessRate);
            m.put("recallRate", recallRate);
            m.put("precisionRate", precisionRate);
            m.put("staleKnowledgeCount", staleKnowledge.size());
            m.put("staleKnowledge", staleKnowledge);
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
}
