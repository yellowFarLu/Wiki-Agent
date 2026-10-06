# 全链路架构

> 对应代码基线：Spring Boot 3.5.16 · Java 21 · Spring AI 1.1.2 · Spring AI Alibaba 1.1.2.0
> 相关文档：[技术方案.md](技术方案.md) · [PRODUCTION_AGENT_PLAN.md](PRODUCTION_AGENT_PLAN.md)

## 1. 请求主链路（问答）

```text
用户（Next.js 管理台，单页 8 Tab）
  |
  v
输入安全网关（全链路第一站）
  |  关键词黑名单 + LLM 评判（qwen-flash，阈值 0.7）+ Spotlighting 对抗标记
  |  + 双锁规则状态机 + 信任边界头校验（X-User-Id / X-Internal-Secret）
  v
LLM 路由层（qwen-flash 意图识别，成本最优）
  +-- 简单任务 --------------------------> qwen-plus
  +-- 复杂任务 --------------------------> qwen-max
  +-- 企业知识内容（人名/制度/业务流程）----> 强制走知识库检索
  |                                      （避免企业问题被误判导致漏召回）
  v
Agent 执行层
  |  PERO 主循环（wikiagent.pero.enabled=true，默认开启）
  |    Plan     qwen-max 多节点规划，生成可执行任务清单
  |    Execute  每个节点由 ReActExecutor 执行（最多 8 轮思考-行动循环）
  |             +-- DomainSupervisor 按意图/身份路由 5 个领域 Agent
  |             +-- PeroToolExecutor 真实分派 5 个工具（写操作需审批）
  |    Reflect  LlmReflector 校验节点产出，未达标回 Execute 重试
  |    Optimize 经验沉淀至历史事件库；交接清单实时写 MySQL
  |
  +--> 混合检索 RetrievalService
  |       查询改写（多查询扩展）-> Embedding
  |       -> Milvus 双路召回：Dense 向量 + BM25 稀疏，服务端 RRF 融合（k=60）
  |       -> GraphRAG 实体匹配 + 1-hop 邻居扩展（可选开关）
  |       -> gte-rerank 精排（灰度门控，失败保持原序）
  |       -> Top-K 父块上下文组装（字符预算 12000）
  |       -> Milvus 故障：60s 熔断 + 中文 bigram 关键词本地降级
  |
  +--> 分层记忆
          +-- 短期记忆 Redis（<=20 轮 / TTL 24h，sessionId+userId 前缀隔离）
          +-- 用户画像 MySQL（userId 主键）
          +-- 历史事件 Milvus（父子索引，is_active 软删除）
          +-- 交接清单 MySQL（主表/节点/数据引用/放弃路径 四表）
  v
输出网关（非流式路径整段校验；流式路径逐 chunk 包裹校验）
  内容审核 + 系统提示词泄露检测 + Presidio PII 脱敏（sidecar 可选）
  v
用户（SSE 打字机流式输出，答案附引用角标 + 来源声明 + 会话 traceId）
```

### 四层链路切换

问答在 [ChatService.java](../src/main/java/com/wikiagent/service/chat/ChatService.java) 中按开关选择链路：

1. `wikiagent.pero.enabled=true` + `wikiagent.multi-agent.enabled=true` 且请求含 `domain/subDomain` → MultiAgentOrchestrator（9 域 × 6 子域 × 5 身份垂直隔离）；
2. `wikiagent.pero.enabled=false` → v1-v2 AgentOrchestrator（Route→Plan→Execute→Reflexion 单 Agent 主循环）；
3. `wikiagent.agent.enabled=true` → Agentic RAG（[AgentRagService.java](../src/main/java/com/wikiagent/service/agent/AgentRagService.java)）；
4. 其他 → 简单 RAG 固定管道（改写 → 检索 → 生成）。

SSE 事件协议：`stage` / `sources` / `delta` / `done` / `error` / `blocked`。

## 2. 异步任务链路（入库 / Agent 长任务）

```text
提交 -> MySQL task_instance 状态机（真相源，biz_key 唯一约束幂等）
     -> 投递：RocketMQ（Tag 区分 INGEST/AGENT，独立消费组）
              TASK_MQ=local 时降级 JVM 本地调度（零中间件启动）
     -> Worker CAS 抢租约（PENDING/DISPATCH + 无主或租约过期）
     -> 心跳续租（owner + RUNNING 双条件，防误杀健康长任务）
     -> 步骤级 Checkpoint：mergeRegistered 先合并动态注册步骤，已 DONE 不重跑
     -> 边界检查：租约丢失抛 LeaseLostSignalException 让出击；deadline 到点置 TIMEOUT
     -> 结果：COMPLETED / FAILED（attempt<=3，退避 10s·30s·2min）/ CANCELLED
     -> 人工：WAITING_HUMAN（INPUT 补充后续跑 / DIRECT_RESOLVE 直接终结）
     -> 控制：suspend/resume/cancel 双写 MySQL+Redis，version CAS 防并发
     -> 自愈：RecoveryJob 15s 扫描过期租约（TTL 30s）；DispatchCompensationJob 5s 捞滞留 PENDING
     -> 看门狗：TaskWatchdog 处理 deadline 超时
```

