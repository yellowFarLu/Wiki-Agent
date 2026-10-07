package com.wikiagent.domain.business;

/**
 * 订单信息（query_order 工具返回结构，Mock 派生，不伪装成真实数据）。
 *
 * @param orderNo    订单号（归一化大写）
 * @param found      是否查询到（Mock 阶段几乎恒 true；契约保留 found=false 表示"未找到"）
 * @param status     状态（已揽收 / 干线运输 / 派送中 / 已签收）
 * @param sender     寄件方
 * @param receiver   收件方
 * @param originCity 始发城市
 * @param destCity   目的城市
 * @param weightKg   重量（kg，一位小数）
 * @param createdTime 创建时间（yyyy-MM-dd HH:mm，Mock 确定性反推）
 */
public record OrderInfo(String orderNo, boolean found, String status,
                        String sender, String receiver, String originCity,
                        String destCity, double weightKg, String createdTime) {

    public static OrderInfo notFound(String orderNo) {
        return new OrderInfo(orderNo, false, null, null, null, null, null, 0d, null);
    }
}
