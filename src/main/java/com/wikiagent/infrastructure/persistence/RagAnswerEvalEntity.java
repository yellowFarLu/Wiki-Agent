package com.wikiagent.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * RAG 回答质量在线抽样评测样本（V18 - rag_answer_eval 表，方案见 docs/rag-accuracy-eval.md）。
 * <p>
 * faithfulness/relevance 使用 Integer 承载三态：1=通过 / 0=不通过 / null=无法评判
 * （如该次回答未引用知识库文档），null 不计入看板指标分母。
 */
@Entity
@Table(name = "rag_answer_eval",
        uniqueConstraints = @UniqueConstraint(name = "uk_rag_answer_eval",
                columnNames = {"session_id", "answer_hash"}),
        indexes = @Index(name = "idx_rag_answer_eval_status", columnList = "status, created_at"))
public class RagAnswerEvalEntity {

    /** 待评判。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已评判。 */
    public static final String STATUS_JUDGED = "JUDGED";
    /** 重试耗尽，评判失败（error 记录原因）。 */
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "user_id", length = 64)
    private String userId;

    /** 输入网关脱敏后的问题。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    /** 输出网关脱敏后的最终答案。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    /** SHA-256(answer) 十六进制，(session_id, answer_hash) 唯一防重。 */
    @Column(name = "answer_hash", nullable = false, length = 64)
    private String answerHash;

    /** JSON 数组，引用来源 docId 列表（去重保序）；无引用来源为 null。 */
    @Column(name = "source_doc_ids", columnDefinition = "TEXT")
    private String sourceDocIds;

    /** 回答链路：multi-agent / orchestrator-v1v2 / agent-rag / legacy-rag。 */
    @Column(length = 32)
    private String channel;

    /** 实际评判模型（降级链回退后的真实模型名）。 */
    @Column(name = "judge_model", length = 64)
    private String judgeModel;

    /** 1=论断均有依据 / 0=存在无依据论断 / null=无法评判。 */
    @Column(name = "faithfulness")
    private Integer faithfulness;

    /** 1=切题回答 / 0=答非所问 / null=无法评判。 */
    @Column(name = "relevance")
    private Integer relevance;

    @Column(name = "verdict_reason", length = 1024)
    private String verdictReason;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(length = 512)
    private String error;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "judged_at")
    private Instant judgedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getAnswerHash() { return answerHash; }
    public void setAnswerHash(String answerHash) { this.answerHash = answerHash; }
    public String getSourceDocIds() { return sourceDocIds; }
    public void setSourceDocIds(String sourceDocIds) { this.sourceDocIds = sourceDocIds; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getJudgeModel() { return judgeModel; }
    public void setJudgeModel(String judgeModel) { this.judgeModel = judgeModel; }
    public Integer getFaithfulness() { return faithfulness; }
    public void setFaithfulness(Integer faithfulness) { this.faithfulness = faithfulness; }
    public Integer getRelevance() { return relevance; }
    public void setRelevance(Integer relevance) { this.relevance = relevance; }
    public String getVerdictReason() { return verdictReason; }
    public void setVerdictReason(String verdictReason) { this.verdictReason = verdictReason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getJudgedAt() { return judgedAt; }
    public void setJudgedAt(Instant judgedAt) { this.judgedAt = judgedAt; }
}
