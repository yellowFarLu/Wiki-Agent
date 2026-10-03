package com.wikiagent.application.knowledge.dedup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键 token 判定器：在 LSH 相似文本的 diff 区间内检测数字、日期、货币、否定词。
 */
class KeyTokenDifferTest {

    @Test
    void 数字差异应判定为关键差异() {
        assertThat(KeyTokenDiffer.hasCriticalDiff("苹果是5元", "苹果是3元")).isTrue();
    }

    @Test
    void 否定词差异应判定为关键差异() {
        assertThat(KeyTokenDiffer.hasCriticalDiff("可以申请", "不可以申请")).isTrue();
    }

    @Test
    void 非关键差异应返回false() {
        assertThat(KeyTokenDiffer.hasCriticalDiff("请及时处理", "请尽快处理")).isFalse();
    }

    @Test
    void 日期差异应判定为关键差异() {
        assertThat(KeyTokenDiffer.hasCriticalDiff("活动于2024年1月1日开始", "活动于2025年1月1日开始")).isTrue();
        assertThat(KeyTokenDiffer.hasCriticalDiff("截止日期2024-12-31", "截止日期2025-12-31")).isTrue();
    }

    @Test
    void 货币量词差异应判定为关键差异() {
        assertThat(KeyTokenDiffer.hasCriticalDiff("赔偿5万元", "赔偿3万元")).isTrue();
        assertThat(KeyTokenDiffer.hasCriticalDiff("预算1亿元", "预算2亿元")).isTrue();
    }

    @Test
    void 百分号差异应判定为关键差异() {
        assertThat(KeyTokenDiffer.hasCriticalDiff("利率为5%", "利率为3%")).isTrue();
    }

    @Test
    void 小数差异应判定为关键差异() {
        assertThat(KeyTokenDiffer.hasCriticalDiff("利率为3.5%", "利率为2.5%")).isTrue();
    }

    @Test
    void 相同文本无差异应返回false() {
        assertThat(KeyTokenDiffer.hasCriticalDiff("苹果是5元", "苹果是5元")).isFalse();
    }

    @Test
    void 空文本与null应安全处理() {
        assertThat(KeyTokenDiffer.hasCriticalDiff(null, null)).isFalse();
        assertThat(KeyTokenDiffer.hasCriticalDiff("", "")).isFalse();
        assertThat(KeyTokenDiffer.hasCriticalDiff("abc", null)).isFalse();
    }
}
