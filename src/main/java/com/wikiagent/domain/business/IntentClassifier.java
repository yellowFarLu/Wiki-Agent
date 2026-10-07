package com.wikiagent.domain.business;

/**
 * 业务意图分类端口。实现负责：LLM 结构化输出 {intent, confidence, slots, reason}；
 * 失败/低置信/非业务意图时返回 {@link Classification#fallback} 或 {@link Classification#knowledgeQa}，
 * 由网关确定性降级到知识问答（用户无感）。
 */
public interface IntentClassifier {

    /**
     * 对用户输入做意图分类。
     *
     * @param rawInput 本轮用户原文（已过输入安全网关）
     * @return 分类结果；业务意图命中时含 proposedSlots（未经证据校验）
     */
    Classification classify(String rawInput);
}
