package com.wikiagent.domain.business;

/**
 * 轨迹节点（query_trajectory 工具返回结构）。
 *
 * @param time        节点时间（yyyy-MM-dd HH:mm，Mock 确定性反推、时间正序）
 * @param node        城市网点
 * @param action      动作（揽收 / 发出 / 到达 / 派送 / 签收）
 * @param description 描述
 */
public record TrajectoryNode(String time, String node, String action, String description) {
}
