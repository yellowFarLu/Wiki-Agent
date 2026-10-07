package com.wikiagent.infrastructure.tool;

import com.wikiagent.domain.business.OrderInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryOrderTool 确定性单测：同订单号两次结果完全一致；查无此单返回 notFound。
 */
class QueryOrderToolTest {

    private final QueryOrderTool tool = new QueryOrderTool();

    @Test
    void 同订单号两次结果完全一致() {
        OrderInfo a = tool.execute("SF1234567890");
        OrderInfo b = tool.execute("SF1234567890");
        assertThat(a).isEqualTo(b);
        assertThat(a.found()).isTrue();
        assertThat(a.orderNo()).isEqualTo("SF1234567890");
    }

    @Test
    void 订单号大小写归一为大写() {
        OrderInfo a = tool.execute("sf1234567890");
        assertThat(a.orderNo()).isEqualTo("SF1234567890");
        assertThat(a.found()).isTrue();
    }

    @Test
    void 未找到约定前缀NF返回查无此单() {
        OrderInfo a = tool.execute("NF00000001");
        assertThat(a.found()).isFalse();
    }

    @Test
    void 空单号返回查无此单() {
        assertThat(tool.execute("").found()).isFalse();
        assertThat(tool.execute(null).found()).isFalse();
    }

    @Test
    void 派生字段非空() {
        OrderInfo a = tool.execute("SF1234567890");
        assertThat(a.status()).isNotBlank();
        assertThat(a.originCity()).isNotBlank();
        assertThat(a.destCity()).isNotBlank();
        assertThat(a.weightKg()).isGreaterThan(0d);
    }
}
