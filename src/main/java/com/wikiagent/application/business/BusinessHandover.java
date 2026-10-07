package com.wikiagent.application.business;

import com.wikiagent.application.agent.pero.Handover;
import com.wikiagent.application.agent.pero.TraceService;
import com.wikiagent.domain.agent.Plan;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;
import com.wikiagent.infrastructure.business.BusinessTaskEntity;
import com.wikiagent.infrastructure.business.BusinessTaskJpaDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务意图 Handover：把业务 ReAct 轮次的节点生命周期转写为 V24 business_task 与 agent_trace，
 * 与 PERO 轮次留痕口径一致（可审计、可回放）。
 * <p>
 * 复用 {@link TraceService} 落过程 span；{@link BusinessTaskJpaDao} 落业务事实行。
 */
public class BusinessHandover implements Handover {

    private static final Logger log = LoggerFactory.getLogger(BusinessHandover.class);

    private final TraceService trace;
    private final BusinessTaskJpaDao taskDao;
    private final String bizTaskId;
    private final String userId;
    private final String sessionId;
    private final String intent;

    public BusinessHandover(TraceService trace, BusinessTaskJpaDao taskDao,
                            String bizTaskId, String userId, String sessionId, String intent) {
        this.trace = trace;
        this.taskDao = taskDao;
        this.bizTaskId = bizTaskId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.intent = intent;
    }

    @Override
    public void init(String u, String s, String userInput) {
        // 任务行已在网关创建，这里只记 trace
    }

    @Override
    public void declarePlan(Plan plan) {
        // 业务流单节点，无 plan 阶段
    }

    @Override
    public void startNode(PlanStep step) {
        trace.start(userId + ":" + sessionId, userId, "biz-step:" + step.id(), step.goal());
    }

    @Override
    public void completeNode(PlanStep step, ReActResult result) {
        taskDao.findByBizTaskId(bizTaskId).ifPresent(t -> {
            t.setStatus(BusinessTaskEntity.STATUS_COMPLETED);
            t.setCompletedAt(java.time.LocalDateTime.now());
        });
        log.info("[biz-handover] completeNode bizTaskId={} intent={} done={}",
                bizTaskId, intent, result.done());
    }

    @Override
    public void failNode(PlanStep step, String message) {
        taskDao.findByBizTaskId(bizTaskId).ifPresent(t -> {
            t.setStatus(BusinessTaskEntity.STATUS_FAILED);
            t.setError(message);
        });
    }

    @Override
    public void abandonPath(PlanStep step, String reason) {
        taskDao.findByBizTaskId(bizTaskId).ifPresent(t -> {
            t.setStatus(BusinessTaskEntity.STATUS_CANCELLED);
        });
    }

    @Override
    public void persistEvent(String u, String s, String answer) {
        // 业务事件由 gateway 统一落 business_task + agent_trace；此处不重复落历史事件库
    }

    /** 更新任务状态（供网关在追问/升级时原地更新）。 */
    public void updateStatus(String status) {
        taskDao.findByBizTaskId(bizTaskId).ifPresent(t -> t.setStatus(status));
    }

    /** 更新槽位快照 JSON。 */
    public void updateSlots(String slotsJson, String orderNo) {
        taskDao.findByBizTaskId(bizTaskId).ifPresent(t -> {
            t.setSlotsJson(slotsJson);
            if (orderNo != null) {
                t.setOrderNo(orderNo);
            }
        });
    }
}
