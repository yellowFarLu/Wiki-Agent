package com.wikiagent.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * v4 §6.6.3 知识冲突解决 JPA 实体（V7__conflict_resolution.sql）。
 */
@Entity
@Table(name = "conflict_resolution")
public class ConflictResolutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chunk_id_a", nullable = false)
    private String chunkIdA;

    @Column(name = "chunk_id_b", nullable = false)
    private String chunkIdB;

    @Column(name = "similarity", nullable = false)
    private Double similarity;

    @Column(name = "domain_tag")
    private String domainTag;

    @Column(name = "sub_domain_tag")
    private String subDomainTag;

    @Column(name = "status", nullable = false)
    private String status = "DETECTED";  // DETECTED / RESOLVED / IGNORED

    @Column(name = "resolution")
    private String resolution;  // KEEP_A / KEEP_B / MERGE / DELETE_A / DELETE_B / KEEP_BOTH

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolution_comment", length = 2000)
    private String resolutionComment;

    @Column(name = "detected_at")
    private Instant detectedAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** V19：冲突来源（如 INGEST 入库检测 / SCAN 巡检；nullable 兼容存量行）。 */
    @Column(name = "source", length = 32)
    private String source;

    /** V19：系统建议的解决方式提示（nullable，人工裁决仅参考）。 */
    @Column(name = "resolution_hint", length = 16)
    private String resolutionHint;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChunkIdA() { return chunkIdA; }
    public void setChunkIdA(String chunkIdA) { this.chunkIdA = chunkIdA; }
    public String getChunkIdB() { return chunkIdB; }
    public void setChunkIdB(String chunkIdB) { this.chunkIdB = chunkIdB; }
    public Double getSimilarity() { return similarity; }
    public void setSimilarity(Double similarity) { this.similarity = similarity; }
    public String getDomainTag() { return domainTag; }
    public void setDomainTag(String domainTag) { this.domainTag = domainTag; }
    public String getSubDomainTag() { return subDomainTag; }
    public void setSubDomainTag(String subDomainTag) { this.subDomainTag = subDomainTag; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
    public String getResolutionComment() { return resolutionComment; }
    public void setResolutionComment(String resolutionComment) { this.resolutionComment = resolutionComment; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getResolutionHint() { return resolutionHint; }
    public void setResolutionHint(String resolutionHint) { this.resolutionHint = resolutionHint; }
}
