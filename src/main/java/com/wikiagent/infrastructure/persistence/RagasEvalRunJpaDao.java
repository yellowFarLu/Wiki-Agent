package com.wikiagent.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** RAGAS 评测执行记录 DAO（只读查询为主，写入在编排服务内）。 */
public interface RagasEvalRunJpaDao extends JpaRepository<RagasEvalRunEntity, Long> {

    Optional<RagasEvalRunEntity> findByRunId(String runId);

    /** 历史执行列表：最新在前。 */
    List<RagasEvalRunEntity> findTop100ByOrderByCreatedAtDesc();

    /** 指定状态全部行（启动恢复用）。 */
    List<RagasEvalRunEntity> findByStatus(String status);

    /** 单飞检查：是否存在执行中的 run。 */
    Optional<RagasEvalRunEntity> findFirstByStatusOrderByCreatedAtDesc(String status);

    /** 看板 KPI：最近一次执行（任意终态）。 */
    Optional<RagasEvalRunEntity> findFirstByStatusNotOrderByCreatedAtDesc(String status);
}
