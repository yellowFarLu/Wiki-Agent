package com.wikiagent.application.business;

import com.wikiagent.domain.business.BusinessIntent;
import com.wikiagent.domain.business.IntentSpec;
import com.wikiagent.domain.business.SlotSpec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 意图 schema 注册表：三类业务意图的槽位/节点类型/工具白名单集中定义。
 * 新增意图 = 注册一份 spec，不改状态机代码。
 */
@Component
public class IntentSpecRegistry {

    private final Map<String, IntentSpec> specs;

    public IntentSpecRegistry() {
        this.specs = Map.of(
                BusinessIntent.ORDER_QUERY.code(), new IntentSpec(
                        BusinessIntent.ORDER_QUERY.code(),
                        "查询订单信息：调用 query_order 获取订单详情，并据实回答",
                        "business_query",
                        List.of(SlotSpec.string("orderNo", "orderNo", "请提供订单号（8–20 位字母或数字）")),
                        List.of("query_order")),
                BusinessIntent.TRAJECTORY_QUERY.code(), new IntentSpec(
                        BusinessIntent.TRAJECTORY_QUERY.code(),
                        "查询物流轨迹：调用 query_trajectory 获取轨迹列表，并据实回答",
                        "business_query",
                        List.of(SlotSpec.string("orderNo", "orderNo", "请提供订单号（8–20 位字母或数字）")),
                        List.of("query_trajectory")),
                BusinessIntent.CUSTOMS_GENERATE.code(), new IntentSpec(
                        BusinessIntent.CUSTOMS_GENERATE.code(),
                        "生成清关信息：调用 generate_customs_info 基于上传的映射表生成清关 Excel",
                        "customs_generate",
                        List.of(SlotSpec.file("mappingExcel", "mappingExcel", "请上传包含「小包号、大包号」两列的映射 Excel")),
                        List.of("generate_customs_info")));
    }

    public IntentSpec spec(BusinessIntent intent) {
        return intent == null ? null : specs.get(intent.code());
    }

    public IntentSpec spec(String code) {
        return specs.get(code);
    }
}
