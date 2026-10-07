package com.wikiagent.domain.business;

/**
 * 槽位信封：无模式字典中的单个槽位值，统一带类型、生命周期、证据戳与更新时间。
 * 任何意图的参数都是「槽名 → 槽位信封」，新增意图不改状态结构。
 *
 * @param value     槽位值（订单号字符串 / 文件 fileId 等）
 * @param type      值类型（"string" / "file"），供校验与渲染
 * @param lifecycle 生命周期（PROPOSED / CONFIRMED / VOIDED）
 * @param evidence  证据戳（CONFIRMED 时非空）
 * @param updatedAt 更新时间（epoch millis）
 */
public record SlotValue(String value, String type, SlotLifecycle lifecycle,
                        SlotEvidence evidence, long updatedAt) {

    /** LLM 提议的候选值（未经验证，不参与必填槽判定）。 */
    public static SlotValue proposed(String value, String type) {
        return new SlotValue(value, type, SlotLifecycle.PROPOSED, null, System.currentTimeMillis());
    }

    /** 通过证据校验后置 CONFIRMED 的值。 */
    public static SlotValue confirmed(String value, String type, SlotEvidence evidence) {
        return new SlotValue(value, type, SlotLifecycle.CONFIRMED, evidence, System.currentTimeMillis());
    }

    /** 是否已通过证据校验（必填槽只认 CONFIRMED）。 */
    public boolean isConfirmed() {
        return lifecycle == SlotLifecycle.CONFIRMED;
    }

    /** 重新标记为 CONFIRMED（证据变更/复用覆盖时）。 */
    public SlotValue withConfirmed(SlotEvidence newEvidence) {
        return new SlotValue(value, type, SlotLifecycle.CONFIRMED, newEvidence, System.currentTimeMillis());
    }
}
