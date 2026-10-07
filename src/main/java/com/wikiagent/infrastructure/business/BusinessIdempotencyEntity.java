package com.wikiagent.infrastructure.business;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 操作幂等记录（V24 business_idempotency 表，对标 Stripe / IETF Idempotency-Key）：
 * 唯一约束 {@code (scope, idem_key)} 原子抢占，短期有过期窗口，与文件长期保留解耦。
 * <p>
 * status：IN_PROGRESS / COMPLETED / FAILED。
 */
@Entity
@Table(name = "business_idempotency",
        uniqueConstraints = @UniqueConstraint(name = "uk_biz_idem_scope_key", columnNames = {"scope", "idem_key"}))
public class BusinessIdempotencyEntity {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String scope;

    @Column(name = "idem_key", nullable = false, length = 128)
    private String idemKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "result_ref", length = 64)
    private String resultRef;

    @Column(length = 512)
    private String error;

    @Column(name = "biz_task_id", length = 64)
    private String bizTaskId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public BusinessIdempotencyEntity() {}

    public BusinessIdempotencyEntity(String scope, String idemKey, String requestHash,
                                     String status, LocalDateTime expiresAt) {
        this.scope = scope;
        this.idemKey = idemKey;
        this.requestHash = requestHash;
        this.status = status;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getScope() { return scope; }
    public String getIdemKey() { return idemKey; }
    public String getRequestHash() { return requestHash; }
    public String getStatus() { return status; }
    public String getResultRef() { return resultRef; }
    public String getError() { return error; }
    public String getBizTaskId() { return bizTaskId; }
    public String getTraceId() { return traceId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }

    public void setStatus(String status) { this.status = status; }
    public void setResultRef(String resultRef) { this.resultRef = resultRef; }
    public void setError(String error) { this.error = error; }
    public void setBizTaskId(String bizTaskId) { this.bizTaskId = bizTaskId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
