package com.wikiagent.infrastructure.persistence;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * RAGAS 评测用例集单行（V21 - ragas_eval_sample 表，一次执行的一条样本）。
 * <p>
 * contexts 为 JSON 数组字符串，errors 为 JSON 对象字符串（无错误时 null）；
 * 六项评分为 null 表示该样本该指标评分失败/无法评判，禁止映射成 0。
 */
@Entity
@Table(name = "ragas_eval_sample",
        uniqueConstraints = @UniqueConstraint(name = "uk_ragas_eval_sample",
                columnNames = {"run_id", "sample_id"}),
        indexes = @Index(name = "idx_ragas_eval_sample_run", columnList = "run_id"))
public class RagasEvalSampleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "sample_id", nullable = false, length = 128)
    private String sampleId;

    @Column(name = "domain_tag", length = 64)
    private String domainTag;

    /** 数据来源：seed-manual / synthetic / production / expert（V22，三阶段流水线）。 */
    @Column(length = 32)
    private String source;

    /** 难度：simple / reasoning / multi_hop / boundary（V22）。 */
    @Column(length = 32)
    private String difficulty;

    /** 审核状态：approved（进基线）/ pending（候选预览评分，不进主聚合）（V22）。 */
    @Column(name = "review_status", length = 16)
    private String reviewStatus;

    /** 答案来源：manual / reference-proxy（合成代理）/ production（线上真实回答）（V22）。 */
    @Column(name = "answer_origin", length = 32)
    private String answerOrigin;

    /** 标签逗号分隔（如 noise:misspelled、hard-negative:faithfulness）（V22）。 */
    @Column(length = 512)
    private String tags;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    /** JSON 数组：检索到的 contexts 原文。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String contexts;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reference;

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

    /** JSON 对象：{指标名: 错误信息}，无错误为 null。 */
    @Column(columnDefinition = "TEXT")
    private String errors;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getSampleId() { return sampleId; }
    public void setSampleId(String sampleId) { this.sampleId = sampleId; }
    public String getDomainTag() { return domainTag; }
    public void setDomainTag(String domainTag) { this.domainTag = domainTag; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getAnswerOrigin() { return answerOrigin; }
    public void setAnswerOrigin(String answerOrigin) { this.answerOrigin = answerOrigin; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getContexts() { return contexts; }
    public void setContexts(String contexts) { this.contexts = contexts; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
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
    public String getErrors() { return errors; }
    public void setErrors(String errors) { this.errors = errors; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
