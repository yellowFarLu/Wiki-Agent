package com.wikiagent.infrastructure.tool;

import com.wikiagent.domain.business.TrajectoryNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务工具：查询轨迹列表（Mock）。
 * <p>
 * 按 orderNo 哈希确定性生成 4–6 条时间正序节点，与 {@link QueryOrderTool} 的状态保持一致（同哈希种子）。
 * 纯函数，字节级可回放。
 */
@Component
public class QueryTrajectoryTool {

    public static final String NAME = "query_trajectory";

    private static final String[] ACTIONS = {"揽收", "发出", "到达", "派送", "签收"};

    /**
     * 查询轨迹列表。
     *
     * @param orderNo 已通过槽位证据校验的订单号
     * @return 时间正序的轨迹节点；查无此单时返回空列表
     */
    public List<TrajectoryNode> execute(String orderNo) {
        String normalized = MockDataUtil.normalizeOrderNo(orderNo);
        if (normalized.isBlank() || MockDataUtil.isNotFound(normalized)) {
            return List.of();
        }
        int n = MockDataUtil.trajectoryNodeCount(normalized);
        long base = MockDataUtil.baseEpoch(normalized);
        String origin = MockDataUtil.originCity(normalized);
        String dest = MockDataUtil.destCity(normalized);
        List<TrajectoryNode> nodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String action = i == 0 ? ACTIONS[0]
                    : i == n - 1 ? ACTIONS[ACTIONS.length - 1]
                    : ACTIONS[1 + (i % (ACTIONS.length - 2))];
            String city = i == 0 ? origin : i == n - 1 ? dest
                    : MockDataUtil.CITIES.get((MockDataUtil.pick(normalized, MockDataUtil.CITIES.size()) + i) % MockDataUtil.CITIES.size());
            nodes.add(new TrajectoryNode(
                    MockDataUtil.formatEpoch(base + i * 5L * 3600_000L),
                    city + "网点",
                    action,
                    "快件" + (i == 0 ? "已收件" : action + "于" + city + "网点")));
        }
        return nodes;
    }
}
