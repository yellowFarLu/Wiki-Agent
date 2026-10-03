package com.wikiagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "kb_document")
public class KbDocument {

    public static final String PARSING = "PARSING";
    public static final String CLEANING = "CLEANING";
    public static final String CHUNKING = "CHUNKING";
    public static final String EMBEDDING = "EMBEDDING";
    public static final String INDEXING = "INDEXING";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";
    /** 子项目 B：结构化字段抽取进行中（可选态）。 */
    public static final String EXTRACTING = "EXTRACTING";
    /** 子项目 B：介质必须依赖 AI 但供应商未配置，流水线终态（不静默产出空 chunk）。 */
    public static final String AI_SKIPPED = "AI_SKIPPED";

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false, length = 16)
    private String docType;

    private long sizeBytes;

    @Column(nullable = false, length = 24)
    private String status = PARSING;

    private int parentCount;

    private int childCount;

    @Column(length = 2000)
    private String error;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /** V19：知识生效日期（去重/冲突裁决依据；存量文档为 null）。 */
    private LocalDate effectiveDate;

    /** V19：生效日期设置人（nullable）。 */
    @Column(length = 64)
    private String effectiveSetBy;

    /** V19：生效日期设置时间（nullable）。 */
    private Instant effectiveSetAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getParentCount() { return parentCount; }
    public void setParentCount(int parentCount) { this.parentCount = parentCount; }
    public int getChildCount() { return childCount; }
    public void setChildCount(int childCount) { this.childCount = childCount; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }
    public String getEffectiveSetBy() { return effectiveSetBy; }
    public void setEffectiveSetBy(String effectiveSetBy) { this.effectiveSetBy = effectiveSetBy; }
    public Instant getEffectiveSetAt() { return effectiveSetAt; }
    public void setEffectiveSetAt(Instant effectiveSetAt) { this.effectiveSetAt = effectiveSetAt; }
}
