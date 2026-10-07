package com.wikiagent.application.business;

import com.wikiagent.application.agent.pero.Perception;
import com.wikiagent.domain.business.BusinessIntent;
import com.wikiagent.domain.business.DialogueState;
import com.wikiagent.domain.business.DialogueStateStore;
import com.wikiagent.domain.business.IntentSpec;
import com.wikiagent.domain.business.SlotClarificationSignal;
import com.wikiagent.domain.business.SlotEvidence;
import com.wikiagent.domain.business.SlotEvidenceSource;
import com.wikiagent.domain.business.SlotValue;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 业务槽位硬闸（神经符号闸 B）：业务工具执行前裁决槽位证据，LLM 无法绕过。
 * <p>
 * 裁决顺序（§4.2）：
 * <ol>
 *   <li>RAW_HIT：args 的单号在本轮原文中确定性正则命中 → 接受并更新状态（允许「再查 SF999」）</li>
 *   <li>USER_UPLOAD：本轮带附件（mappingExcel）→ 接受</li>
 *   <li>状态复用：args 为空但状态有同槽 CONFIRMED 值 → 复用（跨工具/组合意图）</li>
 *   <li>以上皆否（含模型脑补值）→ 抛 {@link SlotClarificationSignal}（网关追问）</li>
 * </ol>
 * 通过后用 CONFIRMED 值覆盖模型入参（模型改不了真实入参）。
 */
@Component
public class BusinessSlotGate {

    private final DialogueStateStore store;
    private final IntentSpecRegistry registry;
    private final SlotEvidenceValidator validator;

    public BusinessSlotGate(DialogueStateStore store, IntentSpecRegistry registry,
                            SlotEvidenceValidator validator) {
        this.store = store;
        this.registry = registry;
        this.validator = validator;
    }

    /**
     * 解析业务工具必填槽的 CONFIRMED 值；无法裁决时抛 {@link SlotClarificationSignal}。
     */
    public String resolveSlotValue(String toolName, Map<String, Object> args, Perception ctx) {
        String slotName = slotFor(toolName);
        DialogueState state = load(ctx);
        BusinessIntent intent = state == null ? null : state.activeIntent();
        IntentSpec spec = intent == null ? null : registry.spec(intent);

        if ("orderNo".equals(slotName)) {
            String proposed = textArg(args, "orderNo");
            // ② RAW_HIT：模型给的单号在本轮原文中确定性命中（允许「再查 SF999」）
            if (proposed != null && validator.isValidOrderNo(proposed)
                    && state != null && state.rawInput() != null
                    && state.rawInput().toUpperCase().contains(proposed.toUpperCase())) {
                String norm = proposed.trim().toUpperCase();
                update(state, slotName, SlotValue.confirmed(norm, "string",
                        SlotEvidence.of(SlotEvidenceSource.RAW_HIT, state.turn(), norm)));
                return norm;
            }
            // ③ 状态复用
            SlotValue confirmed = state == null ? null : state.confirmedSlot("orderNo");
            if (confirmed != null) {
                return confirmed.value();
            }
            throw new SlotClarificationSignal("orderNo", askPrompt(spec, "orderNo"), intent);
        }

        if ("mappingExcel".equals(slotName)) {
            // ① USER_UPLOAD：本轮上传
            if (state != null && state.attachmentFileId() != null && !state.attachmentFileId().isBlank()) {
                return state.attachmentFileId();
            }
            // ③ 状态复用
            SlotValue confirmed = state == null ? null : state.confirmedSlot("mappingExcel");
            if (confirmed != null) {
                return confirmed.value();
            }
            throw new SlotClarificationSignal("mappingExcel", askPrompt(spec, "mappingExcel"), intent);
        }

        throw new SlotClarificationSignal(slotName, "缺少参数", intent);
    }

    /** 当前业务任务 biz_task_id（无则 null）。 */
    public String currentTaskId(Perception ctx) {
        DialogueState state = load(ctx);
        return state == null ? null : state.taskId();
    }

    private DialogueState load(Perception ctx) {
        if (ctx == null) {
            return null;
        }
        Optional<DialogueState> s = store.load(ctx.userId(), ctx.sessionId());
        return s.orElse(null);
    }

    private void update(DialogueState state, String slotName, SlotValue value) {
        store.save(state.withSlot(slotName, value));
    }

    private String slotFor(String toolName) {
        return switch (toolName) {
            case "query_order", "query_trajectory" -> "orderNo";
            case "generate_customs_info" -> "mappingExcel";
            default -> toolName;
        };
    }

    private String askPrompt(IntentSpec spec, String slotName) {
        if (spec != null && spec.slot(slotName) != null) {
            return spec.slot(slotName).askPrompt();
        }
        return "请提供 " + slotName;
    }

    private static String textArg(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isBlank() ? null : s;
    }
}
