package com.wikiagent.application.business;

import com.wikiagent.config.BusinessIntentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ActGate 单测：等待态取消词 / 订单号入槽 / 上传入槽 / 无法解析。
 */
class ActGateTest {

    private ActGate actGate;

    @BeforeEach
    void setUp() {
        actGate = new ActGate(new SlotEvidenceValidator(new BusinessIntentProperties()));
    }

    @Test
    void 取消词命中() {
        assertThat(actGate.isCancel("不查了")).isTrue();
        assertThat(actGate.evaluate("orderNo", "算了", null).action()).isEqualTo(ActGate.ActGateAction.CANCEL);
    }

    @Test
    void 订单号从原文解析入槽() {
        ActGate.ActGateResult r = actGate.evaluate("orderNo", "单号是 SF1234567890", null);
        assertThat(r.action()).isEqualTo(ActGate.ActGateAction.FILLED);
        assertThat(r.value()).isEqualTo("SF1234567890");
    }

    @Test
    void 无值返回NO_VALUE() {
        assertThat(actGate.evaluate("orderNo", "嗯", null).action()).isEqualTo(ActGate.ActGateAction.NO_VALUE);
    }

    @Test
    void 上传附件入mappingExcel槽() {
        ActGate.ActGateResult r = actGate.evaluate("mappingExcel", "这是我的文件", "bf-123");
        assertThat(r.action()).isEqualTo(ActGate.ActGateAction.FILLED);
        assertThat(r.slot()).isEqualTo("mappingExcel");
        assertThat(r.value()).isEqualTo("bf-123");
    }
}
