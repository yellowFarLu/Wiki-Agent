package com.wikiagent.infrastructure.persistence;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * RAGAS 离线评测一次执行记录（V21 - ragas_eval_run 表，方案见 docs/operations/eval-baseline.md §6）。
 * <p>
 * status：{@link #STATUS_RUNNING}（执行中）/ {@link #STATUS_OK}（至少一项指标有值）/
 * {@link #STATUS_ERROR}（脚本失败或全部指标无值）。六项指标列为 null 表示无成功评分
 * （看板显示"-"，不计 0）。
 */
@Entity
@Table(name = "ragas_eval_run",
        uniqueConstraints = @UniqueConstraint(name = "uk_ragas_eval_run", columnNames = "run_id"),
        indexes = @Index(name = "idx_ragas_eval_run_status", columnList = "status, created_at"))
public class RagasEvalRunEntity {

    /** 执行中。 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 至少一项指标评分成功。 */
    public static final String STATUS_OK = "OK";
    /** 脚本失败 / 超时 / 全部指标无值（reason 记录原因）。 */
    public static final String STATUS_ERROR = "ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(nullable = false, length = 16)
    private String status = STATUS_RUNNING;

    @Column(name = "judge_model", length = 64)
    private String judgeModel;

    @Column(name = "embedding_model", length = 64)
    private String embeddingModel;

    @Column(length = 256)
    private String endpoint;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(name = "duration_sec")
    private Double durationSec;

    @Column(name = "faithfulness")
    private Double faithfulness;

    @Column(name = "answer_relevancy")
    private Double answerRelevancy;

    @Column(name = "context_precision")
    private Double contextPrecision;

    @Column(name = "context_recall")
    private Double contextRecall;

    @Column(name = "factual_correctness")
    private Double factualCorrectness;

    @Column(name = "semantic_similarity")
    private Double semanticSimilarity;

    @Column(length = 1024)
    private String reason;

    /** 脚本 stdout/stderr 尾部输出，供失败排查。 */
    @Column(name = "output_log", columnDefinition = "TEXT")
    private String outputLog;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getJudgeModel() { return judgeModel; }
    public void setJudgeModel(String judgeModel) { this.judgeModel = judgeModel; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
    public Double getDurationSec() { return durationSec; }
    public void setDurationSec(Double durationSec) { this.durationSec = durationSec; }
    public Double getFaithfulness() { return faithfulness; }
    public void setFaithfulness(Double faithfulness) { this.faithfulness = faithfulness; }
    public Double getAnswerRelevancy() { return answerRelevancy; }
    public void setAnswerRelevancy(Double answerRelevancy) { this.answerRelevancy = answerRelevancy; }
    public Double getContextPrecision() { return contextPrecision; }
    public void setContextPrecision(Double contextPrecision) { this.contextPrecision = contextPrecision; }
    public Double getContextRecall() { return contextRecall; }
    public void setContextRecall(Double contextRecall) { this.contextRecall = contextRecall; }
    public Double getFactualCorrectness() { return factualCorrectness; }
    public void setFactualCorrectness(Double factualCorrectness) { this.factualCorrectness = factualCorrectness; }
    public Double getSemanticSimilarity() { return semanticSimilarity; }
    public void setSemanticSimilarity(Double semanticSimilarity) { this.semanticSimilarity = semanticSimilarity; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getOutputLog() { return outputLog; }
    public void setOutputLog(String outputLog) { this.outputLog = outputLog; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
