package com.wikiagent.service.agent;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.application.prompt.PromptTemplateService;
import com.wikiagent.application.ragcache.AnswerCacheService;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.domain.llm.spi.ChatModelRequest;
import com.wikiagent.domain.prompt.RenderedPrompt;
import com.wikiagent.domain.retrieve.RetrievalSecurityContext;
import com.wikiagent.infrastructure.llm.ChatModelProviderChain;
import com.wikiagent.infrastructure.trace.RagTraceRecorder;
import com.wikiagent.service.chat.ChatStreamer;
import com.wikiagent.service.chat.FallbackAnswerService;
import com.wikiagent.service.chat.PromptComposer;
import com.wikiagent.service.chat.SseSender;
import com.wikiagent.service.retrieve.QueryRewriteService;
import com.wikiagent.service.retrieve.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agentic RAG 编排（业界 Adaptive RAG + 查询规划 + CRAG 纠正式检索的编排式实现）：
 * 1. 路由 + 查询规划：LLM 判断是否需要检索，闲聊直答；知识库问题拆分为 1~3 个检索查询
 * 2. 迭代检索：每轮多查询混合检索（BM25+向量 RRF → 父文档替换），跨轮去重累积
 * 3. 充分性评估（CRAG）：证据不足且未达轮数上限时，用改写查询自动重检
 * 4. 最终回答：汇总证据流式生成
 * 每一步均通过 SSE 推送 stage 事件；任一 LLM 决策失败自动降级，不阻断链路。
 */
@Service
public class AgentRagService {

    private static final Logger log = LoggerFactory.getLogger(AgentRagService.class);

    /** 路由 + 查询规划（一次调用同时输出 mode 与 queries）。 */
    static final String PLANNER_SYSTEM = """
            你是企业知识库系统的查询路由与规划模块。分析用户输入，只输出一个 JSON，不要输出任何其他文字：
            {"mode":"direct","queries":[]} 或 {"mode":"search","queries":["查询1","查询2"]} 或 {"mode":"web","queries":[]}
            判断规则：
            - 问候、寒暄、夸奖道谢、询问你的身份能力等与知识库内容无关的输入 → mode=direct（无需检索，直接对话回答）
            - 仅当问题明确需要"实时/最新外部信息"且知识库不可能包含时 → mode=web（跳过知识库检索，直接联网搜索回答）。
              典型场景：天气、新闻、股价、赛事比分、节假日安排等。
              注意：人名、公司制度、业务流程、产品信息、内部文档等可能存在于企业知识库的内容，
              即使看起来像通用问题，也应优先 mode=search 尝试检索知识库，而非 mode=web。
            - 其他需要知识库才能回答的问题 → mode=search，并将问题拆分为 1~3 个独立的检索查询：
              * 复杂/多要点问题按子问题拆分；简单问题只输出 1 个查询
              * 补全代词与省略的指代，使每个查询不依赖上下文也能被理解
              * 保留原文关键术语、产品名、专有名词，不要同义替换
            """;

    /** 检索充分性评估（CRAG 的评估器角色）。 */
    static final String GRADER_SYSTEM = """
            你是企业知识库的检索质量评估器。根据用户问题与已检索到的参考资料，判断资料是否足以回答问题：
            - sufficient=true：资料包含直接回答问题的关键事实
            - sufficient=false：资料为空、不相关、只覆盖部分要点或缺少关键信息
            - 特别注意：若资料中存在两份来源对同一事项给出不同数值/结论，且资料中已出现
              "【冲突提示】"段落明确标注两说并存，则 sufficient 必须为 true——
              "存在互相矛盾的两种规定"本身就是对用户有价值的事实，不得因资料矛盾而判不足。
            只输出一个 JSON，不要输出任何其他文字：
            {"sufficient":true,"refinedQuery":""}
            当 sufficient=false 时，在 refinedQuery 中给出一个更适合下一轮检索的改写查询
            （补全关键术语、换一个检索角度）；sufficient=true 时 refinedQuery 留空。
            """;

