package com.wikiagent.domain.business;

/**
 * 通用对话状态机状态（Rasa / Dialogflow CX / Amazon Lex 通用形态）。
 * <ul>
 *   <li>{@link #IDLE} — 无进行中的业务任务（本轮走意图分类）</li>
 *   <li>{@link #ACTIVE} — 已进入业务意图，ReAct 执行中</li>
 *   <li>{@link #WAITING_SLOT} — 缺必填槽，已追问，等用户下一轮补齐（ActGate 直接解析回答）</li>
 *   <li>{@link #DONE} — Agent 出 FINAL，任务完成（清状态回 IDLE）</li>
 *   <li>{@link #CANCELLED} — 用户取消或高置信切换意图，旧任务废弃</li>
 * </ul>
 */
public enum DialogueStatus {
    IDLE,
    ACTIVE,
    WAITING_SLOT,
    DONE,
    CANCELLED
}
