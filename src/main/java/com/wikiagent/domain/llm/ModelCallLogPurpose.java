package com.wikiagent.domain.llm;

/**
 * 模型调用打点用途枚举。
 */
public enum ModelCallLogPurpose {
    /** 规则确定性计算用途标记（AC-D3：规则引擎零 LLM，正常计算不应产生该打点）。 */
    INTENT, EXTRACT, CHAT, RERANK, JUDGE, RULED,
    /** ConflictGuard 精判（qwen-flash 结构化冲突判定，Task 6）。 */
    CONFLICT_JUDGE;

    public static ModelCallLogPurpose from(String raw) {
        if (raw == null) {
            return null;
        }
        for (ModelCallLogPurpose p : values()) {
            if (p.name().equalsIgnoreCase(raw)) {
                return p;
            }
        }
        return null;
    }
}
