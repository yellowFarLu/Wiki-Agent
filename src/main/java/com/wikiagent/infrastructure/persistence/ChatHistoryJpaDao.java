package com.wikiagent.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;


/**
 * 对话历史 DAO。
 */
public interface ChatHistoryJpaDao extends JpaRepository<ChatHistoryEntity, Long> {

    /** 按会话 ID 查询全部消息（按时间升序）。 */
    List<ChatHistoryEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** 阶段二生产候选导出：窗口内用户消息（时间倒序），用于抽取真实提问分布。 */
    List<ChatHistoryEntity> findByRoleAndCreatedAtAfterOrderByCreatedAtDesc(
            String role, Instant since, Pageable pageable);

    /** 阶段二生产候选导出：批量取指定会话的某角色消息（问答配对用）。 */
    List<ChatHistoryEntity> findBySessionIdInAndRoleOrderByCreatedAtAsc(
            Collection<String> sessionIds, String role);

    /**
     * 列出所有去重的会话 ID（按最近活跃时间降序）。
     * 用 GROUP BY + 聚合排序，兼容 MySQL ONLY_FULL_GROUP_BY：
     * 不能对 DISTINCT 列直接 ORDER BY 非选择列 created_at。
     */
    @Query("SELECT h.sessionId FROM ChatHistoryEntity h GROUP BY h.sessionId ORDER BY MAX(h.createdAt) DESC")
    List<String> findDistinctSessionIds();
}
