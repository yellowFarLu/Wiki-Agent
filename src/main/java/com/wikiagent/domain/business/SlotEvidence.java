package com.wikiagent.domain.business;

/**
 * 槽位证据戳：记录一个被 CONFIRMED 的槽位值从何而来。
 *
 * @param source 证据来源（RAW_HIT / CLARIFY_ANSWER / USER_UPLOAD）
 * @param turn   采集轮次（从 1 开始）
 * @param raw    命中的原文片段（RAW_HIT/CLARIFY_ANSWER 时非空；上传为 fileId）
 */
public record SlotEvidence(SlotEvidenceSource source, int turn, String raw) {

    public static SlotEvidence of(SlotEvidenceSource source, int turn, String raw) {
        return new SlotEvidence(source, turn, raw);
    }
}
