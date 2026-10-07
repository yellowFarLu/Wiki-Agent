package com.wikiagent.application.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.agent.pero.ReActExecutor;
import com.wikiagent.application.agent.pero.ReActGovernance;
import com.wikiagent.application.agent.pero.SimplePerception;
import com.wikiagent.application.agent.pero.TraceService;
import com.wikiagent.config.BusinessIntentProperties;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;
import com.wikiagent.domain.business.BusinessIntent;
import com.wikiagent.domain.business.Classification;
import com.wikiagent.domain.business.DialogueState;
import com.wikiagent.domain.business.DialogueStateStore;
import com.wikiagent.domain.business.DialogueStatus;
import com.wikiagent.domain.business.IntentClassifier;
import com.wikiagent.domain.business.IntentSpec;
import com.wikiagent.domain.business.SlotClarificationSignal;
import com.wikiagent.domain.business.SlotSpec;
import com.wikiagent.domain.business.SlotEvidence;
import com.wikiagent.domain.business.SlotEvidenceSource;
import com.wikiagent.domain.business.SlotValue;
import com.wikiagent.domain.tool.ToolCaller;
import com.wikiagent.infrastructure.business.BusinessFileEntity;
import com.wikiagent.infrastructure.business.BusinessTaskEntity;
import com.wikiagent.infrastructure.business.BusinessTaskJpaDao;
import com.wikiagent.service.chat.SseSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 业务意图网关（有界神经符号 ReAct）：LLM 在环内自主决策，意图/槽位/工具/文件每处副作用由确定性硬闸裁决。
 * <p>
 * 主流程（§1/§4）：
 * <pre>
 * ActGate（等待态解析回答/取消词）→ IntentClassifier（失败/低置信 → ROUTE_KNOWLEDGE）
 *   → SlotEvidenceValidator（证据裁决入槽）→ 建 business_task → ReActExecutor
 *   → 缺槽 SlotClarificationSignal → WAITING_SLOT + slot_request
 *   → FINAL → delta(+file_ready)+done，清状态
 * </pre>
 * 返回 {@link Outcome#ROUTE_KNOWLEDGE} 时由 ChatService 走现有四层链路（知识问答）。
 */
@Service
public class BusinessIntentGateway {

    private static final Logger log = LoggerFactory.getLogger(BusinessIntentGateway.class);

    public enum Outcome { HANDLED, ROUTE_KNOWLEDGE }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BusinessIntentProperties props;
    private final DialogueStateStore store;
    private final IntentClassifier classifier;
    private final IntentSpecRegistry registry;
    private final SlotEvidenceValidator validator;
    private final ActGate actGate;
    private final ReActExecutor reActExecutor;
    private final TraceService trace;
    private final BusinessTaskJpaDao taskDao;
    private final BusinessFileService fileService;
    private final int maxIterations;

    public BusinessIntentGateway(BusinessIntentProperties props, DialogueStateStore store,
                                 IntentClassifier classifier, IntentSpecRegistry registry,
                                 SlotEvidenceValidator validator, ActGate actGate,
                                 ReActExecutor reActExecutor, TraceService trace,
                                 BusinessTaskJpaDao taskDao,
                                 BusinessFileService fileService,
                                 @Value("${wikiagent.pero.react.max-iterations:8}") int maxIterations) {
        this.props = props;
        this.store = store;
        this.classifier = classifier;
        this.registry = registry;
        this.validator = validator;
        this.actGate = actGate;
        this.reActExecutor = reActExecutor;
        this.trace = trace;
        this.taskDao = taskDao;
        this.fileService = fileService;
        this.maxIterations = maxIterations;
    }

    /** 是否启用（开关关闭时 ChatService 直接跳过网关）。 */
    public boolean enabled() {
        return props.isEnabled();
    }

    /**
     * 处理一轮对话。返回 ROUTE_KNOWLEDGE 表示应交给现有知识问答链路。
     */
    public Outcome handle(String userId, String sessionId, String rawInput,
                          String attachmentFileId, SseSender sse) {
        DialogueState state = store.load(userId, sessionId).orElse(DialogueState.idle(userId, sessionId));

        // ① 等待槽位态：优先解析回答，不重跑分类
        if (state.isWaitingSlot()) {
            return handleWaiting(state, rawInput, attachmentFileId, sse);
        }

        // ② 意图分类
        Classification c = classifier.classify(rawInput);
        if (c == null || c.fallback() || c.intent() == null) {
            // 降级知识问答：清残留状态，交给现有链路（打点 reason 由 classify 记录）
            clearIfPresent(state);
            return Outcome.ROUTE_KNOWLEDGE;
        }

        // ③ 证据裁决 + 建任务 + 跑 ReAct
        return startTask(state, c, rawInput, attachmentFileId, sse);
    }

    private Outcome handleWaiting(DialogueState state, String rawInput,
                                  String attachmentFileId, SseSender sse) {
        String awaitingSlot = state.awaiting() == null ? null : state.awaiting().slot();
        ActGate.ActGateResult r = actGate.evaluate(awaitingSlot, rawInput, attachmentFileId);

        if (r.action() == ActGate.ActGateAction.CANCEL) {
            markTask(state, BusinessTaskEntity.STATUS_CANCELLED);
            store.clear(state.userId(), state.sessionId());
            // 取消后按普通输入重新分类
            Classification c = classifier.classify(rawInput);
            if (c == null || c.fallback() || c.intent() == null) {
                return Outcome.ROUTE_KNOWLEDGE;
            }
            return startTask(DialogueState.idle(state.userId(), state.sessionId()), c, rawInput,
                    attachmentFileId, sse);
        }

        if (r.action() == ActGate.ActGateAction.FILLED) {
            // 回答解析入槽（CLARIFY_ANSWER 证据），续跑 ReAct
            DialogueState next = state
                    .withSlot(r.slot(), SlotValue.confirmed(r.value(), slotType(r.slot()),
                            SlotEvidence.of(SlotEvidenceSource.CLARIFY_ANSWER, state.turn() + 1, r.value())))
                    .withAwaiting(null)
                    .withStatus(DialogueStatus.ACTIVE)
                    .withInput(rawInput, attachmentFileId)
                    .withTurn(state.turn() + 1)
                    .bumpVersion();
            store.save(next);
            persistSlots(next);
            return runReAct(next, sse);
        }

        // NO_VALUE：重问或升级
        int retry = state.awaiting() == null ? 0 : state.awaiting().retry();
        if (retry + 1 >= props.getMaxClarifyRetries()) {
            markTask(state, BusinessTaskEntity.STATUS_ESCALATED_HUMAN);
            store.clear(state.userId(), state.sessionId());
            sse.send("slot_request", Map.of(
                    "intent", state.activeIntent() == null ? "" : state.activeIntent().code(),
                    "slot", awaitingSlot,
                    "prompt", "多次未能识别您提供的信息，已转人工协助，请稍候或重新发起",
                    "retry", retry + 1));
            sse.complete();
            return Outcome.HANDLED;
        }
        DialogueState next = state.withAwaiting(
                        state.awaiting().withRetry(retry + 1))
                .withInput(rawInput, attachmentFileId)
                .withTurn(state.turn() + 1)
                .bumpVersion();
        store.save(next);
        sse.send("slot_request", Map.of(
                "intent", state.activeIntent() == null ? "" : state.activeIntent().code(),
                "slot", awaitingSlot,
                "prompt", state.awaiting() == null ? "请提供 " + awaitingSlot : state.awaiting().prompt(),
                "retry", retry + 1));
        sse.complete();
        return Outcome.HANDLED;
    }

    private Outcome startTask(DialogueState state, Classification c, String rawInput,
                              String attachmentFileId, SseSender sse) {
        BusinessIntent intent = c.intent();
        IntentSpec spec = registry.spec(intent);
        int turn = state.turn() + 1;

        Map<String, SlotValue> confirmed = validator.confirmSlots(
                intent, spec, c.proposedSlots(), rawInput, attachmentFileId, turn);

        // 建业务任务行
        String bizTaskId = "bt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        BusinessTaskEntity task = new BusinessTaskEntity(bizTaskId, state.userId(), state.sessionId(), intent.code());
        taskDao.save(task);

        // 写状态：ACTIVE + 已确认槽位 + 本轮原文/附件
        DialogueState next = state;
        for (Map.Entry<String, SlotValue> e : confirmed.entrySet()) {
            next = next.withSlot(e.getKey(), e.getValue());
        }
        next = next.withActiveIntent(intent, c.confidence())
                .withStatus(DialogueStatus.ACTIVE)
                .withTaskId(bizTaskId)
                .withInput(rawInput, attachmentFileId)
                .withTurn(turn)
                .withAwaiting(null)
                .bumpVersion();
        store.save(next);
        persistSlots(next);

        // 闸C 必填槽位硬闸：有缺槽→确定性追问事件，结束本轮。
        // 不依赖 ReAct 模型首轮是否选工具（LLM 只判不裁），对齐文档 §1 闸C 与伪码
        // "CLASSIFY → 校验槽位 → 缺槽：slot_request 结束本轮 → 槽齐：工具分派"。
        SlotSpec missing = firstMissingRequired(spec, next);
        if (missing != null) {
            return requestSlot(next, missing.name(), missing.askPrompt(), 0, sse);
        }

        return runReAct(next, sse);
    }

    private Outcome runReAct(DialogueState state, SseSender sse) {
        IntentSpec spec = registry.spec(state.activeIntent());
        if (spec == null) {
            return Outcome.ROUTE_KNOWLEDGE;
        }
        PlanStep step = new PlanStep("biz-" + state.turn(), spec.goal(), spec.stepType());
        SimplePerception ctx = new SimplePerception(state.userId(), state.sessionId(), state.rawInput(),
                state.activeIntent().code());
        BusinessHandover handover = new BusinessHandover(
                trace, taskDao, state.taskId(), state.userId(), state.sessionId(), state.activeIntent().code());
        ReActGovernance governance = new ReActGovernance(
                ToolCaller.of(state.userId(), null, props.getDefaultScopes()),
                "business-agent", state.sessionId(), null, Map.of());

        try {
            ReActResult result = reActExecutor.execute(step, ctx, handover, maxIterations, () -> { }, governance);
            if (result.done()) {
                String answer = result.finalAnswer() == null ? "" : result.finalAnswer();
                emitAnswerAndFile(state, answer, sse);
                store.clear(state.userId(), state.sessionId());
                return Outcome.HANDLED;
            }
            // 达 maxIter 未出 FINAL：告知超时
            sse.send("delta", Map.of("text", "（业务处理超时，请稍后重试或重新发起）"));
            sse.send("done", Map.of());
            sse.complete();
            markTask(state, BusinessTaskEntity.STATUS_FAILED);
            store.clear(state.userId(), state.sessionId());
            return Outcome.HANDLED;
        } catch (SlotClarificationSignal e) {
            return handleClarification(state, e, sse);
        } catch (Exception e) {
            log.error("业务 ReAct 执行异常 userId={}: {}", state.userId(), e.getMessage(), e);
            sse.send("delta", Map.of("text", "（业务处理失败：" + messageOf(e) + "）"));
            sse.send("done", Map.of());
            sse.complete();
            markTask(state, BusinessTaskEntity.STATUS_FAILED);
            store.clear(state.userId(), state.sessionId());
            return Outcome.HANDLED;
        }
    }

    /** ReAct 环内缺槽信号 → 确定性追问（与闸C 同一出口）。 */
    private Outcome handleClarification(DialogueState state, SlotClarificationSignal e, SseSender sse) {
        return requestSlot(state, e.slot(), e.prompt(), 0, sse);
    }

    /** 找出第一个未 CONFIRMED 的必填槽（无则 null）。 */
    private SlotSpec firstMissingRequired(IntentSpec spec, DialogueState state) {
        if (spec == null) {
            return null;
        }
        for (SlotSpec s : spec.requiredSlots()) {
            if (state.confirmedSlot(s.name()) == null) {
                return s;
            }
        }
        return null;
    }

    /** 确定性追问：WAITING_SLOT + slot_request，结束本轮。 */
    private Outcome requestSlot(DialogueState state, String slot, String prompt,
                                int retry, SseSender sse) {
        DialogueState next = state
                .withStatus(DialogueStatus.WAITING_SLOT)
                .withAwaiting(new DialogueState.Awaiting(slot, prompt, retry))
                .bumpVersion();
        store.save(next);
        markTask(next, BusinessTaskEntity.STATUS_WAITING_SLOT);
        sse.send("slot_request", Map.of(
                "intent", next.activeIntent() == null ? "" : next.activeIntent().code(),
                "slot", slot,
                "prompt", prompt,
                "retry", retry));
        sse.complete();
        return Outcome.HANDLED;
    }

    /** 发最终答案（delta+done，经输出安全网关）+ 清关产物 file_ready。 */
    private void emitAnswerAndFile(DialogueState state, String answer, SseSender sse) {
        sse.send("delta", Map.of("text", answer));
        // 清关产物：任务行 result_file_id 非空 → 发 file_ready
        BusinessFileEntity customs = resultFileOf(state.taskId());
        if (customs != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("fileId", customs.getFileId());
            payload.put("fileName", customs.getOriginalName());
            payload.put("rowCount", customs.getRowCount());
            payload.put("downloadUrl", "/api/business/files/" + customs.getFileId() + "/download");
            sse.send("file_ready", payload);
        }
        sse.send("done", Map.of());
        sse.complete();
    }

    private BusinessFileEntity resultFileOf(String taskId) {
        if (taskId == null) {
            return null;
        }
        return taskDao.findByBizTaskId(taskId)
                .map(BusinessTaskEntity::getResultFileId)
                .filter(fid -> fid != null && !fid.isBlank())
                .map(fileService::fileOf)
                .orElse(null);
    }

    private void markTask(DialogueState state, String status) {
        if (state.taskId() == null) {
            return;
        }
        taskDao.findByBizTaskId(state.taskId()).ifPresent(t -> t.setStatus(status));
    }

    private void persistSlots(DialogueState state) {
        if (state.taskId() == null) {
            return;
        }
        try {
            String json = MAPPER.writeValueAsString(state.slots());
            String orderNo = state.confirmedSlot("orderNo") == null
                    ? null : state.confirmedSlot("orderNo").value();
            taskDao.findByBizTaskId(state.taskId()).ifPresent(t -> {
                t.setSlotsJson(json);
                if (orderNo != null) {
                    t.setOrderNo(orderNo);
                }
            });
        } catch (Exception e) {
            log.warn("槽位快照序列化失败: {}", e.getMessage());
        }
    }

    private void clearIfPresent(DialogueState state) {
        if (state.status() != DialogueStatus.IDLE) {
            markTask(state, BusinessTaskEntity.STATUS_CANCELLED);
        }
        store.clear(state.userId(), state.sessionId());
    }

    private String slotType(String slotName) {
        return "mappingExcel".equals(slotName) ? "file" : "string";
    }

    private static String messageOf(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
