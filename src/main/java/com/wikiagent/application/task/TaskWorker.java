package com.wikiagent.application.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.observability.trace.MdcCallable;
import com.wikiagent.application.observability.trace.MdcRunnable;
import com.wikiagent.application.observability.trace.TaskTraceConveyor;
import com.wikiagent.config.TaskProperties;
import com.wikiagent.domain.observability.trace.TraceIdGenerator;
import com.wikiagent.domain.task.ActorType;
import com.wikiagent.domain.task.CancelSignalException;
import com.wikiagent.domain.task.ErrorCode;
import com.wikiagent.domain.task.FatalTaskException;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.HumanTaskStatus;
import com.wikiagent.domain.task.LeaseLostSignalException;
import com.wikiagent.domain.task.PauseSignalException;
import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.StepResult;
import com.wikiagent.domain.task.TaskEvent;
import com.wikiagent.domain.task.TaskEventType;
import com.wikiagent.domain.task.TaskExecutionContext;
import com.wikiagent.domain.task.TaskHandler;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStateMachine;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.TaskStep;
import com.wikiagent.domain.task.ports.ControlFlagPort;
import com.wikiagent.domain.task.ports.HumanTaskRepositoryPort;
import com.wikiagent.domain.task.ports.LeasePort;
import com.wikiagent.domain.task.ports.ProgressPort;
import com.wikiagent.domain.task.ports.TaskDispatcherPort;
import com.wikiagent.domain.task.ports.TaskEventRepositoryPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskStepRepositoryPort;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务执行器（规格 3.1）：MQ 消费入口 → 护栏 → CAS 租约 → 心跳续租 →
 * planSteps 落库/断点续跑 → 逐步执行（边界控制检查 + 步骤超时）→ 重试/终态落库。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class TaskWorker implements TaskMessageSink {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);

    /** 看门狗延迟消息等级：RocketMQ level 4=30s，与默认 leaseTtl 30s 对齐。 */
    private static final int WATCHDOG_DELAY_LEVEL = 4;

    private final TaskRepositoryPort taskRepo;
    private final TaskStepRepositoryPort stepRepo;
    private final TaskEventRepositoryPort eventRepo;
    private final HumanTaskRepositoryPort humanRepo;
    private final LeasePort leasePort;
    private final ControlFlagPort controlFlagPort;
    private final ProgressPort progressPort;
    private final TaskDispatcherPort dispatcher;
    private final TaskStreamBus streamBus;
    private final TaskProperties props;
    private final TaskHandlerRegistry registry;
    private final ErrorClassifier errorClassifier;

    private final String workerId = "w-" + UUID.randomUUID().toString().substring(0, 8);

    /** 字段注入：保持 12 参构造以支持测试手工实例化（手工 new 时为 null，onWatchdog 空转）。 */
    @Autowired(required = false)
    private TaskWatchdog watchdog;

    /**
     * 子项目 I（AC-I1）：任务 traceId 跨线程传送带（字段注入保持构造稳定；
     * 手工 new 的测试中为 null，worker 对每次投递生成新 traceId）。
     */
    @Autowired(required = false)
    private TaskTraceConveyor traceConveyor;

    /**
     * 子项目 I（AC-I2）：任务时长/失败计量钩子（字段注入；为 null 时不计量）。
     */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;
    private final ExecutorService stepPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "task-worker-step");
        t.setDaemon(true);
        return t;
    });

    public TaskWorker(TaskRepositoryPort taskRepo,
                      TaskStepRepositoryPort stepRepo,
                      TaskEventRepositoryPort eventRepo,
                      HumanTaskRepositoryPort humanRepo,
                      LeasePort leasePort,
                      ControlFlagPort controlFlagPort,
                      ProgressPort progressPort,
                      TaskDispatcherPort dispatcher,
                      TaskStreamBus streamBus,
                      TaskProperties props,
                      TaskHandlerRegistry registry,
                      ErrorClassifier errorClassifier) {
        this.taskRepo = taskRepo;
        this.stepRepo = stepRepo;
        this.eventRepo = eventRepo;
        this.humanRepo = humanRepo;
        this.leasePort = leasePort;
        this.controlFlagPort = controlFlagPort;
        this.progressPort = progressPort;
        this.dispatcher = dispatcher;
        this.streamBus = streamBus;
        this.props = props;
        this.registry = registry;
        this.errorClassifier = errorClassifier;
    }

    @PreDestroy
    void stop() {
        stepPool.shutdownNow();
    }

    /** 单步默认超时：StepDef 显式声明优先，否则取全局默认。测试可覆盖。 */
    protected Duration stepTimeout(StepDef def) {
        if (def.timeoutSec() != null && def.timeoutSec() > 0) {
            return Duration.ofSeconds(def.timeoutSec());
        }
        return Duration.ofSeconds(Math.max(1, props.getDefaults().getStepTimeoutSec()));
    }

    @Override
    public void onMessage(String taskId, String taskType) {
        // 子项目 I（AC-I1）：跨线程传送带取回触发请求的 traceId；
        // 无绑定（恢复扫描/retry/看门狗/RocketMQ 跨进程）时生成新 traceId
        String inherited = traceConveyor == null ? null : traceConveyor.consume(taskId);
        String traceId = inherited != null ? inherited : TraceIdGenerator.generate();
        MDC.put("traceId", traceId);
        Timer.Sample sample = Timer.start();
        try {
            processMessage(taskId, taskType);
        } finally {
            recordTaskMetrics(taskId, taskType, sample);
            MDC.remove("traceId");
        }
    }

    /** 单次任务消息处理（onMessage 的 MDC/计量边界之内）。 */
    private void processMessage(String taskId, String taskType) {
        // ① 幂等护栏：任务不存在或状态不可领取（非 PENDING/DISPATCH）直接 ACK 跳过
        TaskInstance current = taskRepo.findByTaskId(taskId).orElse(null);
        if (current == null || !TaskStateMachine.canLease(current.status())) {
            return;
        }
        // ② CAS 抢租约（DB 权威 + Redis 租约双写）；抢不到说明另一副本已领取
        Instant expireAt = Instant.now().plus(Duration.ofSeconds(props.getLeaseTtlSec()));
        if (!taskRepo.casLease(taskId, workerId, expireAt)) {
            return;
        }
        leasePort.tryAcquire(taskId, workerId, Duration.ofSeconds(props.getLeaseTtlSec()));
        TaskInstance task = taskRepo.findByTaskId(taskId).orElseThrow();
        // LEASE：PENDING/DISPATCH → RUNNING
        task = taskRepo.save(task.withStatus(TaskStateMachine.transition(task.status(), TaskEventType.LEASE)));
        eventRepo.append(event(taskId, TaskEventType.LEASE, null));
        // AC-I1：任务正式领取（MDC traceId 与触发请求同源，恢复/retry 扫描时为 worker 新生成）
        log.info("任务领取开始执行: taskId={} taskType={} attempt={} traceId={}",
                taskId, taskType, task.attempt(), MDC.get("traceId"));
        // 看门狗双保险：leaseTtl 后核对租约/心跳是否存活（规格 1.4）
        dispatcher.dispatchWatchdog(taskId, workerId, WATCHDOG_DELAY_LEVEL);

        ScheduledExecutorService heartbeat = startHeartbeat(taskId);
        try {
            runTask(task, taskType);
        } catch (Throwable t) {
            // runTask 步骤循环未兜底的 Throwable（含 Error，如 StackOverflowError）此前会被
            // MQ 客户端静默吞掉、仅在 30s 后表现为“看门狗判定心跳停更”，极难排查；这里先落
            // 完整堆栈再原样抛出，不改变既有重试/回收语义。
            log.error("任务执行抛出未捕获 Throwable，交回上层重试/回收 taskId={} attempt={} type={}: {}",
                    taskId, task.attempt(), t.getClass().getName(), t.getMessage(), t);
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error er) {
                throw er;
            }
            throw new RuntimeException(t);
        } finally {
            heartbeat.shutdownNow();
            TaskControlContext.clear();
            leasePort.release(taskId, workerId);
        }
    }

    /**
     * 子项目 I（AC-I2）：任务执行段计时与失败计数。
     * 仅在任务进入终态/挂起态后记录（租约易主让位时任务仍 RUNNING，不计以避免重复）。
     */
    private void recordTaskMetrics(String taskId, String taskType, Timer.Sample sample) {
        if (meterRegistry == null) {
            return;
        }
        try {
            TaskInstance latest = taskRepo.findByTaskId(taskId).orElse(null);
            if (latest == null) {
                return;
            }
            String result;
            switch (latest.status()) {
                case COMPLETED -> result = "completed";
                case FAILED -> result = "failed";
                case CANCELLED -> result = "cancelled";
                case WAITING_HUMAN -> result = "waiting_human";
                case SUSPENDED -> result = "suspended";
                default -> {
                    return;
                }
            }
            sample.stop(Timer.builder("wikiagent.task.duration.seconds")
                    .description("任务执行耗时（秒）")
                    .tag("taskType", taskType)
                    .tag("result", result)
                    .register(meterRegistry));
            if (latest.status() == TaskStatus.FAILED) {
                String reason = latest.errorCode() == null ? "UNKNOWN" : latest.errorCode().name();
                meterRegistry.counter("wikiagent.task.failed.total",
                        "type", taskType, "reason", reason).increment();
            }
        } catch (Exception e) {
            log.warn("任务指标记录失败（不影响主链路）taskId={}: {}", taskId, e.getMessage());
        }
    }

    @Override
    public void onWatchdog(String taskId, String ownerWorkerId) {
        // 看门狗核对（Task 9）：仍 RUNNING 且 owner 匹配且心跳停更 → 复用统一回收
        if (watchdog != null) {
            watchdog.check(taskId, ownerWorkerId);
        }
    }

    private ScheduledExecutorService startHeartbeat(String taskId) {
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-worker-heartbeat-" + taskId);
            t.setDaemon(true);
            return t;
        });
        AtomicInteger progressRef = new AtomicInteger(
                taskRepo.findByTaskId(taskId).map(TaskInstance::progressPercent).orElse(0));
        heartbeat.scheduleAtFixedRate(MdcRunnable.wrap(() -> {
            try {
                Instant now = Instant.now();
                leasePort.renew(taskId, workerId, Duration.ofSeconds(props.getLeaseTtlSec()));
                // DB 权威续租：lease_expire_at/heartbeat_at 同步刷新（恢复扫描以 DB 为准）；
                // 仅本 worker 持有且仍 RUNNING 才生效，返回 false 说明租约易主/已被回收
                boolean mine = taskRepo.renewLease(taskId, workerId,
                        now.plus(Duration.ofSeconds(props.getLeaseTtlSec())), now);
                if (!mine) {
                    log.warn("心跳续约发现租约易主或任务已回收，本 worker 让位: taskId={} worker={}", taskId, workerId);
                }
                progressPort.publish(taskId, progressRef.get(), null);
            } catch (Exception ex) {
                // 心跳失败不影响主流程；租约过期由恢复扫描兜底（Task 9）。
                // 不可完全静默：持续失败会被看门狗判“心跳停更”而反复回收任务，必须留下可观测证据。
                log.warn("心跳续租失败（将由恢复扫描兜底）taskId={} worker={}: {}",
                        taskId, workerId, ex.toString());
            }
        }), props.getHeartbeatSec(), props.getHeartbeatSec(), TimeUnit.SECONDS);
        return heartbeat;
    }

    private void runTask(TaskInstance task, String taskType) {
        String taskId = task.taskId();
        TaskHandler handler = registry.handler(taskType).orElse(null);
        if (handler == null) {
            failTask(task, ErrorCode.INTERNAL, "未注册的任务处理器: " + taskType);
            return;
        }
        TaskExecutionContext ctx = new TaskExecutionContext(taskId, task.taskType(),
                task.payload(), humanInputs(taskId), task.attempt());
        // ④ planSteps 落库（幂等）+ 已存在步骤回填（断点续跑）
        List<StepDef> plan = new ArrayList<>(handler.planSteps(task.payload()));
        plan.sort(Comparator.comparingInt(StepDef::no));
        stepRepo.saveAllIfAbsent(taskId, plan);
        for (TaskStep step : stepRepo.findByTaskIdOrderByStepNo(taskId)) {
            if (step.checkpoint() != null && !validJson(step.checkpoint())) {
                // 恢复数据损坏：重试无意义，直接致命失败，不再投递（Task 8 裁定）
                stepRepo.markFailed(taskId, step.stepNo(), "步骤 checkpoint JSON 损坏", Instant.now());
                failTask(task, handler, ctx, ErrorCode.INTERNAL, "步骤 checkpoint JSON 损坏: stepNo=" + step.stepNo());
                return;
            }
            ctx.seedStep(new StepDef(step.stepNo(), step.stepType(), step.stepName(), false, null),
                    step.checkpoint());
        }

        AtomicReference<String> resultRef = new AtomicReference<>();
        int startNo = stepRepo.firstNonDoneStepNo(taskId);
        if (startNo == -1) { // 全部步骤已 DONE（崩溃于 COMPLETE 前）：直接补终态
            completeTask(task, resultRef.get());
            return;
        }
        // 索引遍历：循环内 mergeRegistered 会向 plan 追加动态注册步骤，不能用 for-each
        int idx = 0;
        while (idx < plan.size()) {
            // 先并入动态注册步骤再取当前步（AGENT：PLAN 注册的 NODE 必须先于 GENERATE 执行）
            mergeRegistered(ctx, plan);
            StepDef def = plan.get(idx);
            idx++;
            if (def.no() < startNo) {
                continue;
            }
            // ⑤ 步骤边界控制检查（主线程双读 + 租约归属校验）
            TaskControlContext.set(new TaskControlContext.Ctx(taskId, controlFlagPort, taskRepo, workerId));
            try {
                TaskControlContext.checkpointAndThrowIfSignaled();
            } catch (LeaseLostSignalException l) {
                log.info("步骤边界发现租约易主，让位: taskId={} step={}", taskId, def.no());
                return;
            } catch (PauseSignalException p) {
                suspendTask(task);
                return;
            } catch (CancelSignalException c) {
                cancelTask(task, handler, ctx);
                return;
            } finally {
                TaskControlContext.clear();
            }

            // 任务级超时（规格 3.3）：超 deadline 按 TIMEOUT 走统一重试/终态分类
            if (isDeadlineExceeded(task)) {
                stepRepo.markFailed(taskId, def.no(), "任务级超时(deadlineSec)", Instant.now());
                classifyAndTerminate(task, handler, ctx, ErrorCode.TIMEOUT, "任务级超时(deadlineSec): " + def.name());
                return;
            }

            // ⑥ 逐步执行：池内 submit + 步骤超时
            stepRepo.markRunning(taskId, def.no(), Instant.now());
            eventRepo.append(event(taskId, TaskEventType.STEP_START, stepDetail(def)));
            Future<StepResult> future = stepPool.submit(MdcCallable.wrap(() -> {
                TaskControlContext.set(new TaskControlContext.Ctx(taskId, controlFlagPort, taskRepo, workerId));
                try {
                    ctx.setCurrentStepNo(def.no());
                    return handler.executeStep(ctx);
                } finally {
                    TaskControlContext.clear();
                }
            }));
            StepResult result;
            try {
                result = future.get(stepTimeout(def).toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                stepRepo.markFailed(taskId, def.no(),
                        "步骤超时(" + stepTimeout(def).toSeconds() + "s)", Instant.now());
                classifyAndTerminate(task, handler, ctx, ErrorCode.TIMEOUT, "步骤超时: " + def.name());
                return;
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                if (cause instanceof LeaseLostSignalException l) {
                    log.info("步骤执行中发现租约易主，让位: taskId={} step={}", taskId, def.no());
                    return;
                }
                if (cause instanceof PauseSignalException) {
                    suspendTask(task);
                    return;
                }
                if (cause instanceof CancelSignalException) {
                    cancelTask(task, handler, ctx);
                    return;
                }
                if (cause instanceof HumanRequiredException h) {
                    waitHuman(task, def, h);
                    return;
                }
                stepRepo.markFailed(taskId, def.no(), String.valueOf(cause.getMessage()), Instant.now());
                ErrorClassifier.Decision decision = errorClassifier.classify(cause);
                classifyAndTerminate(task, handler, ctx, decision.code(), cause.getMessage());
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                failTask(task, handler, ctx, ErrorCode.INTERNAL, "worker 线程被中断");
                return;
            }

            // 步骤成功：落步骤终态 + 推进进度
            stepRepo.markDone(taskId, def.no(), result.skip() ? null : result.checkpointJson(), Instant.now());
            // 动态注册步骤立即持久化（AgentTaskExecutionContext 契约）：否则恢复时
            // 未开始执行的 NODE 步无行可种子，会被 firstNonDone 跳过
            stepRepo.saveAllIfAbsent(taskId, ctx.registeredSteps());
            eventRepo.append(event(taskId, TaskEventType.STEP_DONE, stepDetail(def)));
            if (result.resultRef() != null) {
                resultRef.set(result.resultRef());
            }
            if (!result.skip()) {
                progressPort.publish(taskId, result.progressPercent(), def.name());
                streamBus.publish(new StreamEvent(taskId, "progress",
                        MAPPER.createObjectNode().put("percent", result.progressPercent())
                                .put("step", def.name())));
            }
        }
        completeTask(task, resultRef.get());
    }

    /** 任务级超时（规格 3.3）：args.deadlineSec 优先，默认 defaults.deadline-sec；自 createdAt 起算。 */
    private boolean isDeadlineExceeded(TaskInstance task) {
        JsonNode d = task.payload() == null ? null : task.payload().get("deadlineSec");
        int deadlineSec = d != null && d.isInt() && d.intValue() > 0
                ? d.intValue() : props.getDefaults().getDeadlineSec();
        if (deadlineSec <= 0 || task.createdAt() == null) {
            return false;
        }
        return Instant.now().isAfter(task.createdAt().plus(Duration.ofSeconds(deadlineSec)));
    }

    /** ⑦ 可重试且未超上限 → RETRY 退避重投；否则 FAILED（并触发 handler.onFailed 副作用钩子）。 */
    private void classifyAndTerminate(TaskInstance task, TaskHandler handler, TaskExecutionContext ctx,
                                      ErrorCode code, String msg) {
        if (code.retryable() && task.attempt() < task.maxAttempts()) {
            retryTask(task, code, msg);
        } else {
            failTask(task, handler, ctx, code, msg);
        }
    }

    private void retryTask(TaskInstance task, ErrorCode code, String msg) {
        if (yieldToCancelFlow(task.taskId(), task)) {
            return;
        }
        // 以库内最新记录为基：步骤执行期间控制面的 suspendReason/controlVersion 不被覆盖
        TaskInstance base = taskRepo.findByTaskId(task.taskId()).orElse(task);
        int newAttempt = task.attempt() + 1;
        TaskInstance updated = base
                .withStatus(TaskStateMachine.transition(base.status(), TaskEventType.RETRY))
                .withAttempt(newAttempt)
                .withNextRunAt(Instant.now().plus(Backoff.durationForAttempt(newAttempt)))
                .withError(code, msg)
                .withClearLease();
        taskRepo.save(updated);
        eventRepo.append(event(task.taskId(), TaskEventType.RETRY,
                MAPPER.createObjectNode().put("errorCode", code.name())
                        .put("attempt", newAttempt)
                        .put("backoffSec", Backoff.durationForAttempt(newAttempt).toSeconds())));
        dispatcher.dispatch(task.taskId(), task.taskType(), Backoff.delayLevelForAttempt(newAttempt));
    }

    private void failTask(TaskInstance task, ErrorCode code, String msg) {
        failTask(task, null, null, code, msg);
    }

    private void failTask(TaskInstance task, TaskHandler handler, TaskExecutionContext ctx,
                          ErrorCode code, String msg) {
        if (yieldToCancelFlow(task.taskId(), task)) {
            return;
        }
        TaskInstance base = taskRepo.findByTaskId(task.taskId()).orElse(task);
        TaskInstance updated = base
                .withStatus(TaskStateMachine.transition(base.status(), TaskEventType.FAIL))
                .withError(code, msg)
                .withClearLease();
        taskRepo.save(updated);
        eventRepo.append(event(task.taskId(), TaskEventType.FAIL,
                MAPPER.createObjectNode().put("errorCode", code.name()).put("msg", String.valueOf(msg))));
        streamBus.publish(new StreamEvent(task.taskId(), "error",
                MAPPER.createObjectNode().put("errorCode", code.name()).put("msg", String.valueOf(msg))));
        // 终态失败副作用钩子（如 INGEST 回滚文档状态）；钩子异常不影响任务终态
        if (handler != null && ctx != null) {
            try {
                handler.onFailed(ctx, code, msg);
            } catch (Exception e) {
                log.warn("任务失败钩子执行异常 taskId={} type={}: {}", task.taskId(), task.taskType(), e.getMessage());
            }
        }
    }

    private void suspendTask(TaskInstance task) {
        if (yieldToCancelFlow(task.taskId(), task)) {
            return;
        }
        TaskInstance base = taskRepo.findByTaskId(task.taskId()).orElse(task);
        TaskInstance updated = base
                .withStatus(TaskStateMachine.transition(base.status(), TaskEventType.SUSPEND))
                .withClearLease();
        taskRepo.save(updated);
        eventRepo.append(event(task.taskId(), TaskEventType.SUSPEND, null));
        streamBus.publish(new StreamEvent(task.taskId(), "progress",
                MAPPER.createObjectNode().put("status", TaskEventType.SUSPEND.name())));
    }

    private void cancelTask(TaskInstance task, TaskHandler handler, TaskExecutionContext ctx) {
        String taskId = task.taskId();
        // REQUEST_CANCEL：RUNNING/SUSPENDED → CANCELING（取消 API 可能已抢先迁移，幂等跳过）
        TaskInstance base = taskRepo.findByTaskId(taskId).orElse(task);
        if (base.status() != TaskStatus.CANCELING) {
            taskRepo.save(base.withStatus(
                    TaskStateMachine.transition(base.status(), TaskEventType.REQUEST_CANCEL)));
            eventRepo.append(event(taskId, TaskEventType.REQUEST_CANCEL, null));
        }
        handler.onCancel(ctx);
        // CANCEL：CANCELING → CANCELLED（终态）
        finalizeCancel(taskId, taskRepo.findByTaskId(taskId).orElse(base));
    }

    /** CANCELING → CANCELLED（终态）：cancelTask 与让位路径共用。 */
    private void finalizeCancel(String taskId, TaskInstance canceling) {
        TaskInstance cancelled = canceling
                .withStatus(TaskStateMachine.transition(canceling.status(), TaskEventType.CANCEL))
                .withClearLease();
        taskRepo.save(cancelled);
        eventRepo.append(event(taskId, TaskEventType.CANCEL, null));
        streamBus.publish(new StreamEvent(taskId, "done",
                MAPPER.createObjectNode().put("status", "CANCELLED")));
    }

    /**
     * 控制面取消流程已介入时 worker 让位：CANCELING 代为收尾到 CANCELLED
     * （取消请求到达时步骤已结束、不再有边界检查点），已 CANCELLED 直接让位。
     * 返回 true 表示取消流程已接管，调用方跳过本次保存与后续动作。
     */
    private boolean yieldToCancelFlow(String taskId, TaskInstance snapshot) {
        TaskInstance base = taskRepo.findByTaskId(taskId).orElse(snapshot);
        if (base.status() == TaskStatus.CANCELLED) {
            return true;
        }
        if (base.status() == TaskStatus.CANCELING) {
            finalizeCancel(taskId, base);
            return true;
        }
        return false;
    }

    private void waitHuman(TaskInstance task, StepDef def, HumanRequiredException h) {
        String taskId = task.taskId();
        if (yieldToCancelFlow(taskId, task)) {
            return;
        }
        // 幂等：同一 (taskId, stepNo, kind) 的未决接管点已存在则复用，
        // 防 Worker 重跑同一 HumanRequired 步骤重复建单撞 uk_task_step_kind
        boolean exists = humanRepo.findByTaskId(taskId).stream().anyMatch(x ->
                x.stepNo() == def.no() && x.kind() == h.getKind()
                        && (x.status() == HumanTaskStatus.OPEN || x.status() == HumanTaskStatus.CLAIMED));
        if (!exists) {
            humanRepo.save(new HumanTask(null, taskId, def.no(), h.getKind(), h.getTitle(),
                    h.getInstruction(), h.getFormSchema(), null, HumanTaskStatus.OPEN,
                    null, null, null, null, 0, Instant.now()));
        }
        TaskInstance base = taskRepo.findByTaskId(taskId).orElse(task);
        TaskInstance updated = base
                .withStatus(TaskStateMachine.transition(base.status(), TaskEventType.WAIT_HUMAN))
                .withClearLease();
        taskRepo.save(updated);
        eventRepo.append(event(taskId, TaskEventType.WAIT_HUMAN,
                MAPPER.createObjectNode().put("stepNo", def.no())
                        .put("kind", h.getKind().name())
                        .put("title", h.getTitle())));
        streamBus.publish(new StreamEvent(taskId, "progress",
                MAPPER.createObjectNode().put("status", "WAITING_HUMAN")
                        .put("stepNo", def.no())));
    }

    private void completeTask(TaskInstance task, String resultRef) {
        String taskId = task.taskId();
        if (yieldToCancelFlow(taskId, task)) {
            return;
        }
        TaskInstance base = taskRepo.findByTaskId(taskId).orElse(task);
        TaskInstance updated = base
                .withStatus(TaskStateMachine.transition(base.status(), TaskEventType.COMPLETE))
                .withProgress(100)
                .withResultRef(resultRef)
                .withClearLease();
        taskRepo.save(updated);
        eventRepo.append(event(taskId, TaskEventType.COMPLETE,
                resultRef == null ? null : MAPPER.createObjectNode().put("resultRef", resultRef)));
        progressPort.publish(taskId, 100, null);
        streamBus.publish(new StreamEvent(taskId, "done",
                MAPPER.createObjectNode().put("status", "COMPLETED")
                        .put("resultRef", String.valueOf(resultRef))));
    }

    /**
     * 已决人工接管点的表单值并入上下文（RESOLVED INPUT/DECRYPT → 字段平铺）。
     * DECRYPT 与 INPUT 同为"填表续跑"语义（口令字段 decryptPassword）；
     * REVIEW/TOOL_APPROVAL 是审批语义，不靠表单值恢复，不并入。
     */
    private Map<String, JsonNode> humanInputs(String taskId) {
        Map<String, JsonNode> inputs = new HashMap<>();
        for (HumanTask ht : humanRepo.findByTaskId(taskId)) {
            if (ht.status() == HumanTaskStatus.RESOLVED && ht.formValue() != null
                    && (ht.kind() == HumanTaskKind.INPUT || ht.kind() == HumanTaskKind.DECRYPT)) {
                ht.formValue().fields().forEachRemaining(e -> inputs.put(e.getKey(), e.getValue()));
            }
        }
        return inputs;
    }

    /** AGENT 动态注册步骤并入执行队列（同 no 幂等）。 */
    private void mergeRegistered(TaskExecutionContext ctx, List<StepDef> plan) {
        for (StepDef registered : ctx.registeredSteps()) {
            if (plan.stream().noneMatch(p -> p.no() == registered.no())) {
                plan.add(registered);
            }
        }
        plan.sort(Comparator.comparingInt(StepDef::no));
    }

    private boolean validJson(String json) {
        try {
            MAPPER.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private TaskEvent event(String taskId, TaskEventType type, JsonNode detail) {
        return new TaskEvent(taskId, type, ActorType.WORKER, workerId, detail, Instant.now());
    }

    private JsonNode stepDetail(StepDef def) {
        return MAPPER.createObjectNode().put("stepNo", def.no()).put("stepType", def.type());
    }
}