    /** direct 模式（闲聊/元问题）的系统提示。 */
    static final String DIRECT_SYSTEM = """
            你是企业知识库应用的助手。用户输入不是知识库检索类问题（如问候、寒暄、询问你的能力），
            请自然、友好、简明地用简体中文回应；不要编造知识库内容，如涉及知识库问题可提示用户换一种问法。
            """;

    private final WikiAgentProperties props;
    private final ChatModel chatModel;
    private final RetrievalService retrieval;
    private final QueryRewriteService rewriter;
    private final ChatStreamer streamer;
    private final FallbackAnswerService fallback;
    private final RagTraceRecorder traceRecorder;
    /** E4：模型调用打点器（可选）。 */
    private final ModelCallRecorder callRecorder;
    /** E1：提示词模板服务（可选，未配置模板时回退静态拼接）。 */
    private final PromptTemplateService templateService;
    /** 打点用模型名（chatModel 是 Primary simpleChatModel）。 */
    private final String chatModelName;
    /**
     * 缺陷1：同步模型调用降级链（可选）。存在时 plan/grade 等非流式调用走链，
     * 由链负责候选切换与 model_call_log（含 fallbackFrom）；缺失时回退裸 chatModel。
     */
    private final ChatModelProviderChain chatChain;
    /**
     * 缺陷16：答案缓存（可选，默认关闭）。开启后 run() 入口先查缓存，命中直接回放不调模型；
     * 证据充分生成路径用装饰 SseSender 收集答案文本并在完成时回写。
     */
    private final AnswerCacheService answerCache;

    /** 兼容旧构造（既有测试）：不打点、不用模板、不走降级链、不接答案缓存。 */
    public AgentRagService(WikiAgentProperties props, ChatModel chatModel, RetrievalService retrieval,
                           QueryRewriteService rewriter, ChatStreamer streamer,
                           FallbackAnswerService fallback, RagTraceRecorder traceRecorder) {
        this(props, chatModel, retrieval, rewriter, streamer, fallback, traceRecorder, null, null,
                "qwen-plus");
    }

    /** E1/E4 构造（兼容）：无同步降级链、无答案缓存。 */
    public AgentRagService(WikiAgentProperties props, ChatModel chatModel, RetrievalService retrieval,
                           QueryRewriteService rewriter, ChatStreamer streamer,
                           FallbackAnswerService fallback, RagTraceRecorder traceRecorder,
                           ObjectProvider<ModelCallRecorder> callRecorder,
                           ObjectProvider<PromptTemplateService> templateService,
                           @Value("${wikiagent.routing.simple-model:qwen-plus}") String chatModelName) {
        this(props, chatModel, retrieval, rewriter, streamer, fallback, traceRecorder,
                callRecorder, templateService, chatModelName, null, null);
    }

    /** 缺陷1 构造（兼容）：有同步降级链，无答案缓存。 */
    public AgentRagService(WikiAgentProperties props, ChatModel chatModel, RetrievalService retrieval,
                           QueryRewriteService rewriter, ChatStreamer streamer,
                           FallbackAnswerService fallback, RagTraceRecorder traceRecorder,
                           ObjectProvider<ModelCallRecorder> callRecorder,
                           ObjectProvider<PromptTemplateService> templateService,
                           @Value("${wikiagent.routing.simple-model:qwen-plus}") String chatModelName,
                           ObjectProvider<ChatModelProviderChain> chatChain) {
        this(props, chatModel, retrieval, rewriter, streamer, fallback, traceRecorder,
                callRecorder, templateService, chatModelName, chatChain, null);
    }

