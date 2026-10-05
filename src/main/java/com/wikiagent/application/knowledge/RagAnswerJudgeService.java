package com.wikiagent.application.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.entity.KbParentChunk;
import com.wikiagent.infrastructure.llm.ChatModelProviderChain;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalEntity;
import com.wikiagent.infrastructure.persistence.RagAnswerEvalJpaDao;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.agent.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * RAG 回答质量在线评判服务（LLM-as-judge，方案见 docs/rag-accuracy-eval.md）。
 * <p>
 * 定时（judge.cron，默认每小时第 20 分钟）拉取 PENDING 样本逐条评判：
 * source_doc_ids 回查父块内容拼参考资料 → LLM 三态判定 faithfulness/relevance → 回填。
 * <p>
 * 诚实契约：LLM 空响应、输出无法解析、两维均无法判定时<b>不得记为"评判不通过"</b>，
 * 只能 attempts+1 保持 PENDING（重试到 max-attempts 置 FAILED + error）。
 * 调用经 {@link ChatModelProviderChain} 降级链（purpose=JUDGE 打点），链不可用回退裸 ChatModel。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.answer-eval.enabled", havingValue = "true", matchIfMissing = true)
public class RagAnswerJudgeService {

    private static final Logger log = LoggerFactory.getLogger(RagAnswerJudgeService.class);

    private static final String JUDGE_SYSTEM = """
            你是企业知识库 RAG 系统的回答质量评测员。根据「用户问题」「助手回答」「参考资料」独立评判两个维度：
            1. faithfulness（忠实性）：回答中的事实性论断是否都能被参考资料支撑？包含参考资料无法支撑的关键事实判 false；
               回答只有通用表述/澄清/致谢、没有可验证的事实性论断，或未提供参考资料时，判 null（无法评判）。
            2. relevance（相关性）：回答是否切题回应了用户问题？明显答非所问、回避或拒绝回答判 false。
            只输出一个 JSON，不要输出任何其他文字：
            {"faithfulness":true,"relevance":true,"reason":"50 字以内的判定依据"}
            faithfulness 与 relevance 的取值只能是 true、false 或 null；reason 用简体中文。""";

    private final RagAnswerEvalJpaDao dao;
    private final KbParentChunkRepo parentRepo;
    private final KbDocumentRepo docRepo;
    private final ChatModel chatModel;
    private final ObjectProvider<ChatModelProviderChain> chainProvider;
    private final ObjectProvider<ModelCallRecorder> recorderProvider;
    private final ObjectMapper objectMapper;
    private final String chatModelName;
    private final int batchSize;
    private final int maxAttempts;
    private final int contextCharBudget;

    public RagAnswerJudgeService(RagAnswerEvalJpaDao dao,
                                 KbParentChunkRepo parentRepo,
                                 KbDocumentRepo docRepo,
                                 ChatModel chatModel,
                                 ObjectProvider<ChatModelProviderChain> chainProvider,
                                 ObjectProvider<ModelCallRecorder> recorderProvider,
                                 ObjectMapper objectMapper,
                                 @Value("${wikiagent.routing.simple-model:qwen-plus}") String chatModelName,
                                 @Value("${wikiagent.answer-eval.judge.batch-size:50}") int batchSize,
                                 @Value("${wikiagent.answer-eval.judge.max-attempts:3}") int maxAttempts,
                                 @Value("${wikiagent.answer-eval.judge.context-char-budget:6000}") int contextCharBudget) {
        this.dao = dao;
        this.parentRepo = parentRepo;
        this.docRepo = docRepo;
        this.chatModel = chatModel;
        this.chainProvider = chainProvider;
        this.recorderProvider = recorderProvider;
        this.objectMapper = objectMapper;
        this.chatModelName = chatModelName;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.contextCharBudget = Math.max(500, contextCharBudget);
    }

    /** 定时评判一批（异常全包，不影响调度线程）。 */
    @Scheduled(cron = "${wikiagent.answer-eval.judge.cron:0 20 * * * ?}")
    public void scheduledJudge() {
        try {
            JudgeBatchResult r = judgePendingBatch();
            if (r.total() > 0) {
                log.info("RAG 回答评判完成: 评判={} 失败={}", r.judged(), r.failed());
            }
        } catch (Exception e) {
            log.warn("RAG 回答评判批次异常: {}", e.getMessage());
        }
    }

