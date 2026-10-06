package com.wikiagent.application.knowledge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段二生产候选选择器纯逻辑测试（无 Spring/中间件）。
 */
class ProductionCandidateSelectorTest {

    private final Instant now = Instant.parse("2026-10-05T10:00:00Z");

    private ProductionCandidateSelector.JudgeRecord judged(
            long id, String q, String a, Integer faith, Integer relev, Set<String> docs) {
        return new ProductionCandidateSelector.JudgeRecord(
                id, q, a, "agent-rag", faith, relev, docs, now);
    }

    private ProductionCandidateSelector.ChatMessage msg(
            long id, String session, String role, String content, String t) {
        return new ProductionCandidateSelector.ChatMessage(
                id, session, role, content, LocalDateTime.parse(t));
    }

    @Test
    void judged_candidates_carry_real_qa_and_pending_provenance() {
        var selection = ProductionCandidateSelector.select(
                List.of(judged(7, "发票怎么开", "在线申请", 1, 1, Set.of())),
                List.of(), List.of(), Set.of(), 50);

        assertEquals(1, selection.candidates().size());
        var c = selection.candidates().get(0);
        assertEquals("prod-rae-7", c.id());
        assertEquals("发票怎么开", c.question());
        assertEquals("在线申请", c.answer());
        assertEquals("production", c.source());
        assertEquals("pending", c.reviewStatus());
        assertEquals("production", c.answerOrigin());
        assertEquals("rag_answer_eval#7", c.sourceRef());
        // ground truth 与 contexts 必须留空待专家补标，绝不反推伪造
        assertTrue(c.contexts().isEmpty());
        assertEquals("", c.reference());
        assertTrue(c.tags().contains("channel:agent-rag"));
    }

    @Test
    void failed_judge_samples_tagged_hard_negative_and_stay_first() {
        var hard = judged(1, "难题", "错答", 0, 0, Set.of());
        var clean = judged(2, "易题", "对答", 1, 1, Set.of());
        var selection = ProductionCandidateSelector.select(List.of(hard, clean),
                List.of(), List.of(), Set.of(), 50);

        assertEquals("prod-rae-1", selection.candidates().get(0).id());
        var tags = selection.candidates().get(0).tags();
        assertTrue(tags.contains("hard-negative:faithfulness"));
        assertTrue(tags.contains("hard-negative:relevance"));
        assertFalse(selection.candidates().get(1).tags().stream()
                .anyMatch(t -> t.startsWith("hard-negative")));
    }

    @Test
    void useless_feedback_doc_match_tags_candidate() {
        var selection = ProductionCandidateSelector.select(
                List.of(judged(3, "问题", "答案", 1, 1, Set.of("doc-9"))),
                List.of(), List.of(), Set.of("doc-9", "doc-x"), 50);
        assertTrue(selection.candidates().get(0).tags()
                .contains("hard-negative:useless-feedback"));
        assertEquals(1, selection.uselessFeedbackTagged());
    }

    @Test
    void chat_history_pairs_user_with_next_assistant_message() {
        var users = List.of(msg(100, "s1", "user", "退税怎么办", "2026-10-05T09:00:00"));
        var assistants = List.of(
                msg(101, "s1", "assistant", "先冲红再重开", "2026-10-05T09:00:05"),
                msg(102, "s1", "assistant", "后续追问回答", "2026-10-05T09:01:00"));
        var selection = ProductionCandidateSelector.select(
                List.of(), users, assistants, Set.of(), 50);

        assertEquals(1, selection.candidates().size());
        var c = selection.candidates().get(0);
        assertEquals("prod-chat-100", c.id());
        assertEquals("先冲红再重开", c.answer());  // 只取紧随其后的第一条
        assertTrue(c.tags().contains("channel:chat-history"));
    }

    @Test
    void chat_without_answer_is_skipped_and_counted() {
        var users = List.of(msg(100, "s1", "user", "没人理我", "2026-10-05T09:00:00"));
        var selection = ProductionCandidateSelector.select(
                List.of(), users, List.of(), Set.of(), 50);
        assertTrue(selection.candidates().isEmpty());
        assertEquals(1, selection.chatConsidered());
        assertEquals(1, selection.noAnswerSkipped());
    }

    @Test
    void dedup_is_cross_source_and_judged_wins() {
        var judged = judged(1, " 发票   怎么开 ", "judged答案", 1, 1, Set.of());
        var users = List.of(msg(100, "s1", "user", "发票 怎么开", "2026-10-05T09:00:00"));
        var assistants = List.of(msg(101, "s1", "assistant", "chat答案",
                "2026-10-05T09:00:05"));
        var selection = ProductionCandidateSelector.select(
                List.of(judged), users, assistants, Set.of(), 50);

        assertEquals(1, selection.candidates().size());
        assertEquals("prod-rae-1", selection.candidates().get(0).id());
        assertEquals(1, selection.duplicateSkipped());
    }

    @Test
    void limit_truncates_with_judged_priority() {
        var judged = List.of(
                judged(1, "问题一", "答案一", 1, 1, Set.of()),
                judged(2, "问题二", "答案二", 1, 1, Set.of()));
        var users = List.of(msg(100, "s1", "user", "chat问题", "2026-10-05T09:00:00"));
        var assistants = List.of(msg(101, "s1", "assistant", "chat答案",
                "2026-10-05T09:00:05"));
        var selection = ProductionCandidateSelector.select(
                judged, users, assistants, Set.of(), 2);
        assertEquals(2, selection.candidates().size());
        assertEquals("prod-rae-1", selection.candidates().get(0).id());
        assertEquals("prod-rae-2", selection.candidates().get(1).id());
    }

    @Test
    void chat_fills_limit_after_judged_exhausted() {
        var judged = List.of(judged(1, "judged问题", "a1", 1, 1, Set.of()));
        var users = List.of(msg(100, "s1", "user", "chat问题", "2026-10-05T09:00:00"));
        var assistants = List.of(msg(101, "s1", "assistant", "chat答案",
                "2026-10-05T09:00:05"));
        var selection = ProductionCandidateSelector.select(
                judged, users, assistants, Set.of(), 10);
        assertEquals(2, selection.candidates().size());
        assertEquals(1, selection.judgedConsidered());
        assertEquals(1, selection.chatConsidered());
    }

    @Test
    void normalized_key_collapses_whitespace_and_lowercases() {
        assertEquals("发票 怎么开", ProductionCandidateSelector.normalizedKey("  发票   怎么开 "));
        assertEquals("abc", ProductionCandidateSelector.normalizedKey("ABC"));
    }

    @Test
    void judged_row_without_answer_skipped() {
        var selection = ProductionCandidateSelector.select(
                List.of(judged(1, "问题", "  ", 1, 1, Set.of())),
                List.of(), List.of(), Set.of(), 50);
        assertTrue(selection.candidates().isEmpty());
        assertEquals(1, selection.noAnswerSkipped());
    }
}