    /**
     * 缺陷1/16 全量装配构造：可选 {@link ChatModelProviderChain} 与
     * {@link AnswerCacheService}（ObjectProvider 包装，Bean 缺席时回退旧路径）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentRagService(WikiAgentProperties props, ChatModel chatModel, RetrievalService retrieval,
                           QueryRewriteService rewriter, ChatStreamer streamer,
                           FallbackAnswerService fallback, RagTraceRecorder traceRecorder,
                           ObjectProvider<ModelCallRecorder> callRecorder,
                           ObjectProvider<PromptTemplateService> templateService,
                           @Value("${wikiagent.routing.simple-model:qwen-plus}") String chatModelName,
                           ObjectProvider<ChatModelProviderChain> chatChain,
                           ObjectProvider<AnswerCacheService> answerCache) {
        this.props = props;
        this.chatModel = chatModel;
        this.retrieval = retrieval;
        this.rewriter = rewriter;
        this.streamer = streamer;
        this.fallback = fallback;
        this.traceRecorder = traceRecorder;
        this.callRecorder = callRecorder == null ? null : callRecorder.getIfAvailable();
        this.templateService = templateService == null ? null : templateService.getIfAvailable();
        this.chatModelName = chatModelName;
        this.chatChain = chatChain == null ? null : chatChain.getIfAvailable();
        this.answerCache = answerCache == null ? null : answerCache.getIfAvailable();
    }

    /** 路由+规划的决策结果。 */
    record Plan(String mode, List<String> queries) {
    }

    /** 充分性评估结果。 */
    record Grade(boolean sufficient, String refinedQuery) {
    }

    /**
     * 缺陷16：chat 路径不带 domain 过滤表达式（检索侧只有 identity 门控），
     * 答案缓存 key 的 domain 段恒为 all；未来支持按 domain 过滤问答时在此透传。
     */
    static final String ANSWER_CACHE_DOMAIN_ALL = "all";

    /** 缺陷16：缓存身份段——优先业务身份头，其次 userId，匿名请求为 anon。 */
    private static String cacheIdentity(String userId) {
        String identity = RetrievalSecurityContext.currentIdentity();
        if (identity != null && !identity.isBlank()) {
            return identity;
        }
        if (userId != null && !userId.isBlank()) {
            return userId;
        }
        return "anon";
    }

