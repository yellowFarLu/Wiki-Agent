package com.wikiagent.domain.business;

import java.util.HashMap;
import java.util.Map;

/**
 * 通用结构化对话状态（Schema-driven Dialogue State）。
 * <p>
 * 与具体业务解耦：意图 + 无模式槽位字典 + 等待槽 + 轮次。业务差异全部收敛到 {@link IntentSpec} 注册表，
 * 新增意图只加一份 spec，不改状态结构与状态机代码。
 * <p>
 * 这是「归约态」（可覆盖、可重建），不是审计/回放真相源——回放真相源是 V24 业务表 + agent_trace 事件流水。
 * <p>
 * 存储：{@link DialogueStateStore}（Redis 单 key JSON，TTL 24h；可退化进程内 Map）。
 *
 * @param taskId 当前业务任务对外 ID（biz_task_id，供产物/审计软关联；IDLE 时为 null）
 */
public record DialogueState(
        int version,
        String userId,
        String sessionId,
        DialogueStatus status,
        BusinessIntent activeIntent,
        double confidence,
        Map<String, SlotValue> slots,
        Awaiting awaiting,
        int turn,
        String rawInput,
        String attachmentFileId,
        String taskId,
        long updatedAt) {

    /** 等待槽位描述（ActGate 据此决定本轮输入按哪个槽的解析器处理）。 */
    public record Awaiting(String slot, String prompt, int retry) {
        public Awaiting withRetry(int newRetry) {
            return new Awaiting(slot, prompt, newRetry);
        }
    }

    /** 新建空闲态（无业务任务）。 */
    public static DialogueState idle(String userId, String sessionId) {
        return new DialogueState(1, userId, sessionId, DialogueStatus.IDLE, null, 0d,
                new HashMap<>(), null, 0, null, null, null, System.currentTimeMillis());
    }

    public boolean isWaitingSlot() {
        return status == DialogueStatus.WAITING_SLOT;
    }

    /** 取某槽位的 CONFIRMED 值；无则返回 null。 */
    public SlotValue confirmedSlot(String name) {
        SlotValue v = slots == null ? null : slots.get(name);
        return v != null && v.isConfirmed() ? v : null;
    }

    /** 写入/覆盖一个槽位（返回新实例，保持不可变语义）。 */
    public DialogueState withSlot(String name, SlotValue value) {
        Map<String, SlotValue> next = slots == null ? new HashMap<>() : new HashMap<>(slots);
        next.put(name, value);
        return new DialogueState(version, userId, sessionId, status, activeIntent, confidence,
                next, awaiting, turn, rawInput, attachmentFileId, taskId, updatedAt);
    }

    public DialogueState withStatus(DialogueStatus newStatus) {
        return new DialogueState(version, userId, sessionId, newStatus, activeIntent, confidence,
                slots, awaiting, turn, rawInput, attachmentFileId, taskId, System.currentTimeMillis());
    }

    public DialogueState withActiveIntent(BusinessIntent intent, double conf) {
        return new DialogueState(version, userId, sessionId, status, intent, conf,
                slots, awaiting, turn, rawInput, attachmentFileId, taskId, System.currentTimeMillis());
    }

    public DialogueState withAwaiting(Awaiting newAwaiting) {
        return new DialogueState(version, userId, sessionId, status, activeIntent, confidence,
                slots, newAwaiting, turn, rawInput, attachmentFileId, taskId, System.currentTimeMillis());
    }

    public DialogueState withTurn(int newTurn) {
        return new DialogueState(version, userId, sessionId, status, activeIntent, confidence,
                slots, awaiting, newTurn, rawInput, attachmentFileId, taskId, System.currentTimeMillis());
    }

    /** 记录本轮原文与附件（供工具执行时的 RAW_HIT / USER_UPLOAD 证据裁决）。 */
    public DialogueState withInput(String newRawInput, String newAttachmentFileId) {
        return new DialogueState(version, userId, sessionId, status, activeIntent, confidence,
                slots, awaiting, turn, newRawInput, newAttachmentFileId, taskId, System.currentTimeMillis());
    }

    public DialogueState withTaskId(String newTaskId) {
        return new DialogueState(version, userId, sessionId, status, activeIntent, confidence,
                slots, awaiting, turn, rawInput, attachmentFileId, newTaskId, System.currentTimeMillis());
    }

    /** 状态机前进后版本 +1（供 CAS 乐观锁）。 */
    public DialogueState bumpVersion() {
        return new DialogueState(version + 1, userId, sessionId, status, activeIntent, confidence,
                slots, awaiting, turn, rawInput, attachmentFileId, taskId, System.currentTimeMillis());
    }
}
