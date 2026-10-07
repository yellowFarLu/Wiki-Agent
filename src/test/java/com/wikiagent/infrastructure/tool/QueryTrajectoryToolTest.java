package com.wikiagent.infrastructure.tool;

import com.wikiagent.domain.business.TrajectoryNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryTrajectoryTool 确定性单测：同订单号两次一致；节点数 4–6 且时间正序；查无此单为空。
 */
class QueryTrajectoryToolTest {

    private final QueryTrajectoryTool tool = new QueryTrajectoryTool();

    @Test
    void 同订单号两次结果完全一致() {
        assertThat(tool.execute("SF1234567890")).isEqualTo(tool.execute("SF1234567890"));
    }

    @Test
    void 节点数在4到6之间且时间正序() {
        List<TrajectoryNode> nodes = tool.execute("SF1234567890");
        assertThat(nodes).hasSizeBetween(4, 6);
        for (int i = 1; i < nodes.size(); i++) {
            assertThat(nodes.get(i - 1).time()).isLessThanOrEqualTo(nodes.get(i).time());
        }
    }

    @Test
    void 查无此单返回空列表() {
        assertThat(tool.execute("NF00000001")).isEmpty();
        assertThat(tool.execute("")).isEmpty();
    }
}