    /** 运行完整的 Agentic RAG 流程。异常向上抛出，由 ChatService 统一转 error 事件。 */
    public void run(String userId, String sessionId, String question, SseSender sse) {
        int maxRounds = Math.max(1, props.agent().maxRounds());
        String identity = cacheIdentity(userId);

        // 缺陷16：答案缓存读路径（默认关闭）。命中则原样回放答案，跳过路由/检索/评估/生成，
        // 不调用任何模型；身份/domain/session/query 任一不同 key 即不同
        if (answerCache != null && answerCache.active()) {
            AnswerCacheService.CacheItem cached = answerCache.get(
                    identity, ANSWER_CACHE_DOMAIN_ALL, sessionId, question);
            if (cached != null) {
                log.info("答案缓存命中 identity={} sessionId={} sourceCount={}",
                        identity, sessionId, cached.sourceCount());
                sse.send("stage", Map.of("stage", "generating", "cache", "hit"));
                sse.send("delta", Map.of("text", cached.answer()));
                sse.send("done", Map.of());
                sse.complete();
                return;
            }
        }

        // 1. 路由 + 查询规划（失败降级为单查询检索）
        sse.send("stage", Map.of("stage", "routing"));
        Plan plan = plan(question, userId, sessionId);
        if ("direct".equals(plan.mode())) {
            log.debug("路由结果: direct，跳过检索");
            traceRecorder.record(userId, sessionId, "agent-rag-routing", "plan",
                    question, "mode=direct", "OK", null);
            sse.send("stage", Map.of("stage", "generating", "mode", "direct"));
            streamer.stream(new Prompt(List.of(
                    new SystemMessage(DIRECT_SYSTEM),
                    new UserMessage(question))), sse, null, userId, sessionId);
            return;
        }
        if ("web".equals(plan.mode())) {
            log.debug("路由结果: web，跳过知识库检索直接联网兜底");
            traceRecorder.record(userId, sessionId, "agent-rag-routing", "plan",
                    question, "mode=web", "OK", null);
            fallback.answer(question, sse);
            return;
        }
        traceRecorder.record(userId, sessionId, "agent-rag-routing", "plan",
                question, "mode=search, queries=" + plan.queries(), "OK", null);

        // 2. 迭代检索 + 充分性评估（CRAG 循环）
        RetrievalService.Accumulator acc = retrieval.newAccumulator();
        List<String> queries = plan.queries();
        RetrievalService.RetrievalResult result = null;
        Grade lastGrade = null;
        for (int round = 1; round <= maxRounds; round++) {
            sse.send("stage", Map.of("stage", "retrieving", "round", round, "queries", queries));
            try {
                retrieval.search(acc, queries);
                // GraphRAG 图扩展与向量召回同候选池（AgentRag 手工编排 search/assemble，
                // 不能漏挂 retrieve(List) 中的图扩展，否则 PERO 关闭路径下图谱永不生效）
                retrieval.graphExpand(acc, queries);
            } catch (Exception e) {
                traceRecorder.record(userId, sessionId, "agent-rag-retrieval-r" + round, "tool_call",
                        String.join(" / ", queries), null, "ERROR", e.getMessage());
                throw e;
            }
            // 必须传原始 question：一参 assemble 会以 null query 跳过 rerank 与冲突守卫
            result = retrieval.assemble(acc, question);
            traceRecorder.record(userId, sessionId, "agent-rag-retrieval-r" + round, "tool_call",
                    String.join(" / ", queries),
                    "sources=" + result.sources().size() + (result.context().isEmpty() ? ", context=EMPTY" : ""),
                    "OK", null);

            if (result.context().isEmpty()) {
                if (round >= maxRounds) {
                    break; // 各轮均无证据
                }
                // 证据为空：换一种表述重检（CRAG 的纠正式回退改写）
                queries = List.of(rewriter.rewrite(question));
                log.debug("第 {} 轮证据为空，改写后重检: {}", round, queries);
                continue;
            }

            // 冲突双保留（KEPT_BOTH）：资料命中且系统已显式标注"两说并存、需人工核对"，
            // 矛盾本身就是要呈现给用户的答案，跳过充分性评估直接生成，
            // 避免评估器把"资料互相矛盾"误判为证据不足而走联网兜底（用例5实测根因）。
            if (result.conflict()) {
                log.info("检索结果含冲突标注，跳过充分性评估直接生成（冲突两说需向用户呈现）: sessionId={}", sessionId);
                break;
            }

            // 非空即评估（末轮也评估）：末轮评估的唯一用途是判定"弱命中→走未命中兜底"
            sse.send("stage", Map.of("stage", "grading", "round", round));
            Grade grade = grade(question, result.context(), userId, sessionId);
            lastGrade = grade;
            if (grade.sufficient()) {
                log.debug("第 {} 轮证据充分，结束检索", round);
                break;
            }
            if (round >= maxRounds || grade.refinedQuery() == null || grade.refinedQuery().isBlank()) {
                log.debug("第 {} 轮证据不足且无法继续重检，将走未命中兜底", round);
                break;
            }
            queries = List.of(grade.refinedQuery());
            log.debug("第 {} 轮证据不足，改写查询重检: {}", round, queries);
        }

        // v4 §6.6 检索埋点（最终进入生成的来源，循环结束后只记一次）
        retrieval.recordRetrievalMetrics(result);
        sse.send("sources", result.sources());

        // 3. 未命中判定：证据为空，或末轮评估仍判证据不足（弱命中）→ 两级兜底
        //    说明：Milvus hybridSearch 无相似度阈值、总会返回 topK 近邻，
        //    因此"检索不到"不能只看结果是否为空，必须以 CRAG 评估结论为准。
        boolean weakMiss = lastGrade != null && !lastGrade.sufficient();
        if (result.context().isEmpty() || weakMiss) {
            String reason = result.context().isEmpty() ? "empty_context" : "insufficient_evidence";
            traceRecorder.record(userId, sessionId, "agent-rag-fallback", "generate",
                    question, "fallback_reason=" + reason, "OK", null);
            fallback.answer(question, sse);
            return;
        }

        // 4. 证据充分：组装证据流式生成
        RenderedPrompt sys = PromptComposer.systemPrompt(templateService);
        RenderedPrompt usr = PromptComposer.userPrompt(templateService, result.context(), question);
        // 缺陷13：把实际命中的提示词模板 code@version 写 INFO 并挂到 generate span，
        // 便于回溯某次回答用了哪版提示词（version=-1 为静态兜底拼接）
        String promptRef = promptVersionRef(PromptComposer.CODE_CHAT_SYSTEM, sys.version())
                + "," + promptVersionRef(PromptComposer.CODE_CHAT_USER, usr.version());
        log.info("生成提示词版本 traceId={} userId={} sessionId={} sources={} prompt={}",
                MDC.get("traceId"), userId, sessionId, result.sources().size(), promptRef);
        traceRecorder.record(userId, sessionId, "agent-rag-generate", "generate",
                question, "sources=" + result.sources().size() + ", prompt=" + promptRef,
                "OK", null);
        sse.send("stage", Map.of("stage", "generating"));
        // 缺陷16：答案缓存写路径——装饰 SseSender 累积 delta，正常完成后按 identity/domain/
        // session/query 回写（含 ansdoc 反向索引）；生成出错或空答案不缓存
        SseSender generateSse = answerCache != null && answerCache.active()
                ? new AnswerCacheWritingSender(sse, answerCache, identity,
                        ANSWER_CACHE_DOMAIN_ALL, sessionId, question, result)
                : sse;
        streamer.stream(new Prompt(List.of(
                new SystemMessage(sys.content()),
                new UserMessage(usr.content()))), generateSse, null, userId, sessionId);
    }

