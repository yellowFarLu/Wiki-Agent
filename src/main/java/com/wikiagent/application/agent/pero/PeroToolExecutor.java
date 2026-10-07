package com.wikiagent.application.agent.pero;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.business.BusinessSlotGate;
import com.wikiagent.domain.business.OrderInfo;
import com.wikiagent.domain.business.TrajectoryNode;
import com.wikiagent.infrastructure.tool.GenerateCustomsInfoTool;
import com.wikiagent.infrastructure.tool.ListAbandonedPathTool;
import com.wikiagent.infrastructure.tool.QueryOrderTool;
import com.wikiagent.infrastructure.tool.QueryTrajectoryTool;
import com.wikiagent.infrastructure.tool.ReadHandoverTool;
import com.wikiagent.infrastructure.tool.SearchHistoryTool;
import com.wikiagent.infrastructure.tool.SearchKnowledgeBaseTool;
import com.wikiagent.infrastructure.tool.UpdateUserProfileTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * v6 PERO 路径真实 ToolExecutor（替换 §20 的 StubToolExecutor 占位）。
 * <p>
 * 将 {@link ReActAction}（name + args JSON）分派到八个真实工具组件：
 * 5 个既有工具（检索/历史/档案/交接）+ 3 个业务工具（订单/轨迹/清关，经 {@link BusinessSlotGate} 过槽位硬闸）。
 * <p>
 * 参数校验失败、JSON 解析失败、未知工具均以明确文本作为 Observation 返回，
 * 让 ReAct 循环据此前进或自愈，不抛异常、不返回桩文本。
 * <p>
 * 业务工具的 {@code SlotClarificationSignal} 原样穿出（由 ReActExecutor 透传、网关捕获），
 * 本类只 catch {@link IllegalArgumentException}（参数错误）不吞信号。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.pero.enabled", havingValue = "true", matchIfMissing = true)
