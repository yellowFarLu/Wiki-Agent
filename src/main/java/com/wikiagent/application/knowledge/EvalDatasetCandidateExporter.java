package com.wikiagent.application.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.infrastructure.persistence.ChatHistoryEntity;
import com.wikiagent.infrastructure.persistence.ChatHistoryJpaDao;
import com.wikiagent.infrastructure.persistence.KbFeedbackJpaDao;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalEntity;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalJpaDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 阶段二：从生产日志只读导出 RAGAS 评测候选（NDJSON），方案见
 * docs/operations/eval-baseline.md §6.2 方式三。
 * <p>
 * 数据来源：rag_answer_eval（真实问答 + LLM-judge 困难标签）、chat_history（真实
 * 提问分布 + 问答配对）、kb_feedback（USELESS 困难信号）。选择逻辑在
 * {@link ProductionCandidateSelector}（纯函数可单测）。
 * <p>
 * 边界（诚实铁律）：
 * - 纯只读：不触发检索（避免 RETRIEVED/CITED 埋点污染看板指标）、不调 LLM、不写业务表；
 * - 候选 contexts/reference 留空、reviewStatus=pending，专家审核补标 ground truth 前
 *   不进基线；产物写 target/ragas-report/（gitignore），避免真实日志入库泄露；
 * - MySQL 不可用时查询直接抛异常，由接口层如实返回 500，不静默产出空集假装成功。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.ragas.enabled", havingValue = "true", matchIfMissing = true)
public class EvalDatasetCandidateExporter {

    private static final Logger log = LoggerFactory.getLogger(EvalDatasetCandidateExporter.class);
    private static final int MAX_FETCH_PAGE = 500;

    /** @param ndjson 与落盘文件内容一致的 NDJSON 文本；filePath 供下载/留档。 */
    public record ExportResult(String ndjson, int candidateCount, Path filePath,
                               int judgedConsidered, int chatConsidered,
                               int duplicateSkipped, int noAnswerSkipped,
                               int uselessFeedbackTagged, Instant windowStart, int days) {
    }

    private final RagAnswerEvalJpaDao answerEvalDao;
    private final ChatHistoryJpaDao chatHistoryDao;
    private final KbFeedbackJpaDao feedbackDao;
    private final ObjectMapper objectMapper;
    private final Path exportDir;

    public EvalDatasetCandidateExporter(RagAnswerEvalJpaDao answerEvalDao,
                                        ChatHistoryJpaDao chatHistoryDao,
                                        KbFeedbackJpaDao feedbackDao,
                                        ObjectMapper objectMapper,
                                        @Value("${wikiagent.ragas.export-dir:target/ragas-report}")
                                        String exportDir) {
        this.answerEvalDao = answerEvalDao;
        this.chatHistoryDao = chatHistoryDao;
        this.feedbackDao = feedbackDao;
        this.objectMapper = objectMapper;
        this.exportDir = Path.of(exportDir);
    }

    public ExportResult export(int days, int limit) {
        int safeDays = Math.max(1, days);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        Instant since = Instant.now().minusSeconds(86400L * safeDays);
        int fetchSize = Math.min(safeLimit * 3, MAX_FETCH_PAGE);

        // 1) rag_answer_eval 已评判（困难优先）
        List<RagAnswerEvalEntity> judgedEntities =
                answerEvalDao.findJudgedForCandidates(since, PageRequest.of(0, fetchSize));
        List<ProductionCandidateSelector.JudgeRecord> judged = new ArrayList<>();
        for (RagAnswerEvalEntity e : judgedEntities) {
            judged.add(new ProductionCandidateSelector.JudgeRecord(
                    e.getId(), e.getQuestion(), e.getAnswer(), e.getChannel(),
                    e.getFaithfulness(), e.getRelevance(), parseDocIds(e.getSourceDocIds()),
                    e.getCreatedAt()));
        }

        // 2) chat_history 用户消息 + 同会话 assistant 配对
        List<ChatHistoryEntity> userEntities =
                chatHistoryDao.findByRoleAndCreatedAtAfterOrderByCreatedAtDesc(
                        "user", since, PageRequest.of(0, fetchSize));
        List<ProductionCandidateSelector.ChatMessage> userMessages = new ArrayList<>();
        Set<String> sessionIds = new LinkedHashSet<>();
        for (ChatHistoryEntity e : userEntities) {
            userMessages.add(new ProductionCandidateSelector.ChatMessage(
                    e.getId(), e.getSessionId(), e.getRole(), e.getContent(), e.getCreatedAt()));
            sessionIds.add(e.getSessionId());
        }
        List<ProductionCandidateSelector.ChatMessage> assistantMessages = new ArrayList<>();
        if (!sessionIds.isEmpty()) {
            for (ChatHistoryEntity e : chatHistoryDao
                    .findBySessionIdInAndRoleOrderByCreatedAtAsc(new ArrayList<>(sessionIds), "assistant")) {
                assistantMessages.add(new ProductionCandidateSelector.ChatMessage(
                        e.getId(), e.getSessionId(), e.getRole(), e.getContent(), e.getCreatedAt()));
            }
        }

        // 3) USELESS 反馈困难信号（chunk_id 列实际存 docId 归属键）
        Set<String> uselessDocIds = new LinkedHashSet<>(
                feedbackDao.findUselessChunkIdsSince(since));

        ProductionCandidateSelector.Selection selection = ProductionCandidateSelector.select(
                judged, userMessages, assistantMessages, uselessDocIds, safeLimit);

        String ndjson = toNdjson(selection.candidates());
        String fileName = "candidates-production-"
                + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.ZonedDateTime.now())
                + ".jsonl";
        Path filePath = exportDir.resolve(fileName);
        writeFile(filePath, ndjson);
        log.info("生产评测候选导出: {} 条（judged 扫描 {}/chat 扫描 {}/去重跳过 {}/无答案跳过 {}"
                        + "/USELESS 标注 {}）→ {}",
                selection.candidates().size(), selection.judgedConsidered(),
                selection.chatConsidered(), selection.duplicateSkipped(),
                selection.noAnswerSkipped(), selection.uselessFeedbackTagged(), filePath);

        return new ExportResult(ndjson, selection.candidates().size(), filePath,
                selection.judgedConsidered(), selection.chatConsidered(),
                selection.duplicateSkipped(), selection.noAnswerSkipped(),
                selection.uselessFeedbackTagged(), since, safeDays);
    }

    /** source_doc_ids 是 JSON 数组字符串（如 ["d1","d2"]）；null/解析失败 → 空集。 */
    @SuppressWarnings("unchecked")
    private Set<String> parseDocIds(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, List.class);
            Set<String> ids = new LinkedHashSet<>();
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    ids.add(o.toString());
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("source_doc_ids 解析失败，按无来源处理: {}", e.getMessage());
            return Set.of();
        }
    }

    private String toNdjson(List<ProductionCandidateSelector.Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        try {
            for (ProductionCandidateSelector.Candidate c : candidates) {
                sb.append(objectMapper.writeValueAsString(c)).append('\n');
            }
        } catch (Exception e) {
            throw new IllegalStateException("候选 NDJSON 序列化失败: " + e.getMessage(), e);
        }
        return sb.toString();
    }

    private void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("候选文件写入失败 " + path + ": " + e.getMessage(), e);
        }
    }
}
