package com.wikiagent.application.business;

import com.wikiagent.config.BusinessIntentProperties;
import com.wikiagent.domain.business.BusinessIntent;
import com.wikiagent.domain.business.IntentSpec;
import com.wikiagent.domain.business.SlotSpec;
import com.wikiagent.domain.business.SlotValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SlotEvidenceValidator 单测：原文命中的单号置 CONFIRMED；LLM 脑补原文不存在的单号被拒绝。
 */
class SlotEvidenceValidatorTest {

    private SlotEvidenceValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SlotEvidenceValidator(new BusinessIntentProperties());
    }

    private static IntentSpec orderSpec() {
        return new IntentSpec(BusinessIntent.ORDER_QUERY.code(), "查订单", "business_query",
                List.of(SlotSpec.string("orderNo", "orderNo", "请提供订单号")), List.of("query_order"));
    }

    @Test
    void 原文命中订单号置CONFIRMED() {
        Map<String, SlotValue> slots = validator.confirmSlots(
                BusinessIntent.ORDER_QUERY, orderSpec(), Map.of(), "帮我查 SF1234567890", null, 1);
        assertThat(slots).containsKey("orderNo");
        assertThat(slots.get("orderNo").value()).isEqualTo("SF1234567890");
        assertThat(slots.get("orderNo").isConfirmed()).isTrue();
    }

    @Test
    void 模型脑补原文不存在的单号被拒绝() {
        Map<String, SlotValue> slots = validator.confirmSlots(
                BusinessIntent.ORDER_QUERY, orderSpec(), Map.of("orderNo", "SF99999999"), "帮我查订单", null, 1);
        assertThat(slots).doesNotContainKey("orderNo");
    }

    @Test
    void 纯字母单词不误判为单号() {
        assertThat(validator.extractOrderNo("请解释一下 knowledge 这个词")).isNull();
    }

    @Test
    void 短于8位或含非法字符的单号拒绝() {
        assertThat(validator.isValidOrderNo("SF123")).isFalse();
        assertThat(validator.isValidOrderNo("SF1234567890")).isTrue();
        assertThat(validator.isValidOrderNo("SF-1234567")).isFalse();
    }

    @Test
    void 上传附件置mappingExcel为CONFIRMED() {
        IntentSpec customs = new IntentSpec(BusinessIntent.CUSTOMS_GENERATE.code(), "生成清关", "customs_generate",
                List.of(SlotSpec.file("mappingExcel", "mappingExcel", "请上传映射表")), List.of("generate_customs_info"));
        Map<String, SlotValue> slots = validator.confirmSlots(
                BusinessIntent.CUSTOMS_GENERATE, customs, Map.of(), "帮我生成清关信息", "bf-abc", 1);
        assertThat(slots.get("mappingExcel").value()).isEqualTo("bf-abc");
        assertThat(slots.get("mappingExcel").isConfirmed()).isTrue();
    }
}
