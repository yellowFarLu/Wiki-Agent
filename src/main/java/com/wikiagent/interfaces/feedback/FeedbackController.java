package com.wikiagent.interfaces.feedback;

import com.wikiagent.infrastructure.persistence.KbFeedbackEntity;
import com.wikiagent.infrastructure.persistence.KbFeedbackJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v4 §17 用户反馈 Controller（"有用 / 无用" 按钮）。
 * <p>
 * POST /api/feedback          — 记录用户反馈
 * GET  /api/feedback/{chunkId} — 获取知识 chunk 的反馈统计
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final KbFeedbackJpaDao feedbackDao;
    private final MetricEventJpaDao metricEventDao;

    public FeedbackController(KbFeedbackJpaDao feedbackDao, MetricEventJpaDao metricEventDao) {
        this.feedbackDao = feedbackDao;
        this.metricEventDao = metricEventDao;
    }

    /**
     * 记录用户反馈。
     * POST /api/feedback
     * Body: { "userId", "sessionId", "conversationId", "chunkId", "feedbackType": "USEFUL|USELESS", "comment" }
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> recordFeedback(@RequestBody Map<String, Object> body) {
        String feedbackType = (String) body.get("feedbackType");
        if (!"USEFUL".equals(feedbackType) && !"USELESS".equals(feedbackType)) {
            throw new IllegalArgumentException("feedbackType 必须为 USEFUL 或 USELESS");
        }
        String userId = (String) body.getOrDefault("userId", "anonymous");
        String sessionId = (String) body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "web-" + System.currentTimeMillis();
        }
        // conversation_id 非空约束：缺省时与 sessionId 对齐
        String conversationId = (String) body.get("conversationId");
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = sessionId;
        }

        KbFeedbackEntity entity = new KbFeedbackEntity();
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        entity.setConversationId(conversationId);
        entity.setChunkId((String) body.get("chunkId"));
        entity.setFeedbackType(feedbackType);
        entity.setFeedbackComment((String) body.get("comment"));
        feedbackDao.save(entity);

        // 同时记录指标事件——metric_event 为 chunk 级指标（chunk_id 非空），
        // 会话级反馈（无 chunkId）只入 kb_feedback，不伪造 chunk 维度指标。
        String chunkId = (String) body.get("chunkId");
        if (chunkId != null && !chunkId.isBlank()) {
            MetricEventEntity metric = new MetricEventEntity();
            metric.setChunkId(chunkId);
            metric.setEventType("USEFUL".equals(feedbackType) ? "FEEDBACK_USEFUL" : "FEEDBACK_USELESS");
            metric.setUserId(userId);
            metric.setSessionId(sessionId);
            metricEventDao.save(metric);
        }

        log.info("用户反馈记录: chunk={}, type={}", body.get("chunkId"), feedbackType);
        return ResponseEntity.ok(Map.of("status", "recorded", "feedbackId", entity.getId()));
    }

    /**
     * 获取知识 chunk 的反馈统计。
     * GET /api/feedback/{chunkId}
     */
    @GetMapping("/{chunkId}")
    public ResponseEntity<Map<String, Object>> getFeedbackStats(@PathVariable String chunkId) {
        List<KbFeedbackEntity> feedbacks = feedbackDao.findByChunkId(chunkId);
        long useful = feedbacks.stream().filter(f -> "USEFUL".equals(f.getFeedbackType())).count();
        long useless = feedbacks.stream().filter(f -> "USELESS".equals(f.getFeedbackType())).count();

        // 仅返回有用/无用原始计数，不再提供易被挑战的"有用率"比率；
        // 疑似无用知识（无用占比>50%）统一由知识看板聚合输出。
        Map<String, Object> stats = new HashMap<>();
        stats.put("chunkId", chunkId);
        stats.put("totalFeedback", feedbacks.size());
        stats.put("usefulCount", useful);
        stats.put("uselessCount", useless);
        return ResponseEntity.ok(stats);
    }
}
