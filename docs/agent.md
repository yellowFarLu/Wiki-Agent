# Agent：PERO 主循环、多 Agent 与分层记忆

> PERO 代码：[application/agent/pero/](../src/main/java/com/wikiagent/application/agent/pero/PeroAgent.java)
> 多 Agent：[application/multiagent/](../src/main/java/com/wikiagent/application/multiagent/MultiAgentOrchestrator.java)

## 1. PERO 主循环

[PeroAgent.java](../src/main/java/com/wikiagent/application/agent/pero/PeroAgent.java) 默认开启（`wikiagent.pero.enabled=true`）：

```text
Perception（SimplePerception：理解输入 + 拉取记忆/交接上下文）
  → Plan      PeroPlanner：qwen-max 多节点规划，产出可执行任务清单
  → Execute   每个节点由 ReActExecutor 执行 ReAct 循环
              （wikiagent.pero.react.max-iterations=8：思考 → 行动 → 观察）
              ReActGovernance 约束行动边界；PeroToolExecutor 真实分派工具
  → Reflect   LlmReflector 校验节点产出，未达标回 Execute 重试
  → Generate  Generator / SimpleGenerator 汇总最终答案
  → Optimize  SimpleOptimizer 沉淀经验至历史事件库（NoOpOptimizer 可关）
              BudgetTracker 全程统计 token 与费用预算
```

辅助组件：[EpisodicMemory.java](../src/main/java/com/wikiagent/application/agent/pero/EpisodicMemory.java)（情景记忆）、[Handover.java](../src/main/java/com/wikiagent/application/agent/pero/Handover.java) 与 [MemoryHandover.java](../src/main/java/com/wikiagent/application/agent/pero/MemoryHandover.java)（交接）、[TraceService.java](../src/main/java/com/wikiagent/application/agent/pero/TraceService.java)（过程追踪）、[PeroLoopHook.java](../src/main/java/com/wikiagent/application/agent/pero/PeroLoopHook.java)（主循环钩子）。

## 2. 领域多 Agent

[MultiAgentOrchestrator.java](../src/main/java/com/wikiagent/application/multiagent/MultiAgentOrchestrator.java) 在请求带 `domain/subDomain` 时启用：

- [DomainSupervisor.java](../src/main/java/com/wikiagent/application/multiagent/DomainSupervisor.java)：按 [TenantKey.java](../src/main/java/com/wikiagent/application/multiagent/TenantKey.java)（业务域 + 子域 + 身份）垂直路由，跨租户记忆与知识不串；
- 覆盖 **9 个业务域**（DomainTag）：industry_solution / merchant_center / pms / service_provider / trunk_line / customs / settlement / first_mile / trajectory，每域 6 个子域；
- **5 种身份**（BusinessIdentity）：admin / business / product / tech / testing，默认 business；
- [StateReducerStrategy.java](../src/main/java/com/wikiagent/application/multiagent/StateReducerStrategy.java)：多 Agent 产出冲突时的归约策略。

[IntentAgents.java](../src/main/java/com/wikiagent/application/multiagent/IntentAgents.java) 注册 5 个意图 Agent：

| 意图 | 实现状态 |
|---|---|
| 知识问答 knowledge_qa | 真实实现（KnowledgeQaAgent → PeroAgent） |
| ai_coding / customer_intake / business_rule_config / order_query | Mock 实现（占位，待接真实能力） |

## 3. 工具与审批

[ToolRegistryImpl.java](../src/main/java/com/wikiagent/infrastructure/tool/ToolRegistryImpl.java) 注册 5 个工具：

1. [SearchKnowledgeBaseTool](../src/main/java/com/wikiagent/infrastructure/tool/SearchKnowledgeBaseTool.java) — 知识库检索；
2. [SearchHistoryTool](../src/main/java/com/wikiagent/infrastructure/tool/SearchHistoryTool.java) — 历史事件检索；
3. [ReadHandoverTool](../src/main/java/com/wikiagent/infrastructure/tool/ReadHandoverTool.java) — 读取交接清单；
4. [ListAbandonedPathTool](../src/main/java/com/wikiagent/infrastructure/tool/ListAbandonedPathTool.java) — 列出已放弃路径（避免重复踩坑）；
5. [UpdateUserProfileTool](../src/main/java/com/wikiagent/infrastructure/tool/UpdateUserProfileTool.java) — 写用户画像，属**写操作**，须经规则之双（Rule of Two）审批后才执行。

工具权限落库（V15 `tool_permission`，[ToolPermissionEntity.java](../src/main/java/com/wikiagent/infrastructure/tool/ToolPermissionEntity.java)）：按身份/工具维度控制可见性与可执行性。

## 4. 分层记忆

四个记忆端口定义在 `domain/memory`，适配器位于 [infrastructure/memory/](../src/main/java/com/wikiagent/infrastructure/memory)：

| 层 | 默认实现 | 规则 |
|---|---|---|
| 短期记忆 | Redis（redis 关闭时 inmemory 兜底） | 每会话 ≤ 20 轮、TTL 24h；key 以 sessionId+userId 前缀隔离 |
| 用户画像 | MySQL（userId 主键） | 稳定偏好/背景，由工具经审批写入 |
| 历史事件 | Milvus（父子索引） | `is_active` 软删除，检索可过滤 |
| 交接清单 | MySQL（`wikiagent.memory.handover-adapter=mysql` 默认） | 主表/节点/数据引用/放弃路径四表；另有 file 适配器与迁移开关 |

## 5. 成本路由

[ChatService.java](../src/main/java/com/wikiagent/service/chat/ChatService.java) 先以 qwen-flash 做意图识别再选模型，定价（元/千 token，输入:输出，application.yml 配置）：

| 模型 | 输入 | 输出 |
|---|---|---|
| qwen-flash | 0.0005 | 0.001 |
| qwen-plus | 0.0008 | 0.002 |
| qwen-max | 0.02 | 0.06 |

涉及人名/制度/业务流程的企业内容**强制走知识库检索**，避免意图误判造成漏召回。LLM 调用统一策略：重试 2 次、退避 300ms；熔断连续失败 3 次打开 60s。
