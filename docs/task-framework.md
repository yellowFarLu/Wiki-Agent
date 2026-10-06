# 通用任务框架

> 核心代码：[application/task/](../src/main/java/com/wikiagent/application/task/TaskSubmissionService.java)
> 领域模型：[domain/task/](../src/main/java/com/wikiagent/domain/task) · 适配器：[infrastructure/task/](../src/main/java/com/wikiagent/infrastructure/task)

## 1. 存储模型（V9）

| 表 | 用途 |
|---|---|
| `task_instance` | 任务实例与状态机（真相源）；`biz_key` 唯一约束保证幂等 |
| `task_step` | 步骤级 Checkpoint（DONE 步骤重启不重跑；动态注册步骤先合并） |
| `task_event` | 任务事件流水（审计/重放依据） |
| `human_task` | 人工介入任务（INPUT 补充 / DIRECT_RESOLVE 直接终结） |
| `handover_checklist` / `handover_node` / `handover_data_ref` / `handover_abandoned_path` | Agent 交接清单四表 |

## 2. 状态机

```text
PENDING ──CAS抢租约──► DISPATCH ──► RUNNING ──► COMPLETED
                          │            │  ▲       FAILED（attempt<=3，退避重试）
                          │            │  │       CANCELLED
                          │            ├──┘       TIMEOUT（deadline 到点）
                          │            ├──► WAITING_HUMAN ──补充──► RUNNING
                          │            └──► SUSPENDED ──resume──► RUNNING
                          └── 滞留 PENDING：DispatchCompensationJob 5s 扫描补偿
```

关键机制：

- **租约**：Worker 以 CAS（PENDING/DISPATCH + 无主或租约过期）抢占；owner 心跳续租（owner + RUNNING 双条件），TTL 30s；
- **恢复**：[TaskRecoveryJob.java](../src/main/java/com/wikiagent/application/task/TaskRecoveryJob.java) 每 15s 扫描过期租约并重投；
- **防误杀**：心跳正常的长任务不会被其他 Worker 接管；执行方发现租约丢失抛 `LeaseLostSignalException` 主动让出击；
- **看门狗**：[TaskWatchdog.java](../src/main/java/com/wikiagent/application/task/TaskWatchdog.java) 处理整体 deadline（默认 1800s）；
- **控制**：[TaskControlService.java](../src/main/java/com/wikiagent/application/task/TaskControlService.java) 的 suspend/resume/cancel 双写 MySQL+Redis，version CAS 防并发冲突（`OptimisticControlConflictException`）；
- **退避**：[Backoff.java](../src/main/java/com/wikiagent/application/task/Backoff.java)：10s → 30s → 2min，最多 3 次尝试；
- **错误分类**：[ErrorClassifier.java](../src/main/java/com/wikiagent/application/task/ErrorClassifier.java) 区分可重试/致命错误。

## 3. 投递与执行

```text
TaskSubmissionService -> task_instance 落库
  -> TaskMessageSink 投递：
       TASK_MQ=rocketmq：RocketMQ Tag 区分 INGEST / AGENT，独立消费组
       TASK_MQ=local   ：JVM 本地调度（零中间件启动）
  -> TaskWorker 抢单 -> TaskHandlerRegistry 找 handler -> 更新步骤/状态
```

参数（application.yml）：dispatch 扫描间隔 5s；心跳 10s；恢复扫描 15s；单步超时 300s；Agent 步骤 180s；并发度 ingest=2 / agent=4。

Handler：

- [IngestTaskHandler.java](../src/main/java/com/wikiagent/application/task/handler/IngestTaskHandler.java) — 驱动入库六步流水线（见 [ingestion.md](ingestion.md)）；
- [AgentTaskHandler.java](../src/main/java/com/wikiagent/application/task/handler/AgentTaskHandler.java) + [AgentTaskLauncher.java](../src/main/java/com/wikiagent/application/task/handler/AgentTaskLauncher.java) — 驱动 PERO Agent 执行。

## 4. 进度推送

[TaskStreamBus.java](../src/main/java/com/wikiagent/application/task/TaskStreamBus.java) + [BusSseSender.java](../src/main/java/com/wikiagent/application/task/handler/BusSseSender.java) 将步骤进度以 [StreamEvent](../src/main/java/com/wikiagent/application/task/StreamEvent.java) 推送到前端；[TaskQueryService.java](../src/main/java/com/wikiagent/application/task/TaskQueryService.java) 提供状态查询兜底。

## 5. 人工介入

[HumanTaskService.java](../src/main/java/com/wikiagent/application/task/HumanTaskService.java)：

- `INPUT`：补充信息后从 Checkpoint 继续后续步骤；
- `DIRECT_RESOLVE`：人工直接给出结论终结任务；
- 典型触发：加密 PDF 需密码（DECRYPT 人工任务）、Agent 主动暂停等待用户输入。
