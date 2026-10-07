package com.wikiagent.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 对话历史记录（chat_history 表）。
 * 每条记录对应一个会话中的一条消息（user / assistant / blocked）。
 */
@Entity
@Table(name = "chat_history")
public class ChatHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** user 或 assistant */
    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * assistant 回答引用的检索来源（JSON 数组，结构与 SSE sources 事件一致：
     * index/docId/versionNo/pageNo/snippet/artifactId/score/filename）。
     * 仅 assistant 消息可能有值；user/blocked 为 null。历史会话重开时用于渲染正文角标与来源列表，
     * 避免回答中 [1][2] 失去对应来源（实时流由 SSE 推送，不落库则刷新即丢失）。
     */
    @Column(name = "sources_json", columnDefinition = "TEXT")
    private String sourcesJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ChatHistoryEntity() {}

    public ChatHistoryEntity(String sessionId, String role, String content) {
        this(sessionId, role, content, null);
    }

    public ChatHistoryEntity(String sessionId, String role, String content, String sourcesJson) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.sourcesJson = sourcesJson;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getSourcesJson() { return sourcesJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
