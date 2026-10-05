package com.wikiagent.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * v5 §19 内容违规记录 JPA 实体（V8__gateway_audit.sql - content_violation_log 表）。
 */
@Entity
@Table(name = "content_violation_log")
public class ContentViolationLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gateway_audit_id")
    private Long gatewayAuditId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "violation_type", nullable = false)
    private String violationType;  // PROMPT_INJECTION / JAILBREAK / PII_LEAK / TOXIC_CONTENT / SYSTEM_PROMPT_LEAK / PROTECTED_MATERIAL

    @Column(name = "violation_detail", columnDefinition = "TEXT")
    private String violationDetail;

    @Column(name = "original_content", columnDefinition = "TEXT")
    private String originalContent;

    @Column(name = "blocked_content", columnDefinition = "TEXT")
    private String blockedContent;

    @Column(name = "severity", nullable = false)
    private String severity;  // LOW / MEDIUM / HIGH / CRITICAL

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGatewayAuditId() { return gatewayAuditId; }
    public void setGatewayAuditId(Long gatewayAuditId) { this.gatewayAuditId = gatewayAuditId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getViolationType() { return violationType; }
    public void setViolationType(String violationType) { this.violationType = violationType; }
    public String getViolationDetail() { return violationDetail; }
    public void setViolationDetail(String violationDetail) { this.violationDetail = violationDetail; }
    public String getOriginalContent() { return originalContent; }
    public void setOriginalContent(String originalContent) { this.originalContent = originalContent; }
    public String getBlockedContent() { return blockedContent; }
    public void setBlockedContent(String blockedContent) { this.blockedContent = blockedContent; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
