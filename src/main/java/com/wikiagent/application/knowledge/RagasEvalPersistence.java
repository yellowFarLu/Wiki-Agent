package com.wikiagent.application.knowledge;

import com.wikiagent.infrastructure.persistence.RagasEvalRunEntity;
import com.wikiagent.infrastructure.persistence.RagasEvalRunJpaDao;
import com.wikiagent.infrastructure.persistence.RagasEvalSampleEntity;
import com.wikiagent.infrastructure.persistence.RagasEvalSampleJpaDao;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * RAGAS 评测结果持久化（事务边界）：run 与 samples 同事务写入，避免半成品状态。
 */
@Component
public class RagasEvalPersistence {

    private final RagasEvalRunJpaDao runDao;
    private final RagasEvalSampleJpaDao sampleDao;

    public RagasEvalPersistence(RagasEvalRunJpaDao runDao, RagasEvalSampleJpaDao sampleDao) {
        this.runDao = runDao;
        this.sampleDao = sampleDao;
    }

    @Transactional
    public void persistNewRunning(RagasEvalRunEntity run) {
        runDao.save(run);
    }

    /** 报告映射结果落库：保留原行 startedAt/createdAt，回填终态字段与用例集。 */
    @Transactional
    public void persistOutcome(RagasEvalRunEntity mapped, List<RagasEvalSampleEntity> samples) {
        RagasEvalRunEntity managed = runDao.findByRunId(mapped.getRunId())
                .orElseThrow(() -> new IllegalStateException("run 行不存在: " + mapped.getRunId()));
        managed.setStatus(mapped.getStatus());
        managed.setJudgeModel(mapped.getJudgeModel());
        managed.setEmbeddingModel(mapped.getEmbeddingModel());
        managed.setEndpoint(mapped.getEndpoint());
        managed.setSampleCount(mapped.getSampleCount());
        managed.setDurationSec(mapped.getDurationSec());
        managed.setFaithfulness(mapped.getFaithfulness());
        managed.setAnswerRelevancy(mapped.getAnswerRelevancy());
        managed.setContextPrecision(mapped.getContextPrecision());
        managed.setContextRecall(mapped.getContextRecall());
        managed.setFactualCorrectness(mapped.getFactualCorrectness());
        managed.setSemanticSimilarity(mapped.getSemanticSimilarity());
        managed.setReason(mapped.getReason());
        managed.setOutputLog(mapped.getOutputLog());
        managed.setFinishedAt(Instant.now());
        runDao.save(managed);
        sampleDao.saveAll(samples);
    }

    @Transactional
    public void failRun(String runId, String reason, String outputLog) {
        runDao.findByRunId(runId).ifPresent(run -> {
            run.setStatus(RagasEvalRunEntity.STATUS_ERROR);
            run.setReason(truncate(reason, 1024));
            run.setOutputLog(outputLog);
            run.setFinishedAt(Instant.now());
            runDao.save(run);
        });
    }

    /** 应用启动时把上次遗留的 RUNNING 标记为中断（重启不可能继续原进程）。 */
    @Transactional
    public int recoverInterrupted() {
        List<RagasEvalRunEntity> stale = runDao.findByStatus(RagasEvalRunEntity.STATUS_RUNNING);
        for (RagasEvalRunEntity run : stale) {
            run.setStatus(RagasEvalRunEntity.STATUS_ERROR);
            run.setReason("应用重启，评测进程中断");
            run.setFinishedAt(Instant.now());
        }
        runDao.saveAll(stale);
        return stale.size();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
