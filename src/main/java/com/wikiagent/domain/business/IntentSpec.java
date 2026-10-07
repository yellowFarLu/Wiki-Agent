package com.wikiagent.domain.business;

import java.util.List;

/**
 * 意图 schema（注册表条目）：定义一类业务意图的目标、节点类型、槽位与可用工具。
 * 业务差异全部收敛到这里，新增意图 = 注册一份 spec，不改状态机代码。
 *
 * @param code     意图码（BusinessIntent.code()）
 * @param goal     传给 ReAct 节点的自然语言目标
 * @param stepType 节点类型（business_query / customs_generate），决定工具白名单
 * @param slots    槽位规格（含必填槽）
 * @param tools    该意图允许的工具名列表
 */
public record IntentSpec(String code, String goal, String stepType,
                         List<SlotSpec> slots, List<String> tools) {

    /** 必填槽列表（required=true）。 */
    public List<SlotSpec> requiredSlots() {
        return slots == null ? List.of() : slots.stream().filter(SlotSpec::required).toList();
    }

    /** 按名找槽位规格。 */
    public SlotSpec slot(String name) {
        if (slots == null) {
            return null;
        }
        return slots.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }
}