    /**
     * 缺陷16：答案缓存回写 SSE 装饰器。逐字转发，累积 delta 文本；
     * 流正常 complete 且收集到非空文本时回写缓存，error 路径不缓存。
     */
    private static final class AnswerCacheWritingSender extends SseSender {
        private final SseSender delegate;
        private final AnswerCacheService cache;
        private final String identity;
        private final String domain;
        private final String sessionId;
        private final String question;
        private final RetrievalService.RetrievalResult result;
        private final StringBuilder answer = new StringBuilder();
        private boolean failed;

        AnswerCacheWritingSender(SseSender delegate, AnswerCacheService cache, String identity,
                                 String domain, String sessionId, String question,
                                 RetrievalService.RetrievalResult result) {
            this.delegate = delegate;
            this.cache = cache;
            this.identity = identity;
            this.domain = domain;
            this.sessionId = sessionId;
            this.question = question;
            this.result = result;
        }

        @Override
        public boolean send(String event, Object data) {
            if ("delta".equals(event) && data instanceof Map<?, ?> map && map.get("text") != null) {
                answer.append(String.valueOf(map.get("text")));
            }
            if ("error".equals(event)) {
                failed = true;
            }
            return delegate.send(event, data);
        }

        @Override
        public void complete() {
            try {
                if (!failed && answer.length() > 0) {
                    cache.put(identity, domain, sessionId, question, result, answer.toString());
                }
            } finally {
                delegate.complete();
            }
        }
    }

    /** 缺陷13：提示词版本引用串，如 {@code chat.system@v3}；未命中模板（version=-1）标 @fallback。 */
    private static String promptVersionRef(String code, int version) {
        return code + (version < 0 ? "@fallback" : "@v" + version);
    }

    /** 路由+规划：LLM 调用失败或输出异常时降级为 search + 原始问题。 */
    Plan plan(String question) {
        return plan(question, null, null);
    }