## 3. 技术选型

| 层 | 技术 | 选型理由 |
|---|---|---|
| 语言/框架 | Java 21 + Spring Boot 3.5.16 | Record、模式匹配、虚拟线程友好；企业生态成熟 |
| AI 框架 | Spring AI 1.1.2 + spring-ai-alibaba 1.1.2.0 | 官方工具/ToolCallback/ToolContext 抽象；DashScope 原生 starter；另引入 agent-framework |
| 大模型 | 通义 qwen-flash / plus / max + qwen-vl-plus + paraformer-v2 | 按任务复杂度分级路由，成本最优；多模态 OCR/ASR 覆盖 |
| Embedding | text-embedding-v4（1024 维） | 中英文企业语料效果均衡 |
| 向量库 | Milvus 2.5.x（SDK 2.5.17） | 原生 Dense+BM25 混合检索、服务端 RRF、标量过滤 |
| 关系库 | MySQL 8（生产）/ H2 文件库 MODE=MySQL（开发默认） | Flyway V1–V22 统一迁移；零依赖本地可跑 |
| 缓存/协调 | Redis + Redisson 3.52.0 | 短期记忆、分布式锁、租约标志、Session 兜底 |
| 消息队列 | RocketMQ 5.3.1（经典 Remoting 客户端） | Tag 分类、延迟消息天然适配退避重试、死信队列 |
| 文档解析 | PDFBox 3.0.8 + Apache POI 5.5.1 + Tika 3.3.2 + DashScope DocAI | 本地解析兜底 + 云端 OCR/ASR/版面/表格增强 |
| 可观测 | Actuator + Micrometer Prometheus + 自研 Trace | 指标 + 最小链路追踪 + Resilience4j 限流熔断 |
| 前端 | Next.js 14.2 App Router + TypeScript + Ant Design 6 | SSE 流式、SSR、工程化 |
| 图谱存储 | MySQL（graph_entity / graph_relation） | LightRAG 思路落地，零额外中间件，增量友好 |

## 4. DDD 分层工程结构

```text
src/main/java/com/wikiagent/
+-- domain/                 # 领域层：纯业务，无框架依赖
|   +-- task/               #   状态机/步骤/事件/人工任务/异常信号 + ports 端口
|   +-- memory/             #   记忆四个端口（短期/画像/事件/交接）
|   +-- eval/               #   黄金样本 + 指标计算器
|   +-- routing/ retrieve/  #   意图/路由决策；检索模型与安全上下文
|   +-- identity/ rule/ parse/ llm/ agent/ graph/ lineage/ ...
+-- application/            # 应用层：用例编排
|   +-- agent/pero/         #   PERO 主循环：Planner/ReActExecutor/Reflector/...
|   +-- multiagent/         #   DomainSupervisor 领域路由与 5 个意图 Agent
|   +-- task/ (+handler)    #   提交/Worker/控制/恢复/看门狗/补偿 + INGEST/AGENT handler
|   +-- graph/ gray/ gateway/ knowledge/ rule/ lineage/ identity/
|   +-- eval/ (metrics/suite/support) 与 observability/ (trace)
+-- infrastructure/         # 基础设施层：可插拔适配器
|   +-- memory/             #   redis/mysql/milvus/inmemory/file 五实现
|   +-- task/               #   jpa 四表 / mq(RocketMQ+本地) / redis / jvm 降级
|   +-- llm/                #   多模型工厂/Provider 链/熔断/rerank/流式
|   +-- security/ gateway/ tool/ parse/ lock/ observability/ persistence/
+-- interfaces/             # 接口层：REST（task/graph/eval/rule/lineage/metrics/...）
+-- controller/             # 基础 REST：ChatController / DocumentController / HealthController
+-- service/                # 跨层核心服务：ingest / retrieve / chat / agent / store
+-- entity/ repo/ dto/      # JPA 实体、仓库、数据传输对象
+-- config/                 # WikiAgentProperties 等配置绑定
frontend/                   # Next.js 14（App Router + TS + AntD + Canvas 图谱）
```

依赖方向：`interfaces/controller → application → domain ← infrastructure`（依赖倒置，domain 只定义端口）。
