package com.wikiagent.domain.business;

import java.util.Map;

/**
 * 意图分类结果（IntentClassifier 输出）。
 *
 * @param intent         业务意图；null 表示知识问答/无法识别
 * @param confidence     置信度 [0,1]
 * @param proposedSlots  LLM 提出的候选槽位（键为槽名，值为原文值；未经证据校验）
 * @param reason         分类理由（可观测）
 * @param fallback       是否触发降级（低置信/解析失败/无 Key 等）
 * @param fallbackReason 降级原因（intent_fallback 打点，如 no_key/llm_error/parse_failed/low_confidence）
 */
public record Classification(BusinessIntent intent, double confidence,
                             Map<String, String> proposedSlots, String reason,
                             boolean fallback, String fallbackReason) {

    public static Classification knowledgeQa(String reason) {
        return new Classification(null, 0d, Map.of(), reason, false, null);
    }

    public static Classification fallback(String fallbackReason) {
        return new Classification(null, 0d, Map.of(), null, true, fallbackReason);
    }
}
