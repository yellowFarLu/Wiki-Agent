package com.wikiagent.application.knowledge;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阶段二生产日志候选选择器（纯逻辑，无 Spring/中间件依赖，便于单测）。
 * <p>
 * 候选两来源（docs/operations/eval-baseline.md §6.2 方式三）：
 * 1. rag_answer_eval 已评判样本：携带真实 question + 真实 answer，judge 失败样本
 *    （faithfulness/relevance=0）由 DAO 困难优先排序，隐式标注 hard-negative；
 * 2. chat_history 真实用户提问：与同会话紧随其后的 assistant 消息配对补 answer，
 *    反映真实提问分布（口语化/错别字/边界外问题）。
 * <p>
 * 诚实铁律：
 * - 导出候选 contexts/reference 一律留空，reviewStatus=pending——生产日志只提供
 *   真实问题与真实回答，ground truth 与上下文必须由领域专家审核补标（方式三原意），
 *   绝不用 answer 反推伪造 reference；
 * - 无法配对 answer 的用户提问不导出（无 answer 的样本无法评任何 RAGAS 指标）；
 * - 归一化去重跨两来源生效，同一问法只保留最有价值的一条（judged 优先于 chat）。
 */
public final class ProductionCandidateSelector {

    public static final String SOURCE_PRODUCTION = "production";
    public static final String STATUS_PENDING = "pending";
    public static final String DIFFICULTY_SIMPLE = "simple";
    public static final String ANSWER_ORIGIN_PRODUCTION = "production";

    private ProductionCandidateSelector() {
    }

    /** rag_answer_eval 行的轻量投影（entity → record 映射在 exporter 完成）。 */
    public record JudgeRecord(long id, String question, String answer, String channel,
                              Integer faithfulness, Integer relevance,
                              Set<String> sourceDocIds, Instant createdAt) {
    }

    /** chat_history 行的轻量投影。 */
    public record ChatMessage(long id, String sessionId, String role, String content,
                              LocalDateTime createdAt) {
    }

    /** 导出的评测候选（字段与 eval/ragas/dataset_io.py schema 逐一对齐）。 */
    public record Candidate(
            String id, String domain, String question, List<String> contexts,
            String answer, String reference, String source, String difficulty,
            String reviewStatus, String answerOrigin, String sourceRef,
            List<String> tags, String note) {
    }

    /** 选择结果：候选（保序，judged 困难优先在前）+ 各环节计数（供导出清单诚实展示）。 */
    public record Selection(List<Candidate> candidates, int judgedConsidered,
                            int chatConsidered, int duplicateSkipped,
                            int noAnswerSkipped, int uselessFeedbackTagged) {
    }

    /** 归一化：去首尾空白、折叠连续空白、小写；用于跨来源去重（不修改原 question 文本）。 */
    static String normalizedKey(String question) {
        if (question == null) {
            return "";
        }
        return question.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    public static Selection select(List<JudgeRecord> judged,
                                   List<ChatMessage> userMessages,
                                   List<ChatMessage> assistantMessages,
                                   Set<String> uselessDocIds,
                                   int limit) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int duplicateSkipped = 0;
        int noAnswerSkipped = 0;
        int uselessTagged = 0;

        // 1) rag_answer_eval（DAO 已按 hard-negative 优先排序，保序）
        int judgedConsidered = 0;
        for (JudgeRecord j : judged) {
            judgedConsidered++;
            String key = normalizedKey(j.question());
            if (key.length() < 2 || isBlank(j.answer())) {
                noAnswerSkipped++;
                continue;
            }
            if (!seen.add(key)) {
                duplicateSkipped++;
                continue;
            }
            List<String> tags = new ArrayList<>();
            if (j.faithfulness() != null && j.faithfulness() == 0) {
                tags.add("hard-negative:faithfulness");
            }
            if (j.relevance() != null && j.relevance() == 0) {
                tags.add("hard-negative:relevance");
            }
            if (j.sourceDocIds() != null && uselessDocIds != null) {
                for (String docId : j.sourceDocIds()) {
                    if (docId != null && uselessDocIds.contains(docId)) {
                        tags.add("hard-negative:useless-feedback");
                        uselessTagged++;
                        break;
                    }
                }
            }
            if (!isBlank(j.channel())) {
                tags.add("channel:" + j.channel().trim());
            }
            candidates.add(new Candidate(
                    "prod-rae-" + j.id(), null, j.question().trim(),
                    new ArrayList<>(), j.answer().trim(), "",
                    SOURCE_PRODUCTION, DIFFICULTY_SIMPLE, STATUS_PENDING,
                    ANSWER_ORIGIN_PRODUCTION, "rag_answer_eval#" + j.id(),
                    tags, "生产日志导出：contexts/reference 待专家审核补标"));
            if (candidates.size() >= limit) {
                return new Selection(candidates, judgedConsidered, 0,
                        duplicateSkipped, noAnswerSkipped, uselessTagged);
            }
        }

        // 2) chat_history：session → 该会话 assistant 消息（DAO 已按时间升序）
        Map<String, List<ChatMessage>> assistantBySession = new LinkedHashMap<>();
        for (ChatMessage m : assistantMessages) {
            assistantBySession.computeIfAbsent(m.sessionId(), k -> new ArrayList<>()).add(m);
        }

        int chatConsidered = 0;
        for (ChatMessage user : userMessages) {
            chatConsidered++;
            String key = normalizedKey(user.content());
            if (key.length() < 2) {
                noAnswerSkipped++;
                continue;
            }
            if (!seen.add(key)) {
                duplicateSkipped++;
                continue;
            }
            String answer = nextAssistantAnswer(assistantBySession.get(user.sessionId()),
                    user.createdAt());
            if (isBlank(answer)) {
                // 无配对回答：无法评任何 RAGAS 指标，放弃但保留计数透明
                seen.remove(key);
                noAnswerSkipped++;
                continue;
            }
            List<String> tags = new ArrayList<>(List.of("channel:chat-history"));
            candidates.add(new Candidate(
                    "prod-chat-" + user.id(), null, user.content().trim(),
                    new ArrayList<>(), answer.trim(), "",
                    SOURCE_PRODUCTION, DIFFICULTY_SIMPLE, STATUS_PENDING,
                    ANSWER_ORIGIN_PRODUCTION, "chat_history#" + user.id(),
                    tags, "生产日志导出：问答自动配对，contexts/reference 待专家审核补标"));
            if (candidates.size() >= limit) {
                break;
            }
        }
        return new Selection(candidates, judgedConsidered, chatConsidered,
                duplicateSkipped, noAnswerSkipped, uselessTagged);
    }

    /** 取同会话中时间严格晚于用户消息的最早一条 assistant 内容（无则 null）。 */
    static String nextAssistantAnswer(List<ChatMessage> sessionAssistant, LocalDateTime userTime) {
        if (sessionAssistant == null || userTime == null) {
            return null;
        }
        for (ChatMessage a : sessionAssistant) {
            if (a.createdAt() != null && a.createdAt().isAfter(userTime) && !isBlank(a.content())) {
                return a.content();
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
