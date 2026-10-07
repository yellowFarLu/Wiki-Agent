package com.wikiagent.domain.business;

/**
 * 槽位证据来源（神经符号硬闸的核心）：LLM 提出的值必须带证据才能置 CONFIRMED。
 * <ul>
 *   <li>{@link #RAW_HIT} — 当前输入原文被确定性正则命中（不接受 LLM 转述值）</li>
 *   <li>{@link #CLARIFY_ANSWER} — 等待槽位态下由回答解析器从原文抽到</li>
 *   <li>{@link #USER_UPLOAD} — 用户经上传控件显式上传且文件校验通过</li>
 * </ul>
 */
public enum SlotEvidenceSource {
    RAW_HIT,
    CLARIFY_ANSWER,
    USER_UPLOAD
}
