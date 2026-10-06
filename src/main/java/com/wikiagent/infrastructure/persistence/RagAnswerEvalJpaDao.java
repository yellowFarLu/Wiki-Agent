package com.wikiagent.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * RAG 回答评测样本 DAO（V18）。
 * <p>
 * count 派生查询的 null 语义（诚实分母）：JPA {@code IsNotNull} 只统计非 null 行，
 * {@code countByFaithfulness(Integer)} 传 null 时按 {@code faithfulness IS NULL} 匹配——
 * 因此"已评判"分母一律用 IsNotNull 系列，不使用传 null 的 count。
 */
public interface RagAnswerEvalJpaDao extends JpaRepository<RagAnswerEvalEntity, Long> {

    boolean existsBySessionIdAndAnswerHash(String sessionId, String answerHash);

    List<RagAnswerEvalEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    /**
     * 阶段二生产候选导出：窗口内已评判样本，困难样本（faithfulness/relevance 任一为 0）
     * 优先、其余按时间倒序。调用方应放大 pageSize 以抵消归一化去重损耗。
     */
    @Query("SELECT e FROM RagAnswerEvalEntity e WHERE e.status = 'JUDGED' AND e.createdAt >= :since "
            + "ORDER BY CASE WHEN (e.faithfulness = 0 OR e.relevance = 0) THEN 0 ELSE 1 END ASC, "
            + "e.createdAt DESC")
    List<RagAnswerEvalEntity> findJudgedForCandidates(@Param("since") Instant since, Pageable pageable);

    long countByStatus(String status);

    long countByFaithfulnessIsNotNull();

    long countByRelevanceIsNotNull();

    long countByFaithfulnessIsNotNullAndRelevanceIsNotNull();

    long countByFaithfulness(Integer faithfulness);

    long countByRelevance(Integer relevance);

    long countByFaithfulnessAndRelevance(Integer faithfulness, Integer relevance);
}