    /** 评判一批 PENDING 样本（定时与手动触发共用），单条互不影响。 */
    public JudgeBatchResult judgePendingBatch() {
        List<RagAnswerEvalEntity> pending = dao.findByStatusOrderByCreatedAtAsc(
                RagAnswerEvalEntity.STATUS_PENDING, PageRequest.of(0, batchSize));
        int judged = 0;
        int failed = 0;
        for (RagAnswerEvalEntity e : pending) {
            try {
                if (judgeOne(e)) {
                    judged++;
                } else {
                    failed++;
                }
            } catch (Exception ex) {
                markRetryOrFail(e, truncate(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), 512));
                failed++;
            }
        }
        return new JudgeBatchResult(judged, failed);
    }

    /** 单条评判：成功回填判定返回 true；不可判定（空响应/解析失败）走重试记失败返回 false。 */
    boolean judgeOne(RagAnswerEvalEntity e) throws Exception {
        String context = loadContext(e.getSourceDocIds());
        String user = buildUserPrompt(e.getQuestion(), e.getAnswer(), context);
        LlmCall llm = call(JUDGE_SYSTEM, user, e.getUserId(), e.getSessionId());
        if (llm == null || llm.content() == null || llm.content().isBlank()) {
            markRetryOrFail(e, "LLM 空响应（降级链不可用或超时）");
            return false;
        }
        Map<String, Object> json = JsonExtractor.parseObject(llm.content());
        if (json.isEmpty()) {
            markRetryOrFail(e, "评判输出无法解析: " + truncate(llm.content(), 100));
            return false;
        }
        Boolean faithful = parseTriBool(json.get("faithfulness"));
        Boolean relevant = parseTriBool(json.get("relevance"));
        if (faithful == null && relevant == null) {
            // 两维均无法判定视为无效输出（relevance 对真实回答应可判），不得记为不通过
            markRetryOrFail(e, "评判输出两维均无法判定: " + truncate(llm.content(), 100));
            return false;
        }
        e.setFaithfulness(toDb(faithful));
        e.setRelevance(toDb(relevant));
        e.setVerdictReason(truncate(stringOf(json.get("reason")), 1024));
        e.setJudgeModel(llm.model());
        e.setStatus(RagAnswerEvalEntity.STATUS_JUDGED);
        e.setJudgedAt(Instant.now());
        e.setError(null);
        e.setAttempts(e.getAttempts() + 1);
        dao.save(e);
        return true;
    }

    /** source_doc_ids → 父块全文拼接（docRepo 补文件名标题行），总字符预算截断。 */
    private String loadContext(String sourceDocIdsJson) throws Exception {
        if (sourceDocIdsJson == null || sourceDocIdsJson.isBlank()) {
            return "";
        }
        List<String> docIds = objectMapper.readValue(sourceDocIdsJson, new TypeReference<List<String>>() {});
        StringBuilder sb = new StringBuilder();
        for (String docId : docIds) {
            if (docId == null || docId.isBlank()) {
                continue;
            }
            String filename = docRepo.findById(docId).map(KbDocument::getFilename).orElse(docId);
            StringBuilder section = new StringBuilder();
            section.append("\n【来源文档：").append(filename).append("】\n");
            List<KbParentChunk> parents = parentRepo.findByDocIdOrderByParentIndex(docId);
            for (KbParentChunk p : parents) {
                if (p.getContent() != null && !p.getContent().isBlank()) {
                    section.append(p.getContent().strip()).append('\n');
                }
            }
            if (sb.length() + section.length() > contextCharBudget) {
                int remain = contextCharBudget - sb.length();
                if (remain <= 100) {
                    break;
                }
                sb.append(section, 0, Math.min(section.length(), remain)).append("\n…(已截断)\n");
                break;
            }
            sb.append(section);
        }
        return sb.toString().strip();
    }

    private String buildUserPrompt(String question, String answer, String context) {
        String ref = (context == null || context.isBlank())
                ? "本次回答没有引用知识库文档，faithfulness 请判 null。"
                : context;
        return "用户问题：\n" + question.strip() + "\n\n助手回答：\n" + answer.strip()
                + "\n\n参考资料：\n" + ref;
    }

    /**
     * LLM 调用（复刻 AgentRagService.call 范式）：降级链优先（链内自带 JUDGE 打点），
     * 链缺失时回退裸 ChatModel + ModelCallRecorder 打点。
     * 全链不可用时链返回空响应（degraded=true，不抛异常）→ 返回 null 走重试。
     */
    private LlmCall call(String system, String user, String userId, String sessionId) {
        ChatModelProviderChain chain = chainProvider != null ? chainProvider.getIfAvailable() : null;
        if (chain != null) {
            ChatModelProviderChain.ChainResult cr = chain.call(
                    new com.wikiagent.domain.llm.spi.ChatModelRequest(system, user),
                    ModelCallLogPurpose.JUDGE, userId, sessionId);
            String content = cr.response() == null ? null : cr.response().content();
            String model = cr.response() == null ? null : cr.response().model();
            if (cr.degraded() || content == null || content.isBlank()) {
                return null;
            }
            return new LlmCall(content, model);
        }
        long started = System.currentTimeMillis();
        try {
            var resp = chatModel.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
            long latency = System.currentTimeMillis() - started;
            if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
                record(null, null, latency, false, userId, sessionId);
                return null;
            }
            String content = resp.getResult().getOutput().getText();
            record(usageOf(resp, true), usageOf(resp, false), latency, true, userId, sessionId);
            return (content == null || content.isBlank()) ? null : new LlmCall(content, chatModelName);
        } catch (Exception e) {
            record(null, null, System.currentTimeMillis() - started, false, userId, sessionId);
            return null;
        }
    }

    private void record(Integer tokensIn, Integer tokensOut, long latency, boolean ok,
                        String userId, String sessionId) {
        ModelCallRecorder r = recorderProvider != null ? recorderProvider.getIfAvailable() : null;
        if (r != null) {
            r.record(ModelCallLogPurpose.JUDGE, "dashscope", chatModelName,
                    tokensIn, tokensOut, latency, ok, null, userId, sessionId);
        }
    }

    private static Integer usageOf(org.springframework.ai.chat.model.ChatResponse resp, boolean prompt) {
        try {
            if (resp == null || resp.getMetadata() == null || resp.getMetadata().getUsage() == null) {
                return null;
            }
            var u = resp.getMetadata().getUsage();
            return prompt ? u.getPromptTokens() : u.getCompletionTokens();
        } catch (Exception e) {
            return null;
        }
    }

    /** 评判失败重试：attempts+1，未达上限保持 PENDING，达到上限置 FAILED + error。 */
    private void markRetryOrFail(RagAnswerEvalEntity e, String error) {
        e.setAttempts(e.getAttempts() + 1);
        e.setError(error);
        if (e.getAttempts() >= maxAttempts) {
            e.setStatus(RagAnswerEvalEntity.STATUS_FAILED);
        }
        try {
            dao.save(e);
        } catch (Exception ex) {
            log.warn("评判失败状态回写异常 id={}: {}", e.getId(), ex.getMessage());
        }
    }

    /** JSON 值 → 三态 Boolean：缺 key/显式 null/"null"/无法识别 → null（无法评判）。 */
    static Boolean parseTriBool(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).strip().toLowerCase();
        if ("true".equals(s)) {
            return true;
        }
        if ("false".equals(s)) {
            return false;
        }
        return null;
    }

    private static Integer toDb(Boolean v) {
        return v == null ? null : (v ? 1 : 0);
    }

    private static String stringOf(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max);
    }

    /** LLM 调用结果：文本 + 实际响应模型（链降级后的真实模型名）。 */
    record LlmCall(String content, String model) {}

    /** 批次结果。 */
    public record JudgeBatchResult(int judged, int failed) {
        public int total() {
            return judged + failed;
        }
    }
}
