package com.wikiagent.application.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.infrastructure.persistence.ChatHistoryEntity;
import com.wikiagent.infrastructure.persistence.ChatHistoryJpaDao;
import com.wikiagent.infrastructure.persistence.KbFeedbackJpaDao;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalEntity;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalJpaDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 阶段二导出器编排测试：DAO 行 → 候选 NDJSON 落盘（Mockito 桩 DAO，真实 ObjectMapper/文件系统）。
 * 抽样/去重/配对的纯逻辑见 ProductionCandidateSelectorTest。
 */
class EvalDatasetCandidateExporterTest {

    private @TempDir Path tempDir;

    private final RagAnswerEvalJpaDao answerEvalDao = mock(RagAnswerEvalJpaDao.class);
    private final ChatHistoryJpaDao chatHistoryDao = mock(ChatHistoryJpaDao.class);
    private final KbFeedbackJpaDao feedbackDao = mock(KbFeedbackJpaDao.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private EvalDatasetCandidateExporter newExporter() {
        return new EvalDatasetCandidateExporter(answerEvalDao, chatHistoryDao, feedbackDao,
                objectMapper, tempDir.toString());
    }

    private RagAnswerEvalEntity judged(long id, String question, String answer,
                                       Integer faith, Integer relev, String docIdsJson) {
        RagAnswerEvalEntity e = new RagAnswerEvalEntity();
        e.setId(id);
        e.setQuestion(question);
        e.setAnswer(answer);
        e.setChannel("agent-rag");
        e.setFaithfulness(faith);
        e.setRelevance(relev);
        e.setSourceDocIds(docIdsJson);
        e.setCreatedAt(Instant.parse("2026-10-05T10:00:00Z"));
        return e;
    }

    private ChatHistoryEntity chat(long id, String session, String role,
                                   String content, LocalDateTime at) {
        // ChatHistoryEntity 生产侧为不可变风格（无 setter），测试用反射补齐主键与时间
        ChatHistoryEntity e = new ChatHistoryEntity(session, role, content);
        ReflectionTestUtils.setField(e, "id", id);
        ReflectionTestUtils.setField(e, "createdAt", at);
        return e;
    }

    @Test
    void 导出judged与chat两类候选并落盘ndjson() throws Exception {
        when(answerEvalDao.findJudgedForCandidates(any(), any())).thenReturn(List.of(
                judged(7, "出口退税发票怎么冲红", "先开红字信息表", 0, 0, "[\"doc-9\"]")));
        when(chatHistoryDao.findByRoleAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("user"), any(), any())).thenReturn(List.of(
                chat(100, "s1", "user", "海关编码归类错了能改吗",
                        LocalDateTime.parse("2026-10-05T09:00:00"))));
        when(chatHistoryDao.findBySessionIdInAndRoleOrderByCreatedAtAsc(
                anyCollection(), eq("assistant"))).thenReturn(List.of(
                chat(101, "s1", "assistant", "放行后可申请修撤",
                        LocalDateTime.parse("2026-10-05T09:00:05"))));
        when(feedbackDao.findUselessChunkIdsSince(any())).thenReturn(List.of("doc-9"));

        EvalDatasetCandidateExporter.ExportResult result = newExporter().export(30, 50);

        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.judgedConsidered()).isEqualTo(1);
        assertThat(result.chatConsidered()).isEqualTo(1);
        assertThat(result.uselessFeedbackTagged()).isEqualTo(1);
        assertThat(result.filePath()).exists();
        // 落盘内容与返回文本完全一致
        assertThat(Files.readString(result.filePath())).isEqualTo(result.ndjson());

        JsonNode[] lines = result.ndjson().lines().map(this::readTree).toArray(JsonNode[]::new);
        JsonNode rae = lines[0];
        assertThat(rae.get("id").asText()).isEqualTo("prod-rae-7");
        assertThat(rae.get("source").asText()).isEqualTo("production");
        assertThat(rae.get("reviewStatus").asText()).isEqualTo("pending");
        assertThat(rae.get("answerOrigin").asText()).isEqualTo("production");
        assertThat(rae.get("reference").asText()).isEmpty();
        assertThat(rae.get("contexts").isArray()).isTrue();
        assertThat(rae.get("contexts").size()).isZero();
        assertThat(rae.get("sourceRef").asText()).isEqualTo("rag_answer_eval#7");
        assertThat(toStringList(rae.get("tags"))).contains("hard-negative:faithfulness",
                "hard-negative:relevance", "hard-negative:useless-feedback", "channel:agent-rag");

        JsonNode chat = lines[1];
        assertThat(chat.get("id").asText()).isEqualTo("prod-chat-100");
        assertThat(chat.get("answer").asText()).isEqualTo("放行后可申请修撤");
        assertThat(toStringList(chat.get("tags"))).containsExactly("channel:chat-history");
    }

    @Test
    void sourceDocIds为非法json时按空集处理不阻断导出() throws Exception {
        when(answerEvalDao.findJudgedForCandidates(any(), any())).thenReturn(List.of(
                judged(8, "报关单随附单证要求", "需合同发票装箱单", 1, 1, "not-a-json")));
        when(chatHistoryDao.findByRoleAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("user"), any(), any())).thenReturn(List.of());
        when(feedbackDao.findUselessChunkIdsSince(any())).thenReturn(List.of());

        EvalDatasetCandidateExporter.ExportResult result = newExporter().export(30, 50);
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.uselessFeedbackTagged()).isZero();
    }

    @Test
    void limit与days参数钳制不抛异常() {
        when(answerEvalDao.findJudgedForCandidates(any(), any())).thenReturn(List.of());
        when(chatHistoryDao.findByRoleAndCreatedAtAfterOrderByCreatedAtDesc(
                eq("user"), any(), any())).thenReturn(List.of());
        when(feedbackDao.findUselessChunkIdsSince(any())).thenReturn(List.of());

        EvalDatasetCandidateExporter.ExportResult result = newExporter().export(-9, 9999);
        assertThat(result.days()).isGreaterThanOrEqualTo(1);
        assertThat(result.candidateCount()).isZero();
    }

    private JsonNode readTree(String s) {
        try {
            return objectMapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> toStringList(JsonNode array) {
        return objectMapper.convertValue(array,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }
}
