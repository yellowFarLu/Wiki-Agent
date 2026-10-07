package com.wikiagent.application.agent.pero;

import com.wikiagent.application.agent.ToolPermissionRegistry;
import com.wikiagent.application.llm.ModelPricingService;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;
import com.wikiagent.domain.agent.ReActStep;
import com.wikiagent.domain.agent.ThoughtActionObservation;
import com.wikiagent.domain.business.SlotClarificationSignal;
import com.wikiagent.domain.task.ErrorCode;
import com.wikiagent.domain.task.FatalTaskException;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.TaskBudget;
import com.wikiagent.domain.tool.ToolCaller;
import com.wikiagent.infrastructure.trace.AuditLogRepository;
import com.wikiagent.service.agent.JsonExtractor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v6 §20.5 节点内 ReAct 子循环执行器（替代 §2.4 单步 NodeExecutor）。
 * <p>
 * ReAct 论文（arXiv:2210.03629）的 Thought/Action/Observation 交替循环：
 * 推理→动作→观测→再推理，直到 LLM 输出 FINAL 或达到 maxIter。
 * <p>
 * 与 §2 既有 {@code NodeExecutor} 的区别：
 * <ul>
 *   <li>§2 NodeExecutor 单步执行（plan 后顺序执行），v6 ReActExecutor 节点内多轮 ReAct</li>
 *   <li>工具白名单按 {@link PlanStep#stepType()} 路由（§20.6 配置）</li>
 *   <li>失败不抛异常，返回 {@link ReActResult#truncated} 由 Reflector 判断是否重做</li>
 * </ul>
 * maxIter 经验默认 8（§20.6 {@code wikiagent.pero.react.max-iterations}），可由 PlanStep 覆盖。
 */
@Service
public class ReActExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReActExecutor.class);

    static final String REACT_SYSTEM = """
            你是企业知识库 Agent 的 ReAct 执行器。基于用户原始诉求、当前节点目标与历史轨迹，输出下一步：
            只输出一个 JSON，不要输出任何其他文字：
            {"thought":"...","action":{"name":"...","args":"..."},"finalAnswer":null}
            或（节点结束）：
            {"thought":"...","action":null,"finalAnswer":"...最终答案..."}

            规则：
            1. action.name 必须在工具白名单中；args 必须是白名单描述的 JSON 字符串。
            2. 需要订单号、上传文件等前置信息时，必须先发起对应工具调用——缺信息时系统会以明确
               Observation 提示，由系统向用户追问；严禁在 finalAnswer 中自行向用户追问，也严禁
               编造或猜测订单号、文件 ID 等参数。
            3. 仅当节点目标已由工具返回结果完成时，才输出 action=null 且 finalAnswer 非空；
               finalAnswer 必须严格基于工具返回的事实，不得添加工具结果之外的信息。
            """;

    /**
     * 工具描述/参数说明（让模型首轮即可正确选工具、构造 args）。
     * 白名单只给名字时模型常跳过工具直接 FINAL，描述与必填槽说明是 ReAct 提示词的必要部分。
     */
    static final Map<String, String> TOOL_DESCRIPTIONS = Map.of(
            "search_knowledge_base", "检索企业知识库。args JSON: {\"query\":\"检索词\"}",
            "search_history", "检索当前用户的历史对话。args JSON: {\"query\":\"检索词\",\"topK\":3}",
            "update_user_profile", "更新当前用户档案。args JSON: {\"key\":\"字段名\",\"value\":\"字段值\"}",
            "read_handover", "读取本会话的交接档案。args JSON: {}",
            "list_abandoned_paths", "列出当前用户未完成的历史路径。args JSON: {}",
            "query_order", "按订单号查询订单详情。args JSON: {\"orderNo\":\"8-20位字母或数字的单号\"}；"
                    + "若缺少订单号，照常调用，系统会返回追问提示",
            "query_trajectory", "按订单号查询物流轨迹列表。args JSON: {\"orderNo\":\"8-20位字母或数字的单号\"}；"
                    + "若缺少订单号，照常调用，系统会返回追问提示",
            "generate_customs_info", "基于用户上传的映射 Excel 生成清关 Excel。"
                    + "args JSON: {\"mappingFileId\":\"上传文件的ID\"}；若文件尚未上传，照常调用，系统会返回上传提示");

    private final ChatModel model;
    private final ToolRegistry toolRegistry;
    private final TraceService trace;
    private final ToolExecutor toolExecutor;
    private final int defaultMaxIter = 8;

    /** F1：工具权限注册表 + 审计（可选，缺省时不做权限拦截，保持既有行为）。 */
    private final ToolPermissionRegistry permissionRegistry;
    private final AuditLogRepository auditLog;

    /**
     * #17 真实计量计费（可选）：供应商 usage 可用时按 promptTokens+completionTokens
     * 计 token，按 {@link ModelPricingService} 配置单价计成本；缺省/取不到时 cost=0。
     */
    private final ObjectProvider<ModelPricingService> pricingProvider;

    public ReActExecutor(ChatModel model, ToolRegistry toolRegistry,
                         ToolExecutor toolExecutor, TraceService trace) {
        this(model, toolRegistry, toolExecutor, trace, null, null, null);
    }

    public ReActExecutor(ChatModel model, ToolRegistry toolRegistry,
                         ToolExecutor toolExecutor, TraceService trace,
                         ToolPermissionRegistry permissionRegistry,
                         AuditLogRepository auditLog) {
        this(model, toolRegistry, toolExecutor, trace, permissionRegistry, auditLog, null);
    }

    @Autowired
    public ReActExecutor(ChatModel model, ToolRegistry toolRegistry,
                         ToolExecutor toolExecutor, TraceService trace,
                         ToolPermissionRegistry permissionRegistry,
                         AuditLogRepository auditLog,
                         ObjectProvider<ModelPricingService> pricingProvider) {
        this.model = model;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.trace = trace;
        this.permissionRegistry = permissionRegistry;
        this.auditLog = auditLog;
        this.pricingProvider = pricingProvider;
    }

    /**
     * 节点内 ReAct 子循环：最多 maxIter 次 Thought/Action/Observation 交替。
     * <p>
     * trace 每轮启动子 span（react:{stepId}:{iter}），便于 §13.8 验收 #23 校验
     * "节点 span 下挂至少 2 条子 ReAct span"。
     */
    public ReActResult execute(PlanStep step, Perception ctx, Handover handover, int maxIter) {
        return execute(step, ctx, handover, maxIter, () -> { });
    }

    /**
     * 带迭代 gate 的重载（Task 12）：每次迭代顶部执行 {@code iterationGate}，
     * gate 抛出的控制信号（暂停/取消）原样穿出本方法，不得被迭代内通用 catch 吞掉。
     * 原四参方法委托空 gate，行为不变。
     */
    public ReActResult execute(PlanStep step, Perception ctx, Handover handover,
                               int maxIter, Runnable iterationGate) {
        return execute(step, ctx, handover, maxIter, iterationGate, null);
    }

    /**
     * F1/F2/F3 治理版重载：携带 ReActGovernance（caller/agentName/预算/审批续跑），
     * 原五参方法委托 null 治理，行为完全不变（不拦截、不计费、不批准）。
     */
    public ReActResult execute(PlanStep step, Perception ctx, Handover handover,
                               int maxIter, Runnable iterationGate,
                               ReActGovernance governance) {
        int limit = maxIter > 0 ? maxIter : defaultMaxIter;
        List<ThoughtActionObservation> traceList = new ArrayList<>();
        List<String> allowedTools = toolRegistry.allowedTools(step);
        String conversationId = ctx.userId() + ":" + ctx.sessionId();
        String prompt = buildReActPrompt(step, ctx, allowedTools, traceList);

        for (int i = 0; i < limit; i++) {
            iterationGate.run();
            // F2：预算检查（迭代边界）——超限即停止，按策略转人工或致命失败
            checkBudget(governance);
            TraceSpan sub = trace.start(conversationId, ctx.userId(),
                    "react:" + step.id() + ":" + (i + 1), step.goal());
            ReActStep ra;
            ChatResponse resp;
            try {
                // #17 保留完整 ChatResponse 以读取供应商 usage（真实 token 计量）
                resp = chat(REACT_SYSTEM, prompt);
                ra = callReAct(resp);
            } catch (Exception e) {
                trace.end(sub, null, "ERROR", e.getMessage());
                log.warn("ReAct step {} iter {} 调用失败: {}", step.id(), i + 1, e.getMessage());
                return ReActResult.truncated(traceList);
            }
            // F2：迭代后计量（+tokens、+cost、+1 iteration），sink 回调写回 payload
            chargeBudget(governance, prompt, resp);

            String observation;
            if (ra.action() == null || "FINAL".equalsIgnoreCase(nameOf(ra))) {
                observation = ra.finalAnswer() == null ? "" : ra.finalAnswer();
                traceList.add(ThoughtActionObservation.finalAnswer(ra.thought(), observation));
                trace.end(sub, ra.finalAnswer(), "OK", null);
                return ReActResult.done(ra.finalAnswer(), traceList);
            }

            // Action → Observation
            ReActAction action = new ReActAction(nameOf(ra), argsOf(ra));
            // F1：工具权限拦截（白名单/角色/scope 三维）；拒绝则不执行工具并写 TOOL_DENIED 审计
            String denyReason = permissionRegistry == null || governance == null
                    ? null
                    : permissionRegistry.denyReason(action.name(), governance.caller(), governance.agentName());
            if (denyReason != null) {
                observation = "TOOL_DENIED: " + denyReason;
                auditToolDenied(action.name(), governance, denyReason);
                log.warn("ReAct step {} iter {} 工具 {} 权限拒绝: {}", step.id(), i + 1, action.name(), denyReason);
            } else {
                // F3：高危工具人工批准门（TOOL_APPROVAL）。未决 → 抛人工接管（worker 建单 +
                // WAITING_HUMAN，resolve 后续跑时决策经 approvalDecisions 回传）；
                // 批准 → 放行执行；驳回 → 跳过该工具，observation 标记 TOOL_REJECTED_BY_USER
                Boolean approval = approvalDecisionOf(action, governance);
                if (approval == null) {
                    throw new HumanRequiredException(HumanTaskKind.TOOL_APPROVAL, "工具批准",
                            "高危工具 " + action.name() + " 需人工批准后执行",
                            buildApprovalFormSchema(action.name(), action.args()));
                }
                if (!approval) {
                    observation = "TOOL_REJECTED_BY_USER";
                    log.info("ReAct step {} iter {} 工具 {} 被人工驳回，跳过执行", step.id(), i + 1, action.name());
                } else {
                    try {
                        observation = toolExecutor.invoke(action, ctx);
                    } catch (SlotClarificationSignal e) {
                        // 神经符号硬闸：缺槽信号必须原样穿出，由业务网关捕获置 WAITING_SLOT，
                        // 不得吞成 Observation 文本（对齐 LangGraph "do not wrap interrupt in try/except"）
                        throw e;
                    } catch (Exception e) {
                        observation = "ERROR: " + e.getMessage();
                        log.warn("ReAct step {} iter {} 工具 {} 调用失败: {}",
                                step.id(), i + 1, action.name(), e.getMessage());
                    }
                }
            }
            traceList.add(ra.toTAO(observation));
            trace.end(sub, observation, "OK", null);
            // F3：上下文压缩——轨迹超阈值时重建 prompt（早期轮折叠为摘要占位），
            // 否则增量追加本轮观测
            if (traceList.size() > COMPACT_THRESHOLD) {
                prompt = buildReActPrompt(step, ctx, allowedTools, compactedTrace(traceList));
            } else {
                prompt = appendObservation(prompt, ra.thought(), action, observation);
            }
        }

        log.warn("ReAct step {} 达到 maxIter={} 仍未结束，返回 truncated", step.id(), limit);
        return ReActResult.truncated(traceList);
    }

    private String buildReActPrompt(PlanStep step, Perception ctx,
                                    List<String> allowedTools,
                                    List<ThoughtActionObservation> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前节点目标：").append(step.goal()).append("\n");
        sb.append("节点类型：").append(step.stepType()).append("\n");
        sb.append("用户意图：").append(ctx.intent()).append("\n");
        sb.append("用户原始诉求：").append(ctx.userInput() == null ? "" : ctx.userInput()).append("\n");
        if (step.hint() != null) {
            sb.append("历史反思 hint：").append(step.hint()).append("\n");
        }
        sb.append("允许调用的工具：\n");
        for (String t : allowedTools) {
            String d = TOOL_DESCRIPTIONS.get(t);
            sb.append("  - ").append(t);
            if (d != null && !d.isBlank()) {
                sb.append("：").append(d);
            }
            sb.append("\n");
        }
        sb.append("\n");
        if (history.isEmpty()) {
            sb.append("历史轨迹：无（首轮）\n");
        } else {
            sb.append("历史轨迹：\n");
            for (int i = 0; i < history.size(); i++) {
                ThoughtActionObservation tao = history.get(i);
                sb.append("  [").append(i + 1).append("] thought=").append(tao.thought())
                        .append(" | action=").append(tao.actionName())
                        .append(" | observation=").append(tao.observation()).append("\n");
            }
        }
        sb.append("\n请输出下一步 ReAct JSON。");
        return sb.toString();
    }

    private String appendObservation(String prompt, String thought,
                                     ReActAction action, String observation) {
        return prompt + "\n[新增] thought=" + thought
                + " | action=" + action.name()
                + " | observation=" + observation
                + "\n请输出下一步 ReAct JSON。";
    }

    private ReActStep callReAct(ChatResponse resp) {
        String out = responseText(resp);
        Map<String, Object> json = JsonExtractor.parseObject(out);
        if (json.isEmpty()) {
            throw new IllegalStateException("ReAct 输出无法解析: " + out);
        }
        String thought = str(json.get("thought"));
        Object actionObj = json.get("action");
        ReActStep.Action action = null;
        if (actionObj instanceof Map<?, ?> am && am.get("name") != null) {
            action = new ReActStep.Action(str(am.get("name")), str(am.get("args")));
        }
        String finalAnswer = str(json.get("finalAnswer"));
        if (finalAnswer.isBlank()) {
            finalAnswer = null;
        }
        return new ReActStep(thought, action, finalAnswer);
    }

    private static String responseText(ChatResponse resp) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return null;
        }
        return resp.getResult().getOutput().getText();
    }

    /** 估算每 1K token 成本（USD），治理计费用（与真实供应商价格解耦的标称值）。 */
    static final double COST_PER_1K_TOKENS = 0.002d;

    /**
     * F2：迭代边界预算检查。超限按策略抛出：
     * PAUSE_HUMAN → HumanRequiredException(INPUT, "预算超限", ...)（表单可填新上限提额续跑）；
     * FAIL → FatalTaskException(BUDGET_EXCEEDED)。
     */
    private void checkBudget(ReActGovernance governance) {
        if (governance == null || governance.budgetTracker() == null) {
            return;
        }
        var budget = governance.budgetTracker().current();
        String exceeded = budget.exceededBy();
        if (exceeded == null) {
            return;
        }
        String msg = "预算超限: " + exceeded;
        if (budget.overflowPolicy() == TaskBudget.OverflowPolicy.FAIL) {
            throw new FatalTaskException(ErrorCode.BUDGET_EXCEEDED, msg);
        }
        throw new HumanRequiredException(HumanTaskKind.INPUT, "预算超限",
                msg + "；请提高预算上限（iterationLimit / tokenLimit / costLimit）或终结任务",
                buildBudgetFormSchema(budget));
    }

    /** F2：预算超限表单 schema（供人工提额）。 */
    private static com.fasterxml.jackson.databind.JsonNode buildBudgetFormSchema(TaskBudget budget) {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("iterationLimit", "integer(当前 " + budget.iterationLimit() + ")");
        schema.put("tokenLimit", "integer(当前 " + budget.tokenLimit() + ")");
        schema.put("costLimit", "number(当前 " + budget.costLimit() + ")");
        return schema;
    }

    /**
     * F2/#17 迭代后记账。
     * <ul>
     *   <li>响应携带供应商 usage（promptTokens/completionTokens）：按真实 token 计量，
     *       成本走 {@link ModelPricingService} 配置单价；pricing 缺失或未配置该模型时 cost=0；</li>
     *   <li>取不到 usage：回退 4 字符 ≈ 1 token 粗估（仅按 prompt 长度，保持历史行为），
     *       成本用标称常量 {@value #COST_PER_1K_TOKENS} 估算。</li>
     * </ul>
     */
    private void chargeBudget(ReActGovernance governance, String prompt, ChatResponse resp) {
        if (governance == null || governance.budgetTracker() == null) {
            return;
        }
        try {
            Usage usage = resp == null || resp.getMetadata() == null
                    ? null : resp.getMetadata().getUsage();
            Integer tokensIn = usage == null ? null : usage.getPromptTokens();
            Integer tokensOut = usage == null ? null : usage.getCompletionTokens();
            // spring-ai 1.1.2 默认 metadata 返回 EmptyUsage(0,0)（非 null），
            // 0/0 不是真实计量（真实调用 prompt tokens 恒 >0），按"未携带 usage"回退估算
            boolean hasRealUsage = (tokensIn != null && tokensIn > 0)
                    || (tokensOut != null && tokensOut > 0);

            long tokens;
            double cost;
            if (hasRealUsage) {
                long in = tokensIn == null ? 0 : tokensIn;
                long out = tokensOut == null ? 0 : tokensOut;
                tokens = Math.max(1, in + out);
                cost = priceCost(resp, tokensIn, tokensOut);
            } else {
                tokens = Math.max(1, (prompt == null ? 0 : prompt.length()) / 4L);
                cost = tokens * COST_PER_1K_TOKENS / 1000d;
                log.info("ReAct 响应未携带 usage，token 与成本按 4 字符≈1 token 估算 [estimated]: tokens={}",
                        tokens);
            }
            governance.budgetTracker().charge(tokens, cost);
        } catch (Exception e) {
            log.warn("预算记账失败（不阻断执行）: {}", e.getMessage());
        }
    }

    /** #17 按模型真实单价计成本；pricing 服务缺失/未配置时返回 0（不阻断计量）。 */
    private double priceCost(ChatResponse resp, Integer tokensIn, Integer tokensOut) {
        if (pricingProvider == null) {
            return 0d;
        }
        ModelPricingService pricing = pricingProvider.getIfAvailable();
        if (pricing == null || tokensIn == null || tokensOut == null) {
            return 0d;
        }
        String model = resp.getMetadata() == null ? null : resp.getMetadata().getModel();
        Double estimated = pricing.estimate(model, tokensIn, tokensOut);
        return estimated == null ? 0d : estimated;
    }

    /** F3：上下文压缩阈值——轨迹超过该轮数后，早期轮次在 prompt 中折叠为摘要占位。 */
    static final int COMPACT_THRESHOLD = 10;

    /** F3：压缩时保留最近 N 轮原文。 */
    static final int COMPACT_KEEP_RECENT = 5;

    /**
     * F3/#18 高危工具批准门（决策键含 args 指纹）。
     *
     * @return TRUE=放行（不需批准或已批准该工具+该组参数）；FALSE=已驳回（跳过执行）；
     *         null=需批准但未决（抛人工接管）
     */
    private Boolean approvalDecisionOf(ReActAction action, ReActGovernance governance) {
        if (permissionRegistry == null || governance == null
                || !permissionRegistry.requiresApproval(action.name())) {
            return Boolean.TRUE;
        }
        Map<String, Boolean> decisions = governance.approvalDecisions();
        if (decisions == null || decisions.isEmpty()) {
            return null;
        }
        Boolean decision = decisions.get(approvalKey(action.name(), action.args()));
        if (decision != null) {
            return decision;
        }
        // 兼容旧人工任务（无 args 指纹，决策 map 以纯 toolName 为键）
        return decisions.get(action.name());
    }

    /**
     * #18 批准归属键 = {@code toolName + ":" + sha256(规范化 args JSON)}。
     * 规范化：解析 args 后按键排序重序列化（消除键序/空白差异）；空白或解析失败
     * 一律规范化为 {@code {}}，故无参工具指纹 = sha256("{}")。
     */
    static String approvalKey(String toolName, String args) {
        return toolName + ":" + argsFingerprint(args);
    }

    /** #18 args 指纹（小写 HEX SHA-256，规范化 JSON）。package-private 供测试复用。 */
    static String argsFingerprint(String args) {
        String normalized;
        if (args == null || args.isBlank()) {
            normalized = "{}";
        } else {
            try {
                Map<String, Object> parsed = APPROVAL_KEY_MAPPER.readValue(
                        args, new TypeReference<Map<String, Object>>() {});
                normalized = APPROVAL_KEY_MAPPER.writeValueAsString(
                        parsed == null ? Map.of() : parsed);
            } catch (Exception e) {
                normalized = "{}";
            }
        }
        return sha256Hex(normalized);
    }

    private static final ObjectMapper APPROVAL_KEY_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** F3/#18 TOOL_APPROVAL 表单 schema：toolName+argsFingerprint 供 worker/任务处理器回读复合键。 */
    private static com.fasterxml.jackson.databind.JsonNode buildApprovalFormSchema(
            String toolName, String args) {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("toolName", toolName);
        schema.put("argsFingerprint", argsFingerprint(args));
        schema.put("approve", "boolean(true=批准执行 / false=驳回跳过)");
        return schema;
    }

    /**
     * F3：上下文压缩。迭代轨迹超 {@value #COMPACT_THRESHOLD} 轮时，早期 TAO 折叠为单条
     * COMPRESSED 摘要（仅保留动作名序列），最近 {@value #COMPACT_KEEP_RECENT} 轮保留原文。
     * 只影响后续 prompt（防上下文膨胀），不改变 {@link ReActResult} 携带的完整轨迹。
     */
    static List<ThoughtActionObservation> compactedTrace(List<ThoughtActionObservation> trace) {
        if (trace.size() <= COMPACT_THRESHOLD) {
            return trace;
        }
        int keep = COMPACT_KEEP_RECENT;
        List<ThoughtActionObservation> early = trace.subList(0, trace.size() - keep);
        StringBuilder summary = new StringBuilder("早期 ").append(early.size())
                .append(" 轮轨迹已压缩，动作序列：");
        for (ThoughtActionObservation t : early) {
            summary.append('[').append(t.actionName()).append(']');
        }
        List<ThoughtActionObservation> out = new ArrayList<>(keep + 1);
        out.add(new ThoughtActionObservation(summary.toString(), "COMPRESSED", null, "（已压缩占位）"));
        out.addAll(trace.subList(trace.size() - keep, trace.size()));
        return out;
    }

    /** F1：TOOL_DENIED 审计落库（eventType=TOOL_DENIED，含 toolName/userId/reason）。 */
    private void auditToolDenied(String toolName, ReActGovernance governance, String reason) {
        if (auditLog == null) {
            return;
        }
        try {
            String userId = governance.caller() == null ? null : governance.caller().userId();
            auditLog.log(userId, governance.sessionId(), "TOOL_DENIED", "tool_permission",
                    "WARN", "tool=" + toolName, "BLOCKED",
                    "toolName=" + toolName + " userId=" + userId + " reason=" + reason);
        } catch (Exception e) {
            log.warn("TOOL_DENIED 审计写入失败 tool={}: {}", toolName, e.getMessage());
        }
    }

    private static String nameOf(ReActStep ra) {
        return ra.action() == null ? null : ra.action().name();
    }

    private static String argsOf(ReActStep ra) {
        return ra.action() == null ? null : ra.action().args();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).strip();
    }

    private ChatResponse chat(String system, String user) {
        return model.call(new Prompt(List.of(
                new SystemMessage(system), new UserMessage(user))));
    }
}
