package com.wikiagent.domain.business;

/**
 * 槽位值生命周期（对标 Rasa CALM {@code from_llm} + slot mapping 裁决 / Amazon Lex confirmationState）：
 * LLM 提出的值先落 {@link #PROPOSED}，通过证据校验升级 {@link #CONFIRMED}，无证据/被更正则 {@link #VOIDED}。
 * 必填槽检查只认 {@link #CONFIRMED}——「LLM 只提议、代码才裁决」在数据结构上的落点。
 */
public enum SlotLifecycle {
    PROPOSED,
    CONFIRMED,
    VOIDED
}