public class PeroToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(PeroToolExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 已注册工具名（用于未知工具提示）。 */
    private static final List<String> KNOWN = List.of(
            SearchKnowledgeBaseTool.NAME, SearchHistoryTool.NAME, UpdateUserProfileTool.NAME,
            ReadHandoverTool.NAME, ListAbandonedPathTool.NAME,
            QueryOrderTool.NAME, QueryTrajectoryTool.NAME, GenerateCustomsInfoTool.NAME);

    private final SearchKnowledgeBaseTool searchKb;
    private final SearchHistoryTool searchHistory;
    private final UpdateUserProfileTool updateProfile;
    private final ReadHandoverTool readHandover;
    private final ListAbandonedPathTool listAbandoned;
    private final QueryOrderTool queryOrder;
    private final QueryTrajectoryTool queryTrajectory;
    private final GenerateCustomsInfoTool generateCustoms;
    /** 业务槽位硬闸（可选：缺省时业务工具按 args 直取，不做证据裁决）。 */
    private final BusinessSlotGate slotGate;

    public PeroToolExecutor(SearchKnowledgeBaseTool searchKb,
                            SearchHistoryTool searchHistory,
                            UpdateUserProfileTool updateProfile,
                            ReadHandoverTool readHandover,
                            ListAbandonedPathTool listAbandoned,
                            QueryOrderTool queryOrder,
                            QueryTrajectoryTool queryTrajectory,
                            GenerateCustomsInfoTool generateCustoms,
                            ObjectProvider<BusinessSlotGate> slotGateProvider) {
        this.searchKb = searchKb;
        this.searchHistory = searchHistory;
        this.updateProfile = updateProfile;
        this.readHandover = readHandover;
        this.listAbandoned = listAbandoned;
        this.queryOrder = queryOrder;
        this.queryTrajectory = queryTrajectory;
        this.generateCustoms = generateCustoms;
        this.slotGate = slotGateProvider == null ? null : slotGateProvider.getIfAvailable();
    }

    @Override
    public String invoke(ReActAction action, Perception ctx) {
        if (action == null || action.name() == null || action.name().isBlank()) {
            return "[tool_executor] 工具名为空，未执行";
        }
        String name = action.name().trim();
        Map<String, Object> args = parseArgs(action.args());
        try {
            String observation = switch (name) {
                case SearchKnowledgeBaseTool.NAME ->
                        searchKb.execute(requireText(args, "query", action.args()));
                case SearchHistoryTool.NAME ->
                        searchHistory.execute(requireText(args, "query", action.args()),
                                intArg(args, "topK", "top_k"));
                case UpdateUserProfileTool.NAME ->
                        updateProfile.execute(ctx.userId(),
                                requireKey(args, "key"), requireKey(args, "value"));
                case ReadHandoverTool.NAME ->
                        readHandover.execute(ctx.userId(), ctx.sessionId());
                case ListAbandonedPathTool.NAME ->
                        listAbandoned.execute(ctx.userId(), ctx.sessionId());
                case QueryOrderTool.NAME -> {
                    String orderNo = resolveSlotValue(QueryOrderTool.NAME, args, ctx, "orderNo");
                    yield renderOrder(queryOrder.execute(orderNo));
                }
                case QueryTrajectoryTool.NAME -> {
                    String orderNo = resolveSlotValue(QueryTrajectoryTool.NAME, args, ctx, "orderNo");
                    yield renderTrajectory(queryTrajectory.execute(orderNo));
                }
                case GenerateCustomsInfoTool.NAME -> {
                    String mappingFileId = resolveSlotValue(GenerateCustomsInfoTool.NAME, args, ctx, "mappingFileId");
                    String taskId = slotGate == null ? null : slotGate.currentTaskId(ctx);
                    yield generateCustoms.execute(mappingFileId, taskId);
                }
                default -> "[tool_executor] 未知工具: " + name + "（已注册: " + KNOWN + "）";
            };
            log.debug("PERO 工具调用 action={} userId={} 结果长度={}",
                    name, ctx.userId(), observation == null ? 0 : observation.length());
            return observation;
        } catch (IllegalArgumentException e) {
            log.info("PERO 工具参数校验失败 action={} userId={} err={}", name, ctx.userId(), e.getMessage());
            return "[tool_executor] 参数错误: " + e.getMessage();
        }
    }

    /** 业务工具：经槽位硬闸裁决必填槽值；硬闸缺失时按 args 直取。 */
    private String resolveSlotValue(String toolName, Map<String, Object> args, Perception ctx, String argKey) {
        if (slotGate != null) {
            return slotGate.resolveSlotValue(toolName, args, ctx);
        }
        String v = textArg(args, argKey);
        if (v == null) {
            throw new IllegalArgumentException(argKey + " 缺失");
        }
        return v;
    }

    private static String renderOrder(OrderInfo o) {
        if (!o.found()) {
            return "[query_order] 未查询到订单 " + o.orderNo() + "，请核对单号";
        }
        return "[query_order] 订单 " + o.orderNo() + "：状态=" + o.status()
                + "，寄件=" + o.sender() + "，收件=" + o.receiver()
                + "，路线=" + o.originCity() + "→" + o.destCity()
                + "，重量=" + o.weightKg() + "kg，创建时间=" + o.createdTime();
    }

    private static String renderTrajectory(List<TrajectoryNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "[query_trajectory] 未查询到订单轨迹，请核对单号";
        }
        StringBuilder sb = new StringBuilder("[query_trajectory] 轨迹列表：\n");
        for (TrajectoryNode n : nodes) {
            sb.append("- ").append(n.time()).append(" ").append(n.node())
                    .append(" ").append(n.action()).append("（").append(n.description()).append("）\n");
        }
        return sb.toString();
    }

    /**
     * 解析 args JSON 对象。
     *
     * @return 解析成功的键值对；空白返回空 Map；非 JSON 返回 null（供单参数工具回退原始文本）
     */
    private static Map<String, Object> parseArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> m = MAPPER.readValue(raw, new TypeReference<>() { });
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取必需文本参数。args 为 null（非 JSON）时把整个原始串作为参数值回退——
     * LLM 对单参数工具常直接输出裸文本。
     */
    private static String requireText(Map<String, Object> args, String key, String raw) {
        if (args != null) {
            Object v = args.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v).trim();
            }
        }
        if (args == null && raw != null && !raw.isBlank()) {
            return raw.trim();
        }
        throw new IllegalArgumentException(key + " 缺失（期望 JSON 参数，如 {\"" + key + "\":\"...\"}）");
    }

    /** 取必需键（无回退：结构化工具必须提供 JSON 参数）。 */
    private static String requireKey(Map<String, Object> args, String key) {
        if (args != null) {
            Object v = args.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v).trim();
            }
        }
        throw new IllegalArgumentException(
                key + " 缺失（update_user_profile 需要 JSON 参数 {\"key\":\"字段名\",\"value\":\"字段值\"}）");
    }

    /** 取可选整数参数（多键兼容），缺失或解析失败返回 0 由工具自身用默认值。 */
    private static int intArg(Map<String, Object> args, String... keys) {
        if (args == null) {
            return 0;
        }
        for (String k : keys) {
            Object v = args.get(k);
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v != null) {
                try {
                    return Integer.parseInt(String.valueOf(v).trim());
                } catch (NumberFormatException ignore) {
                    // 尝试下一个键
                }
            }
        }
        return 0;
    }

    /** 取可选文本参数（业务工具直取回退用）。 */
    private static String textArg(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isBlank() ? null : s;
    }
}
