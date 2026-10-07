package com.wikiagent.infrastructure.business;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 业务意图任务（V24 business_task 表）：审计/回放真相源。
 * <p>
 * 一次业务意图任务一行；槽位追问期原地更新（status/slots_json），出 FINAL → COMPLETED，
 * 意图切换则旧行置 CANCELLED 并新建一行。
 * <p>
 * status 取值：IN_PROGRESS / WAITING_SLOT / COMPLETED / FAILED / CANCELLED / ESCALATED_HUMAN。
 */
@Entity
@Table(name = "business_task")
public class BusinessTaskEntity {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_WAITING_SLOT = "WAITING_SLOT";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_ESCALATED_HUMAN = "ESCALATED_HUMAN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "biz_task_id", nullable = false, length = 64, unique = true)
    private String bizTaskId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "intent", nullable = false, length = 32)
    private String intent;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "slots_json", columnDefinition = "TEXT")
    private String slotsJson;

    @Column(name = "order_no", length = 32)
    private String orderNo;

    @Column(name = "result_file_id", length = 64)
    private String resultFileId;

    @Column(name = "human_task_id")
    private Long humanTaskId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(length = 512)
    private String error;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public BusinessTaskEntity() {}

    public BusinessTaskEntity(String bizTaskId, String userId, String sessionId, String intent) {
        this.bizTaskId = bizTaskId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.intent = intent;
        this.status = STATUS_IN_PROGRESS;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getBizTaskId() { return bizTaskId; }
    public String getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public String getIntent() { return intent; }
    public String getStatus() { return status; }
    public String getSlotsJson() { return slotsJson; }
    public String getOrderNo() { return orderNo; }
    public String getResultFileId() { return resultFileId; }
    public Long getHumanTaskId() { return humanTaskId; }
    public String getTraceId() { return traceId; }
    public String getError() { return error; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }

    public void setStatus(String status) { this.status = status; this.updatedAt = LocalDateTime.now(); }
    public void setSlotsJson(String slotsJson) { this.slotsJson = slotsJson; this.updatedAt = LocalDateTime.now(); }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; this.updatedAt = LocalDateTime.now(); }
    public void setResultFileId(String resultFileId) { this.resultFileId = resultFileId; this.updatedAt = LocalDateTime.now(); }
    public void setHumanTaskId(Long humanTaskId) { this.humanTaskId = humanTaskId; this.updatedAt = LocalDateTime.now(); }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public void setError(String error) { this.error = error; this.updatedAt = LocalDateTime.now(); }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; this.updatedAt = LocalDateTime.now(); }
}
