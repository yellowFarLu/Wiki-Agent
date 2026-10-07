package com.wikiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 业务意图网关配置（{@code wikiagent.business-intent}）。
 * <p>
 * 全部有默认值，缺省即可用；环境变量覆盖见 application.yml。
 */
@ConfigurationProperties(prefix = "wikiagent.business-intent")
public class BusinessIntentProperties {

    /** 总开关，默认 true。 */
    private boolean enabled = true;

    /** 意图置信度阈值：低于该值降级知识问答。 */
    private double confidenceThreshold = 0.8;

    /** 订单号正则（Mock 宽松口径：8–20 位字母或数字）。 */
    private String orderNoRegex = "^[A-Za-z0-9]{8,20}$";

    /** 连续追问失败上限，达到即转人工。 */
    private int maxClarifyRetries = 2;

    /** 幂等记录 TTL（小时）。 */
    private int idempotencyTtlHours = 24;

    /** 业务文件落盘根目录（相对项目根）。 */
    private String fileBaseDir = "data/business-files";

    /** 语义错误（JSON 畸形）带纠错重提示次数。 */
    private int rePromptMax = 1;

    /** 瞬态错误退避重试：base 延迟（毫秒）。 */
    private long retryBaseDelayMs = 1000;

    /** 瞬态错误退避重试：max 延迟（毫秒）。 */
    private long retryMaxDelayMs = 60000;

    /** 瞬态错误退避重试：最多重试次数。 */
    private int retryMaxAttempts = 2;

    /** 熔断器：连续失败阈值。 */
    private int circuitFailureThreshold = 5;

    /** 熔断器：OPEN 冷却秒数。 */
    private long circuitOpenSeconds = 30;

    /** Mock 业务网关默认授予的 scope（逗号分隔），保证权限门可放行。 */
    private List<String> defaultScopes = List.of("order:read", "customs:write");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }

    public String getOrderNoRegex() { return orderNoRegex; }
    public void setOrderNoRegex(String orderNoRegex) { this.orderNoRegex = orderNoRegex; }

    public int getMaxClarifyRetries() { return maxClarifyRetries; }
    public void setMaxClarifyRetries(int maxClarifyRetries) { this.maxClarifyRetries = maxClarifyRetries; }

    public int getIdempotencyTtlHours() { return idempotencyTtlHours; }
    public void setIdempotencyTtlHours(int idempotencyTtlHours) { this.idempotencyTtlHours = idempotencyTtlHours; }

    public String getFileBaseDir() { return fileBaseDir; }
    public void setFileBaseDir(String fileBaseDir) { this.fileBaseDir = fileBaseDir; }

    public int getRePromptMax() { return rePromptMax; }
    public void setRePromptMax(int rePromptMax) { this.rePromptMax = rePromptMax; }

    public long getRetryBaseDelayMs() { return retryBaseDelayMs; }
    public void setRetryBaseDelayMs(long retryBaseDelayMs) { this.retryBaseDelayMs = retryBaseDelayMs; }

    public long getRetryMaxDelayMs() { return retryMaxDelayMs; }
    public void setRetryMaxDelayMs(long retryMaxDelayMs) { this.retryMaxDelayMs = retryMaxDelayMs; }

    public int getRetryMaxAttempts() { return retryMaxAttempts; }
    public void setRetryMaxAttempts(int retryMaxAttempts) { this.retryMaxAttempts = retryMaxAttempts; }

    public int getCircuitFailureThreshold() { return circuitFailureThreshold; }
    public void setCircuitFailureThreshold(int circuitFailureThreshold) { this.circuitFailureThreshold = circuitFailureThreshold; }

    public long getCircuitOpenSeconds() { return circuitOpenSeconds; }
    public void setCircuitOpenSeconds(long circuitOpenSeconds) { this.circuitOpenSeconds = circuitOpenSeconds; }

    public List<String> getDefaultScopes() { return defaultScopes; }
    public void setDefaultScopes(List<String> defaultScopes) { this.defaultScopes = defaultScopes == null ? List.of() : defaultScopes; }
}
