package com.wikiagent.domain.business;

/**
 * 意图 schema 注册表中的一个槽位规格。
 *
 * @param name       槽位名（orderNo / mappingExcel）
 * @param type       值类型（"string" / "file"）
 * @param required   是否必填
 * @param validator  指向通用校验器键（"orderNo" / "mappingExcel"）
 * @param askPrompt  缺槽追问文案（可被 LLM 拟文案覆盖）
 */
public record SlotSpec(String name, String type, boolean required,
                       String validator, String askPrompt) {

    public static SlotSpec string(String name, String validator, String askPrompt) {
        return new SlotSpec(name, "string", true, validator, askPrompt);
    }

    public static SlotSpec file(String name, String validator, String askPrompt) {
        return new SlotSpec(name, "file", true, validator, askPrompt);
    }
}
