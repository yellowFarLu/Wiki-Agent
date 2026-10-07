package com.wikiagent.application.business;

import com.wikiagent.config.BusinessIntentProperties;
import com.wikiagent.domain.business.BusinessIntent;
import com.wikiagent.domain.business.IntentSpec;
import com.wikiagent.domain.business.SlotEvidence;
import com.wikiagent.domain.business.SlotEvidenceSource;
import com.wikiagent.domain.business.SlotSpec;
import com.wikiagent.domain.business.SlotValue;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 槽位证据裁决器（神经符号硬闸 B）：LLM 提出的槽位值必须带证据才能置 CONFIRMED。
 * <ul>
 *   <li>orderNo：原文经确定性正则命中（RAW_HIT）；拒绝 LLM 转述值</li>
 *   <li>mappingExcel：本轮显式上传（USER_UPLOAD）</li>
 * </ul>
 * 无证据的槽位不置 CONFIRMED，交由网关追问。
 */
@Component
public class SlotEvidenceValidator {

    /** 订单号必须含至少一个数字，避免把普通英文单词误判为单号。 */
    private final Pattern orderNoFull;
    private final Pattern orderNoSearch;

    public SlotEvidenceValidator(BusinessIntentProperties props) {
        String full = props.getOrderNoRegex();
        this.orderNoFull = Pattern.compile(full);
        this.orderNoSearch = Pattern.compile(stripAnchors(full));
    }

    /**
     * 对意图的必填槽做证据裁决，返回 CONFIRMED 的槽位（无证据的不在此列）。
     *
     * @param intent           业务意图
     * @param proposed         LLM 提出的候选槽（可空，仅供参考）
     * @param rawInput         本轮原文
     * @param attachmentFileId 本轮上传的附件 fileId（可空）
     * @param turn             轮次（从 1 开始）
     */
    public Map<String, SlotValue> confirmSlots(BusinessIntent intent, IntentSpec spec,
                                               Map<String, String> proposed, String rawInput,
                                               String attachmentFileId, int turn) {
        Map<String, SlotValue> confirmed = new HashMap<>();
        if (spec == null) {
            return confirmed;
        }
        for (SlotSpec slot : spec.requiredSlots()) {
            SlotValue v = confirm(slot, proposed, rawInput, attachmentFileId, turn);
            if (v != null) {
                confirmed.put(slot.name(), v);
            }
        }
        return confirmed;
    }

    private SlotValue confirm(SlotSpec slot, Map<String, String> proposed,
                              String rawInput, String attachmentFileId, int turn) {
        if ("orderNo".equals(slot.validator())) {
            String hit = extractOrderNo(rawInput);
            if (hit != null) {
                return SlotValue.confirmed(hit, "string",
                        SlotEvidence.of(SlotEvidenceSource.RAW_HIT, turn, hit));
            }
            // LLM 转述值：仅当原文确实包含该值且合法时才接受（等效 RAW_HIT）
            String proposedVal = proposed == null ? null : proposed.get(slot.name());
            if (proposedVal != null && !proposedVal.isBlank()) {
                String norm = proposedVal.trim().toUpperCase();
                if (orderNoFull.matcher(norm).matches()
                        && rawInput != null && rawInput.toUpperCase().contains(norm)) {
                    return SlotValue.confirmed(norm, "string",
                            SlotEvidence.of(SlotEvidenceSource.RAW_HIT, turn, norm));
                }
            }
            return null;
        }
        if ("mappingExcel".equals(slot.validator())) {
            if (attachmentFileId != null && !attachmentFileId.isBlank()) {
                return SlotValue.confirmed(attachmentFileId, "file",
                        SlotEvidence.of(SlotEvidenceSource.USER_UPLOAD, turn, attachmentFileId));
            }
            return null;
        }
        return null;
    }

    /** 从原文确定性抽取订单号（正则命中 + 含数字），大小写归一。 */
    public String extractOrderNo(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return null;
        }
        Matcher m = orderNoSearch.matcher(rawInput);
        while (m.find()) {
            String cand = m.group().toUpperCase();
            if (cand.matches(".*[0-9].*") && orderNoFull.matcher(cand).matches()) {
                return cand;
            }
        }
        return null;
    }

    /** 校验一个值是否为合法订单号（供工具执行期 RAW_HIT 复检）。 */
    public boolean isValidOrderNo(String value) {
        return value != null && orderNoFull.matcher(value.trim().toUpperCase()).matches();
    }

    private static String stripAnchors(String regex) {
        String s = regex;
        if (s.startsWith("^")) {
            s = s.substring(1);
        }
        if (s.endsWith("$")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