    Plan plan(String question, String userId, String sessionId) {
        try {
            String out = call(PLANNER_SYSTEM, question, ModelCallLogPurpose.INTENT, userId, sessionId);
            Map<String, Object> json = JsonExtractor.parseObject(out);
            if (json.isEmpty()) {
                log.warn("路由规划输出无法解析，降级为默认检索: {}", out);
                return new Plan("search", List.of(question));
            }
            if ("direct".equals(json.get("mode"))) {
                return new Plan("direct", List.of());
            }
            if ("web".equals(json.get("mode"))) {
                return new Plan("web", List.of());
            }
            List<String> queries = normalizeQueries(json.get("queries"), question, props.agent().maxQueriesPerRound());
            return new Plan("search", queries);
        } catch (Exception e) {
            log.warn("路由规划调用失败，降级为默认检索: {}", e.getMessage());
            return new Plan("search", List.of(question));
        }
    }

    /** 充分性评估：失败时视为充分，不阻断回答。 */
    Grade grade(String question, String context) {
        return grade(question, context, null, null);
    }

    Grade grade(String question, String context, String userId, String sessionId) {
        try {
            String user = "用户问题：" + question.strip() + "\n\n已检索到的参考资料：\n" + context.strip()
                    + "\n\n请判断以上资料是否足以回答问题，只输出 JSON。";
            String out = call(GRADER_SYSTEM, user, ModelCallLogPurpose.JUDGE, userId, sessionId);
            Map<String, Object> json = JsonExtractor.parseObject(out);
            if (json.isEmpty()) {
                log.warn("评估输出无法解析，视为充分: {}", out);
                return new Grade(true, null);
            }
            boolean sufficient = Boolean.TRUE.equals(json.get("sufficient"));
            Object refined = json.get("refinedQuery");
            return new Grade(sufficient, refined == null ? null : String.valueOf(refined));
        } catch (Exception e) {
            log.warn("评估调用失败，视为充分: {}", e.getMessage());
            return new Grade(true, null);
        }
    }

    private String call(String system, String user) {
        return call(system, user, ModelCallLogPurpose.CHAT, null, null);
    }

    private String call(String system, String user, ModelCallLogPurpose purpose,
                        String userId, String sessionId) {
        // 缺陷1：降级链存在时走链（链内负责候选切换与含 fallbackFrom 的打点）；
        // 全链不可用时链返回空文本响应（不抛异常），由上层 JSON 解析失败走既有降级。
        if (chatChain != null) {
            ChatModelProviderChain.ChainResult cr =
                    chatChain.call(new ChatModelRequest(system, user), purpose, userId, sessionId);
            return cr.response() == null ? null : cr.response().content();
        }
        long started = System.currentTimeMillis();
        try {
            var resp = chatModel.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
            long latency = System.currentTimeMillis() - started;
            if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
                record(purpose, null, null, latency, false, userId, sessionId);
                return null;
            }
            record(purpose, usageOf(resp, true), usageOf(resp, false), latency, true, userId, sessionId);
            return resp.getResult().getOutput().getText();
        } catch (Exception e) {
            record(purpose, null, null, System.currentTimeMillis() - started, false, userId, sessionId);
            throw e;
        }
    }

    private void record(ModelCallLogPurpose purpose, Integer tokensIn, Integer tokensOut, long latency,
                        boolean ok, String userId, String sessionId) {
        if (callRecorder != null) {
            callRecorder.record(purpose, "dashscope", chatModelName, tokensIn, tokensOut,
                    latency, ok, null, userId, sessionId);
        }
    }

    private static Integer usageOf(org.springframework.ai.chat.model.ChatResponse resp, boolean prompt) {
        try {
            if (resp == null || resp.getMetadata() == null) {
                return null;
            }
            Usage u = resp.getMetadata().getUsage();
            if (u == null) {
                return null;
            }
            return prompt ? u.getPromptTokens() : u.getCompletionTokens();
        } catch (Exception e) {
            return null;
        }
    }

    /** 归一化规划出的查询列表：仅保留非空字符串、去重、限量；全部无效时回退原始问题。 */
    private List<String> normalizeQueries(Object raw, String fallback, int limit) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) {
                    continue;
                }
                String q = String.valueOf(o).strip();
                if (!q.isEmpty() && !out.contains(q)) {
                    out.add(q);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out.isEmpty() ? List.of(fallback) : List.copyOf(out);
    }
}
