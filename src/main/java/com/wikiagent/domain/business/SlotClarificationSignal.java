package com.wikiagent.domain.business;

/**
 * 槽位澄清信号（神经符号硬闸的挂起点）：业务工具执行前发现必填槽缺失/无证据时抛出，
 * 由网关捕获后置 {@link DialogueStatus#WAITING_SLOT} 并推送 slot_request 事件，结束本轮。
 * <p>
 * 与 {@link com.wikiagent.domain.task.HumanRequiredException} 同风格，但不建人工任务，只挂起对话。
 * 必须由 {@code ReActExecutor} 在最前 catch 透传，不得被吞成 Observation 文本（对齐 LangGraph
 * "Do not wrap interrupt in try/except" 硬规则）。
 */
public class SlotClarificationSignal extends RuntimeException {

    private final String slot;
    private final String prompt;
    private final BusinessIntent intent;

    public SlotClarificationSignal(String slot, String prompt, BusinessIntent intent) {
        super("缺少槽位 " + slot + ": " + prompt);
        this.slot = slot;
        this.prompt = prompt;
        this.intent = intent;
    }

    public String slot() {
        return slot;
    }

    public String prompt() {
        return prompt;
    }

    public BusinessIntent intent() {
        return intent;
    }
}
