package com.wikiagent.application.business;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对话行为闸（ActGate）：等待槽位态下优先解析「回答」而非重跑意图分类，解决「那是/对/嗯」被当新查询的问题。
 * <p>
 * 判定顺序（确定性，§2.2）：
 * <ol>
 *   <li>取消词（不查了/算了/换个问题）→ {@link ActGateAction#CANCEL}</li>
 *   <li>按 awaitingSlot 从原文/上传事件抽槽 → {@link ActGateAction#FILLED}</li>
 *   <li>抽不到 → {@link ActGateAction#NO_VALUE}</li>
 * </ol>
 */
@Component
public class ActGate {

    /** 取消词（用户明确终止当前业务任务）。 */
    private static final List<String> CANCEL_WORDS = List.of(
            "不查了", "算了", "换个问题", "取消", "不用了", "没事了", "不要了", "返回", "退出");

    public enum ActGateAction { CANCEL, FILLED, NO_VALUE }

    /** 等待态解析结果。 */
    public record ActGateResult(ActGateAction action, String slot, String value) {
        static ActGateResult cancel() { return new ActGateResult(ActGateAction.CANCEL, null, null); }
        static ActGateResult filled(String slot, String value) { return new ActGateResult(ActGateAction.FILLED, slot, value); }
        static ActGateResult noValue() { return new ActGateResult(ActGateAction.NO_VALUE, null, null); }
    }

    private final SlotEvidenceValidator validator;

    public ActGate(SlotEvidenceValidator validator) {
        this.validator = validator;
    }

    /**
     * 等待态解析本轮输入。
     *
     * @param awaitingSlot     等待的槽名（orderNo / mappingExcel）
     * @param rawInput         本轮原文
     * @param attachmentFileId 本轮上传附件 fileId（可空）
     */
    public ActGateResult evaluate(String awaitingSlot, String rawInput, String attachmentFileId) {
        if (isCancel(rawInput)) {
            return ActGateResult.cancel();
        }
        if ("orderNo".equals(awaitingSlot)) {
            String v = validator.extractOrderNo(rawInput);
            return v == null ? ActGateResult.noValue() : ActGateResult.filled("orderNo", v);
        }
        if ("mappingExcel".equals(awaitingSlot)) {
            return (attachmentFileId != null && !attachmentFileId.isBlank())
                    ? ActGateResult.filled("mappingExcel", attachmentFileId)
                    : ActGateResult.noValue();
        }
        return ActGateResult.noValue();
    }

    /** 是否命中取消词（等待态下用户想终止任务）。 */
    public boolean isCancel(String rawInput) {
        if (rawInput == null) {
            return false;
        }
        String s = rawInput.trim();
        for (String w : CANCEL_WORDS) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
