package com.wikiagent.infrastructure.tool;

import com.wikiagent.domain.business.OrderInfo;
import org.springframework.stereotype.Component;

/**
 * 业务工具：查询订单信息（Mock）。
 * <p>
 * Mock 本体是纯确定性函数（同 orderNo 必得同输出，字节级可回放），不读外部系统、不写库。
 * 「未找到」约定见 {@link MockDataUtil#isNotFound}。
 */
@Component
public class QueryOrderTool {

    public static final String NAME = "query_order";

    /**
     * 按订单号查询订单信息（Mock 确定性派生）。
     *
     * @param orderNo 已通过槽位证据校验的订单号（大小写归一）
     * @return 订单信息；查无此单时 {@link OrderInfo#found()} = false
     */
    public OrderInfo execute(String orderNo) {
        String normalized = MockDataUtil.normalizeOrderNo(orderNo);
        if (normalized.isBlank()) {
            return OrderInfo.notFound("");
        }
        if (MockDataUtil.isNotFound(normalized)) {
            return OrderInfo.notFound(normalized);
        }
        String status = MockDataUtil.orderStatus(normalized);
        return new OrderInfo(
                normalized, true, status,
                "寄件人-" + tail(normalized), "收件人-" + tail(normalized),
                MockDataUtil.originCity(normalized), MockDataUtil.destCity(normalized),
                MockDataUtil.weightKg(normalized), MockDataUtil.formatEpoch(MockDataUtil.baseEpoch(normalized)));
    }

    private static String tail(String orderNo) {
        return orderNo.length() <= 4 ? orderNo : orderNo.substring(orderNo.length() - 4);
    }
}
