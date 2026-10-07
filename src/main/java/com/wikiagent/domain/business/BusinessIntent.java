package com.wikiagent.domain.business;

import java.util.Optional;

/**
 * 业务意图：问题分析阶段识别出的用户业务诉求。
 * 作为有界神经符号 ReAct 架构的状态枚举/意图标识使用。
 */
public enum BusinessIntent {

    /** 查询订单信息 */
    ORDER_QUERY("order_query", "查询订单信息"),
    /** 查询轨迹信息 */
    TRAJECTORY_QUERY("trajectory_query", "查询轨迹"),
    /** 生成清关信息 */
    CUSTOMS_GENERATE("customs_generate", "生成清关信息");

    private final String code;
    private final String label;

    BusinessIntent(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 机器可读 code，持久化/跨系统引用用。 */
    public String code() {
        return code;
    }

    /** 中文展示名。 */
    public String label() {
        return label;
    }

    public static Optional<BusinessIntent> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase();
        for (BusinessIntent i : values()) {
            if (i.code.equals(normalized)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }
}
