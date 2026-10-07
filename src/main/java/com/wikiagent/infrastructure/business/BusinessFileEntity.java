package com.wikiagent.infrastructure.business;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 业务文件元数据（V24 business_file 表）：长期保留，支撑历史会话下载。
 * <p>
 * fileType：MAPPING（上传映射表）/ CUSTOMS（生成清关）。checksum 为内容 sha256，
 * 用于内容审计与同用户自然去重（不建全局唯一，避免不同用户同内容互相冲突）。
 * storagePath 存相对路径（data/business-files/...），读取时由配置 data 目录拼接。
 */
@Entity
@Table(name = "business_file")
public class BusinessFileEntity {

    public static final String TYPE_MAPPING = "MAPPING";
    public static final String TYPE_CUSTOMS = "CUSTOMS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_id", nullable = false, length = 64, unique = true)
    private String fileId;

    @Column(name = "file_type", nullable = false, length = 16)
    private String fileType;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "mapping_file_id", length = 64)
    private String mappingFileId;

    @Column(name = "biz_task_id", length = 64)
    private String bizTaskId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(length = 64)
    private String checksum;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public BusinessFileEntity() {}

    public BusinessFileEntity(String fileId, String fileType, String originalName,
                              String storagePath, String contentType, int rowCount,
                              String userId, String checksum) {
        this.fileId = fileId;
        this.fileType = fileType;
        this.originalName = originalName;
        this.storagePath = storagePath;
        this.contentType = contentType;
        this.rowCount = rowCount;
        this.userId = userId;
        this.checksum = checksum;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getFileId() { return fileId; }
    public String getFileType() { return fileType; }
    public String getOriginalName() { return originalName; }
    public String getStoragePath() { return storagePath; }
    public String getContentType() { return contentType; }
    public int getRowCount() { return rowCount; }
    public String getMappingFileId() { return mappingFileId; }
    public String getBizTaskId() { return bizTaskId; }
    public String getUserId() { return userId; }
    public String getChecksum() { return checksum; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setMappingFileId(String mappingFileId) { this.mappingFileId = mappingFileId; }
    public void setBizTaskId(String bizTaskId) { this.bizTaskId = bizTaskId; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
}
