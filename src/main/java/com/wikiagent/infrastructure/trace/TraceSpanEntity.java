package com.wikiagent.infrastructure.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * v1-v2 §8 链路追踪 JPA 实体，映射 agent_trace 表（V2__trace.sql）。
 * <p>
 * 每行记录 Agent 执行链路的一个 span：
 * plan / node / tool_call / llm_call / generate。
 * 与领域值对象 {@link com.wikiagent.domain.trace.TraceSpan} 一一对应。
 */
@Entity
@Table(name = "agent_trace")
public class TraceSpanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, length = 128)
    private String conversationId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "node_id", length = 64)
    private String nodeId;

    @Column(name = "span_type", nullable = false, length = 32)
    private String spanType;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "input_data")
    private String inputData;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "output_data")
    private String outputData;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "error_msg", length = 2000)
    private String errorMsg;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "token_input")
    private Integer tokenInput;

    @Column(name = "token_output")
    private Integer tokenOutput;

    @Column(length = 32)
    private String intent;

    @Column(name = "model_used", length = 64)
    private String modelUsed;

    /** AC-I1（V16）：链路关联 ID，持久化时取自 MDC；无 MDC 上下文时为 NULL。 */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getSpanType() { return spanType; }
    public void setSpanType(String spanType) { this.spanType = spanType; }
    public String getInputData() { return inputData; }
    public void setInputData(String inputData) { this.inputData = inputData; }
    public String getOutputData() { return outputData; }
    public void setOutputData(String outputData) { this.outputData = outputData; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getTokenInput() { return tokenInput; }
    public void setTokenInput(Integer tokenInput) { this.tokenInput = tokenInput; }
    public Integer getTokenOutput() { return tokenOutput; }
    public void setTokenOutput(Integer tokenOutput) { this.tokenOutput = tokenOutput; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
