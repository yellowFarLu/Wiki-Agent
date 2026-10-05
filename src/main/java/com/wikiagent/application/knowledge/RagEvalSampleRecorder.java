package com.wikiagent.application.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalEntity;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalJpaDao;
import com.wikiagent.service.retrieve.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RAG 回答质量评测样本采集器（方案见 docs/rag-accuracy-eval.md）。
 * <p>
 * 由 ChatService 的 AnswerFinalizer 在答案经输出安全网关通过后回调（question/answer 均为
 * 脱敏后文本），落 {@code rag_answer_eval} PENDING 样本，等待 {@link RagAnswerJudgeService} 评判。
 * <p>
 * 可靠性契约：<b>本类任何异常不得影响对话主链路</b>——record 全方法 try-catch，失败仅 WARN。
 * {@code wikiagent.answer-eval.enabled=false} 时 Bean 不装配，看板如实显示暂无数据。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.answer-eval.enabled", havingValue = "true", matchIfMissing = true)
public class RagEvalSampleRecorder {

    private static final Logger log = LoggerFactory.getLogger(RagEvalSampleRecorder.class);

    private final RagAnswerEvalJpaDao dao;
    private final ObjectMapper objectMapper;
    private final double sampleRate;

    public RagEvalSampleRecorder(RagAnswerEvalJpaDao dao,
                                 ObjectMapper objectMapper,
                                 @Value("${wikiagent.answer-eval.sample-rate:1.0}") double sampleRate) {
        this.dao = dao;
        this.objectMapper = objectMapper;
        this.sampleRate = Math.max(0.0, Math.min(1.0, sampleRate));
    }

    /**
     * 采集一条评测样本。幂等：同会话同答案（SHA-256）只落一条。
     *
     * @param sources  SSE sources 事件的引用来源（父文档粒度）；闲聊/联网兜底等路径为 null
     * @param channel  回答链路：multi-agent / orchestrator-v1v2 / agent-rag / legacy-rag
     */
    public void record(String sessionId, String userId, String question, String answer,
                       List<RetrievalService.Source> sources, String channel) {
        try {
            if (sessionId == null || sessionId.isBlank()
                    || question == null || question.isBlank()
                    || answer == null || answer.isBlank()) {
                return;
            }
            if (sampleRate < 1.0 && ThreadLocalRandom.current().nextDouble() >= sampleRate) {
                return;
            }
            String answerHash = sha256Hex(answer);
            if (dao.existsBySessionIdAndAnswerHash(sessionId, answerHash)) {
                return;
            }
            RagAnswerEvalEntity e = new RagAnswerEvalEntity();
            e.setSessionId(sessionId);
            e.setUserId(userId);
            e.setQuestion(question);
            e.setAnswer(answer);
            e.setAnswerHash(answerHash);
            e.setSourceDocIds(toDistinctJson(sources));
            e.setChannel(channel);
            e.setStatus(RagAnswerEvalEntity.STATUS_PENDING);
            dao.save(e);
        } catch (Exception ex) {
            log.warn("RAG 评测样本采集失败（不影响对话）sessionId={}: {}", sessionId, ex.getMessage());
        }
    }

    /** docId 去重保序 → JSON 数组；无有效来源返回 null（诚实：该样本忠实度将无法评判）。 */
    private String toDistinctJson(List<RetrievalService.Source> sources) throws Exception {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> docIds = new LinkedHashSet<>();
        for (RetrievalService.Source s : sources) {
            if (s != null && s.docId() != null && !s.docId().isBlank()) {
                docIds.add(s.docId());
            }
        }
        return docIds.isEmpty() ? null : objectMapper.writeValueAsString(List.copyOf(docIds));
    }

    static String sha256Hex(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
