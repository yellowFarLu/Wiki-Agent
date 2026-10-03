package com.wikiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 子项目 B 文档智能解析配置（{@code wikiagent.parse}）。
 * 默认 provider=dashscope：无 API Key 时供应商 available=false，流水线自动跳过 AI 能力。
 */
@ConfigurationProperties(prefix = "wikiagent.parse")
public class ParseProperties {

    /** 供应商名称：dashscope（默认）| none（禁用全部 AI 能力）。 */
    private String provider = "dashscope";

    /** 上传文件大小上限（MB），超过按 VALIDATION_FAILED 处理。 */
    private int maxFileMb = 200;

    /** 判定扫描页的阈值：页提取文本字符数低于该值视为扫描页走 OCR。 */
    private int scannedPageCharThreshold = 20;

    /** 文本层 vs OCR 相似度低于该值（差异率超阈）→ 页面标 CONFLICT。 */
    private double textConflictSimilarity = 0.85;

    /** OCR 单次调用超时（秒）。 */
    private int ocrTimeoutSec = 30;

    /** 语音转写超时（秒，含轮询）。 */
    private int asrTimeoutSec = 120;

    /** 版面分析超时（秒）。 */
    private int layoutTimeoutSec = 30;

    /** 表格识别超时（秒）。 */
    private int tableTimeoutSec = 60;

    /** 总尝试次数（1 + 重试次数）；默认 3 = 重试 2 次。 */
    private int maxAttempts = 3;

    /** 重试基础退避（毫秒），指数递增（base * 2^(n-1)）。 */
    private long retryBackoffBaseMs = 500;

    /** 熔断打开时长（秒），与 Milvus 60s 熔断一致。 */
    private int circuitOpenSec = 60;

    /** 连续失败多少次后打开熔断。 */
    private int circuitFailureThreshold = 3;

    /** 视觉模型（OCR/版面/表格）。plus 比 max 快 2~3.5 倍且单价更低，中文印刷体 OCR 质量差异可忽略。 */
    private String visionModel = "qwen-vl-plus";

    /** 语音转写模型。 */
    private String asrModel = "paraformer-v2";

    /** ASR 文件公网访问基础 URL（如 OSS 前缀）；为空时本地文件无法转写。 */
    private String asrFileBaseUrl = "";

    /** DashScope 服务根地址。 */
    private String dashscopeBaseUrl = "https://dashscope.aliyuncs.com/api/v1";

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getMaxFileMb() {
        return maxFileMb;
    }

    public void setMaxFileMb(int maxFileMb) {
        this.maxFileMb = maxFileMb;
    }

    public int getScannedPageCharThreshold() {
        return scannedPageCharThreshold;
    }

    public void setScannedPageCharThreshold(int scannedPageCharThreshold) {
        this.scannedPageCharThreshold = scannedPageCharThreshold;
    }

    public double getTextConflictSimilarity() {
        return textConflictSimilarity;
    }

    public void setTextConflictSimilarity(double textConflictSimilarity) {
        this.textConflictSimilarity = textConflictSimilarity;
    }

    public int getOcrTimeoutSec() {
        return ocrTimeoutSec;
    }

    public void setOcrTimeoutSec(int ocrTimeoutSec) {
        this.ocrTimeoutSec = ocrTimeoutSec;
    }

    public int getAsrTimeoutSec() {
        return asrTimeoutSec;
    }

    public void setAsrTimeoutSec(int asrTimeoutSec) {
        this.asrTimeoutSec = asrTimeoutSec;
    }

    public int getLayoutTimeoutSec() {
        return layoutTimeoutSec;
    }

    public void setLayoutTimeoutSec(int layoutTimeoutSec) {
        this.layoutTimeoutSec = layoutTimeoutSec;
    }

    public int getTableTimeoutSec() {
        return tableTimeoutSec;
    }

    public void setTableTimeoutSec(int tableTimeoutSec) {
        this.tableTimeoutSec = tableTimeoutSec;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getRetryBackoffBaseMs() {
        return retryBackoffBaseMs;
    }

    public void setRetryBackoffBaseMs(long retryBackoffBaseMs) {
        this.retryBackoffBaseMs = retryBackoffBaseMs;
    }

    public int getCircuitOpenSec() {
        return circuitOpenSec;
    }

    public void setCircuitOpenSec(int circuitOpenSec) {
        this.circuitOpenSec = circuitOpenSec;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public String getAsrModel() {
        return asrModel;
    }

    public void setAsrModel(String asrModel) {
        this.asrModel = asrModel;
    }

    public String getAsrFileBaseUrl() {
        return asrFileBaseUrl;
    }

    public void setAsrFileBaseUrl(String asrFileBaseUrl) {
        this.asrFileBaseUrl = asrFileBaseUrl;
    }

    public String getDashscopeBaseUrl() {
        return dashscopeBaseUrl;
    }

    public void setDashscopeBaseUrl(String dashscopeBaseUrl) {
        this.dashscopeBaseUrl = dashscopeBaseUrl;
    }
}
