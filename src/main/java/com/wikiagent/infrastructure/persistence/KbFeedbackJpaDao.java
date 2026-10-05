package com.wikiagent.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * v4 §17 用户反馈 JPA DAO。
 */
public interface KbFeedbackJpaDao extends JpaRepository<KbFeedbackEntity, Long> {

    List<KbFeedbackEntity> findByChunkId(String chunkId);

    List<KbFeedbackEntity> findBySessionId(String sessionId);

    @Query("SELECT f.feedbackType, COUNT(f) FROM KbFeedbackEntity f WHERE f.chunkId = :chunkId GROUP BY f.feedbackType")
    List<Object[]> countByChunkIdGroupByType(@Param("chunkId") String chunkId);

    /**
     * 全量 chunk 级反馈分组统计（会话级反馈 chunkId 为 null，不计入）。
     * 每行：[chunkId, feedbackType, count]。有用/无用口径以 kb_feedback 表为准。
     */
    @Query("SELECT f.chunkId, f.feedbackType, COUNT(f) FROM KbFeedbackEntity f "
            + "WHERE f.chunkId IS NOT NULL GROUP BY f.chunkId, f.feedbackType")
    List<Object[]> countGroupedByChunkAndType();

    /**
     * 窗口内全部反馈（含会话级）按类型计数，用于看板原始计数与检索计数同窗口展示。
     * 每行：[feedbackType, count]。
     */
    @Query("SELECT f.feedbackType, COUNT(f) FROM KbFeedbackEntity f "
            + "WHERE f.createdAt >= :since GROUP BY f.feedbackType")
    List<Object[]> countByCreatedAtAfterGroupByType(@Param("since") Instant since);

    /**
     * 窗口内 chunk 级反馈（相关性标签）按类型计数，用于带标签检索精确率：
     * 会话级反馈（chunkId 为 null）标注的是整条答案而非检索项相关性，不计入。
     * 每行：[feedbackType, count]。
     */
    @Query("SELECT f.feedbackType, COUNT(f) FROM KbFeedbackEntity f "
            + "WHERE f.chunkId IS NOT NULL AND f.createdAt >= :since GROUP BY f.feedbackType")
    List<Object[]> countChunkLevelByCreatedAtAfterGroupByType(@Param("since") Instant since);
}
