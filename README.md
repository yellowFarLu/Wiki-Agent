# 🧭 Wiki Agent

**不止是知识库检索 —— 一个具备感知、记忆、规划、工具调用、治理与自省能力的企业级知识智能体**

*Java 21 · Spring Boot 3.5 · Spring AI 1.1.2 · Spring AI Alibaba 1.1.2.0 · Milvus 2.x · MySQL · Redis/Redisson · RocketMQ · Next.js 14 · DDD 分层架构*

---

## 📌 一句话定位

Wiki Agent 把"企业知识库问答"从 RAG 三件套升级为一条**完整的智能体工程闭环**：文档以六步可断点流水线入库（解析→清洗→父子切分→向量化→索引→校验，并可选 LLM 实体关系抽取构建知识图谱）；问答经**输入安全网关 → qwen-flash 意图路由 → PERO Agent 主循环（Plan-Execute-Reflect-Optimize + ReAct 工具调用）→ 混合检索（Dense + BM25 服务端 RRF + rerank + GraphRAG 邻居扩展）→ 输出安全网关**；重活下沉到 **MySQL 状态机 + RocketMQ 投递 + Redis 协调** 的通用任务框架；辅以分层记忆、灰度发布、全链路追踪、离线评测与**每个中间件都可本地降级**的韧性设计。

> 🔍 **答不上来时**：联网搜索 → 模型自身知识逐级兜底，并**诚实标注答案来源**，绝不把通用知识伪装成知识库内容
> 🧠 **分层记忆**：短期会话（Redis ≤20 轮）、用户画像（MySQL）、历史事件（Milvus 父子索引）、交接清单（MySQL 四表）
> 🗺️ **复杂任务**：先规划任务清单、再 ReAct 逐节点执行、反思不达标则重试、经验沉淀反哺
> ⚙️ **任务框架**：幂等提交、租约心跳、步骤级 Checkpoint、指数退避、死信、人工接管、崩溃自愈
> 📊 **自省能力**：黄金样本离线评测 7 项确定性指标、反馈飞轮、链路追踪、知识过期治理
> 🛡️ **安全韧性**：提示词注入 / PII 泄露 / 中间件宕机 —— 全链路护栏 + 降级预案，零中间件也能健康启动

---

## 🏗️ 全链路架构

### 请求主链路

```text
👤 用户（Next.js 管理台 / 内置静态 UI）
 │
 ▼
🛡️ 输入安全网关（全链路第一站）
 │   关键词黑名单 + LLM 评判（qwen-flash，阈值 0.7）+ Spotlighting 对抗标记
 │   + 双锁规则状态机 + 信任边界头校验（X-User-Id / WIKIAGENT_INTERNAL_SECRET）
 │
 ▼
🚦 LLM 路由层（qwen-flash 意图识别，成本最优）
 ├─ 简单任务 ───────────────────────► qwen-plus
 ├─ 复杂任务 ───────────────────────► qwen-max
 └─ 企业知识内容（人名/制度/业务流程）──► 强制 mode=search 走知识库
 │                                   （避免企业问题被误判为 web 导致漏召回）
 ▼
🤖 Agent 执行层
 │   PERO 主循环（wikiagent.pero.enabled=true，默认）
 │   📋 Plan    qwen-max 多节点规划，生成可执行任务清单
 │   ⚡ Execute 每个节点由 ReActExecutor 执行（最多 8 轮思考-行动循环）
 │   │          ├─ DomainSupervisor 按意图/身份路由 5 个领域 Agent
 │   │          └─ 🧰 PeroToolExecutor 真实分派 5 个 @Tool（写操作需审批）
 │   🔍 Reflect LlmReflector 校验节点产出，未达标回 Execute 重试
 │   📈 Optimize 经验沉淀至历史事件库；交接清单实时写 MySQL
 │
 ├──► 🔎 混合检索 RetrievalService
 │        查询改写（多查询扩展）→ Embedding
 │        → Milvus 双路召回：Dense 向量 + BM25 稀疏，服务端 RRF 融合（k=60）
 │        → GraphRAG 实体匹配 + 1-hop 邻居扩展（可选开关）
 │        → gte-rerank 精排（灰度门控，失败保持原序）
 │        → Top-K 父块上下文组装（字符预算 12000）
 │        → Milvus 故障：60s 熔断 + 中文 bigram 关键词本地降级
 │
 ├──► 🧠 分层记忆
 │        ├─ 短期记忆 Redis（≤20 轮 / TTL 24h，sessionId+userId 前缀隔离）
 │        ├─ 用户画像 MySQL（userId 主键）
 │        ├─ 历史事件 Milvus（父子索引，is_active 软删除）
 │        └─ 交接清单 MySQL（节点/数据引用/放弃路径三表+主表）
 │
 ▼
🛡️ 输出网关（非流式路径全量校验；流式路径逐 chunk 包裹校验）
     内容审核 + 系统提示词泄露检测 + Presidio PII 脱敏（sidecar 可选）
 │
 ▼
👤 用户（SSE 打字机流式输出，答案附引用角标 + 来源声明 + 会话 traceId）
```

### 异步任务链路（入库 / Agent 长任务）

```text
提交 ─► MySQL task_instance 状态机（真相源，biz_key 唯一约束幂等）
      ─► 投递：RocketMQ（Tag 区分 INGEST/AGENT，独立消费组）
      │        TASK_MQ=local 时降级 JVM 本地调度（零中间件启动）
      ─► Worker CAS 抢租约（PENDING/DISPATCH + 无主或租约过期）
      ─► 心跳续租（owner + RUNNING 双条件，防误杀健康长任务）
      ─► 步骤级 Checkpoint：mergeRegistered 先合并动态注册步骤，已 DONE 不重跑
      ─► 边界检查：租约丢失抛 LeaseLostSignalException 让出击；deadline 到点置 TIMEOUT
      ─► 结果：COMPLETED / FAILED（attempt≤3，退避 10s·30s·2min）/ CANCELLED
      ─► 人工：WAITING_HUMAN（INPUT 补充后续跑 / DIRECT_RESOLVE 直接终结）
      ─► 控制：suspend/resume/cancel 双写 MySQL+Redis，version CAS 防并发
      ─► 自愈：RecoveryJob 15s 扫描过期租约（TTL 30s）；DispatchCompensationJob 5s 捞滞留 PENDING
      ─► 看门狗：TaskWatchdog 处理 deadline 超时
```

---

## 🧱 技术选型

| 层 | 技术 | 选型理由 |
|---|---|---|
| 语言/框架 | Java 21 + Spring Boot 3.5 | Record、模式匹配、虚拟线程友好；企业生态成熟 |
| AI 框架 | Spring AI 1.1.2 + spring-ai-alibaba 1.1.2.0 | 官方 `@Tool`/ToolCallback/ToolContext 抽象；DashScope 原生 starter |
| 大模型 | 通义 qwen-flash / plus / max + qwen-vl-max + paraformer-v2 | 按任务复杂度分级路由，成本最优；多模态 OCR/ASR 覆盖 |
| Embedding | text-embedding-v4（1024 维） | 中英文企业语料效果均衡 |
| 向量库 | Milvus 2.x | 原生 Dense+BM25 混合检索、服务端 RRF、标量过滤 |
| 关系库 | MySQL 8（生产）/ H2 文件库 MODE=MySQL（开发） | Flyway V1–V17 统一迁移；零依赖本地可跑 |
| 缓存/协调 | Redis + Redisson | 短期记忆、分布式锁、租约标志、Session 兜底 |
| 消息队列 | RocketMQ | Tag 分类、延迟消息天然适配退避重试、死信队列 |
| 文档解析 | PDFBox + Apache POI + Tika + DashScope DocAI | 本地解析兜底 + 云端 OCR/ASR/版面/表格增强 |
| 可观测 | Actuator + Micrometer Prometheus + 自研 Trace | 指标 + 最小链路追踪 + Resilience4j 限流熔断 |
| 前端 | Next.js 14 App Router + TypeScript + Ant Design 6 | SSE 流式、SSR、工程化 |
| 图谱存储 | MySQL（graph_entity / graph_relation） | LightRAG 思路落地，零额外中间件，增量友好 |

---

## 📦 核心模块详解

> 以下每个模块均给出：**职责 → 关键机制 → 代码落点**，方便按需展开。

### 1️⃣ 文档解析与入库流水线（六步状态机）

入库不是"文档→向量"的黑盒，而是文档状态机驱动的**六步可断点续跑流水线**，每步落状态、产出血缘 Artifact、异常可分类重试：

```text
📤 上传 ─► 📖 PARSING ─► 🧹 CLEANING ─► ✂️ CHUNKING ─► 🔢 EMBEDDING ─► 📇 INDEXING ─► ✅ READY
```

| 步骤 | 关键实现 | 细节 |
|---|---|---|
| ① 解析 | `DocumentParser` / `RichDocumentParser` | 按扩展名分流：txt/md 直读；PDF 用 PDFBox **逐页**提取（保留页码/坐标）；docx/xlsx 用 POI；旧版 .doc/.xls 走 Tika 兜底。产出 `ParsedDocument` |
| ② AI 富解析 | `DashScopeDocumentAiProvider`（SPI：`wikiagent.parse.provider`） | 扫描件/图片走 qwen-vl-max OCR；录音走 paraformer-v2 ASR；版面分析 + 跨页表格拼接。**文本层与 OCR 双跑**，相似度 <0.85 标 CONFLICT 不静默二选一；加密文档转人工解密 |
| ③ 清洗 | `TextCleaner` | 去零宽字符、去页码/分隔线、折叠空白、剔除 ≥3 次重复短行（页眉页脚特征） |
| ④ 父子切分 | `ChunkSplitter` | **两级切分**：父块 1000 字符/150 重叠（段落聚合，超长段按句边界再切）；子块 350 字符/60 重叠滑窗，窗口在句子边界收口。子块携带 `pageNo`+`versionNo` |
| ⑤ 向量化 | DashScope EmbeddingModel | text-embedding-v4，批量 10 条/次；**Embedding 精确缓存**（Redis，TTL 24h）命中不重复计费 |
| ⑥ 索引校验 | `MilvusStoreService` + 父块 MySQL | 子块向量写 Milvus `wikiagent_chunks`（Dense + BM25 双向量字段）；父块纯 MySQL，检索命中后回溯组装上下文；**幂等**：已 READY 直接跳过；`is_active` 软删除支撑版本重解析 |

- **失败语义**：终态失败任务回滚文档状态为 FAILED；READY/AI_SKIPPED 终态不允许被回滚覆盖
- **两种驱动**：任务框架（`IngestTaskHandler` 逐步执行，可暂停/接管/续跑，推荐）；`wikiagent.task.enabled=false` 时走 `@Async` 旧入口
- **页码粗映射**：子块前 20 字符在页文本中定位来源页，支持引用精确到页
- 落点：[IngestionService.java](src/main/java/com/wikiagent/service/ingest/IngestionService.java)、[ChunkSplitter.java](src/main/java/com/wikiagent/service/ingest/ChunkSplitter.java)

### 2️⃣ 混合检索引擎（RetrievalService）

```text
用户问题
  │
  ├─ 查询改写 QueryRewriteService：多查询扩展（每轮最多 3 查询 × 最多 2 轮）
  │
  ▼
Milvus hybridSearch（每个查询 sub-topk=20）
  ├─ Dense 路：query embedding 向量近邻
  └─ Sparse 路：BM25（k1=1.2, b=0.75）
        └─ 服务端 RRF 融合（rrf-k=60），无需应用侧归一化分数
  │
  ├─ GraphRAG 扩展（可选）：实体模糊匹配 → 1-hop 邻居 → 关联 chunk 以 0.5 置信分注入
  │
  ├─ 子块→父块归并：Accumulator 按 parentId 取最高分代表块，保序去重
  │
  ├─ rerank 精排：gte-rerank 对候选重排，截断 final-topk=10
  │     · GrayReleaseService 灰度门控（首个受控特性：rerank）
  │     · 无 API Key：provider.available()=false 自动跳过
  │     · 调用异常：catch 后保持原序，不硬失败
  │
  └─ 上下文组装：父块文本拼装，字符预算 parent-char-budget=12000
```

- **熔断降级**：Milvus 探测/调用失败 → 进入 60 秒冷却（`volatile milvusDisabledUntil`），冷却期直接走 MySQL 本地检索，避免每次 10s 超时拖垮问答；本地路抽取最多 12 个中文 bigram/关键词
- **权限过滤**：`RetrievalIdentityFilter` 基于 `RetrievalSecurityContext` 身份 + 8 大业务域做元数据过滤
- **指标埋点**：检索事件以 **RETRIEVED / CITED** 两种粒度在最终源头写 `metric_event`，杜绝"召回即引用"的虚报
- 落点：[RetrievalService.java](src/main/java/com/wikiagent/service/retrieve/RetrievalService.java)、[MilvusStoreService.java](src/main/java/com/wikiagent/service/store/MilvusStoreService.java)、[QueryRewriteService.java](src/main/java/com/wikiagent/service/retrieve/QueryRewriteService.java)

### 3️⃣ 🕸️ GraphRAG 知识图谱（LightRAG 风格）

在向量检索之上叠加实体关系图谱，解决"与某实体相关的所有知识"的跨 chunk 关联召回。

**方案选型（方案对比）**

| 方案 | 结论 | 原因 |
|---|---|---|
| 微软 GraphRAG | ❌ | 社区检测 + 全局摘要需全量重跑，增量入库成本不可接受 |
| HippoRAG | ❌ | 神经关联记忆创新性强，但工程落地与运维复杂度高 |
| **LightRAG 思路（选用）** | ✅ | 实体级抽取 + 局部邻居扩展，增量友好；图存 MySQL 零新增中间件 |

**入库侧（GraphExtractionService）**

- 每个 chunk 截断至 1500 字符送 `simpleChatModel`（qwen-plus），Prompt 限定：
  - 实体 8 类：人物 / 组织 / 产品 / 地点 / 概念 / 事件 / 规则 / 其他
  - 关系 9 类：属于 / 包含 / 依赖 / 影响 / 合作 / 对抗 / 引用 / 继承 / 其他
  - 强约束"**只抽取文本明确提及的实体和关系，禁止编造**"
- **实体消歧**：`name+type` 唯一冲突时 upsert 合并 description；跨文档共享同一图谱
- **关系合并**：`source+target+type` 冲突时累加 weight（共现频次）
- LLM 返回 JSON（兼容 markdown 包裹解析）；**抽取失败 catch + log.warn，不阻断入库主流程**
- 文档删除时对图数据 `is_active=false` 软下线（Milvus 不支持 chunk 级删除的同一设计原则）

**检索侧（GraphRagService）**

实体名模糊匹配 Top5（检索探针：整句优先，多词自然语言按 bigram/拉丁词拆解补充，避免「WMS 何时升级 3.0」这类改写查询匹配不到名为「仓储管理系统 WMS」的实体）→ 查出边/入边 1-hop 邻居（上限 10）→ 收集邻居实体关联的 chunkId → 映射父块后以固定 **0.5 分**累积进检索结果（中等置信：不压过向量高分，但能进入 rerank 被重新评估）；单轮注入 chunk 上限 20，防止 bigram 命中泛滥。图扩展在两条问答链路都挂接：普通 `retrieve(List)` 与 AgentRag 的每轮 CRAG 循环；图服务任何异常只 warn 不阻断向量主流程。

**可视化与 API**

- 前端 `/graph`：零依赖 Canvas **自实现力导向布局**（圆形初始化 + 50 轮斥力/弹簧引力/向心力迭代），实体类型配色，支持搜索、点击实体跳转来源文档
- `GET /api/graph/stats|/search?q=|/entity/{id}|/entity/{id}/neighbors|/doc/{docId}`
- 开关：`WIKIAGENT_GRAPH_ENABLED=true`（默认开启，`@ConditionalOnProperty` + 调用侧 `ObjectProvider` 空回退；无 DashScope Key 时逐 chunk 告警跳过，主链路零影响）

### 4️⃣ 分层记忆（横向扩展友好）

| 层级 | 存储 | 机制 | 代码落点 |
|---|---|---|---|
| 短期记忆 | Redis（降级：JVM `InMemoryShortTermMemoryAdapter`） | 会话级 ≤20 轮、TTL 24h，`sessionId+userId` 前缀隔离 | `infrastructure/memory/redis` |
| 用户画像 | MySQL `user_profile`（userId 主键） | 身份/偏好/业务事实；`update_user_profile` 工具写操作需人工审批 | `JpaUserProfileRepository` |
| 历史事件库 | Milvus 父子索引（降级：本地） | 事件向量化 + `is_active` 软删除；Optimizer 经验沉淀；`MilvusEventSink` 用 Runnable 回调写入 | `infrastructure/memory/milvus` |
| 交接清单 | MySQL 四表（降级：File） | `handover_checklist`（主）/ `handover_node`（已执行节点）/ `handover_data_ref`（数据引用索引）/ `handover_abandoned_path`（放弃路径）；内存单例**线程安全写穿**仓库，解决读写分离不一致 | `infrastructure/memory/mysql` |

> 设计原则：Agent 工程代码（domain 端口）与数据状态（适配器实现）分离，MySQL/Redis/Milvus/InMemory/File 五套实现按开关插拔，支持无状态副本水平扩缩容。

### 5️⃣ LLM 成本路由

`DashScopeMultiModelFactory` 产出三个 ChatModel Bean 与一个 EmbeddingModel：

```text
                ┌─ qwen-flash  意图识别 / 安全 LLM 评判 / 轻量 Reflect（最便宜，0.0005/0.001 元每千 tokens）
用户问题 ─路由─► ├─ qwen-plus   简单问答 / 图实体抽取（0.0008/0.002）
                └─ qwen-max    复杂规划 Plan / 复杂任务生成（0.02/0.06）
```

- 单价配置在 `wikiagent.llm.pricing`，`ModelCallRecorder` 每次调用记录 tokens/成本（DashScope 未回 usage 时按 4 字符≈1 token 估算并标注 `[estimated]`）
- 企业知识内容（人名/公司制度/业务流程）**显式规则强制 mode=search**——曾因误判 mode=web 导致知识库漏召回，是踩坑后固化的硬规则
- LLM 调用统一走 `ChatModelProviderChain`：2 次重试 + 300ms 线性退避 + 连续 3 次失败熔断 60s，NoOpChatModel 为无 Key 兜底

### 6️⃣ PERO Agent 主循环与多 Agent

**PERO = Plan + Execute + Reflect + Optimize**（融合 Plan-And-Execute 与 ReAct），落点 `application/agent/pero/`：

| 角色 | 类 | 职责 |
|---|---|---|
| 总控 | `PeroAgent` | 驱动主循环，串接四阶段与 Hook |
| 规划 | `PeroPlanner` | ChatModel 多节点规划，产出节点清单 |
| 感知 | `SimplePerception` | 组装上下文（会话记忆 + 画像 + 交接信息） |
| 执行 | `ReActExecutor` | 每节点最多 8 轮 Thought→Action→Observation；步骤类型限定工具白名单（search_kb 只能用检索工具，generate 禁用工具） |
| 工具分派 | `PeroToolExecutor`（生产）/ `StubToolExecutor`（仅 PERO 关闭时 v1-v2） | 真实工具执行 + 参数校验 + 审批闸门 |
| 反思 | `LlmReflector` | qwen-max 校验节点产出是否达标，未达标回 Execute |
| 优化 | `SimpleOptimizer` / `NoOpOptimizer` | 经验写历史事件库（开关 `wikiagent.pero.optimize.enabled`） |
| 预算 | `BudgetTracker` | token/成本/轮次预算控制 |
| 记忆衔接 | `MemoryHandover` | 交接清单实时持久化（线程安全写穿） |
| 情景记忆 | `EpisodicMemory` | 30 天 TTL 的节点间记忆 |

**5 个真实工具（Spring AI 官方工具抽象）**：`search_knowledge_base`、`search_history`、`update_user_profile`（写，需 `profile:write` scope + 人工审批）、`read_handover`、`list_abandoned_paths`。工具权限三级优先级：YAML 配置 > DB `tool_permission` 表（V15）> 内置默认。

**多 Agent（application/multiagent）**：自研 `DomainSupervisor`（spring-ai-alibaba 的 SupervisorAgent.builder().subAgents() 实际不存在，故自研），按意图在 5 个领域 Agent（knowledge_qa / ai_coding / customer_intake / business_rule_config / order_query）间路由，路由字段为用户身份；并发靠 Redisson 锁 + StateReducer（messages/episodic_memory 列表合并，其余覆盖）。

**四层降级链**（`ObjectProvider` 优雅解决 PERO Bean 与 v1-v2 Bean 互斥）：
`多Agent(PERO v6) → v1-v2 Orchestrator(PlanAndExecutePlanner+ReflexionService) → AgentRagService 纯RAG → Legacy 单轮兜底`

### 7️⃣ 通用任务框架（子项目 A · 重点）

**为什么是异步任务，而不是 HTTP 同步返回？** 同步并非不可用——普通问答 `/api/chat` 就是**同步 SSE 流式返回**（用户在线等答案）。但"重活"不能走同步：

| 场景 | 同步方案的问题 | 异步任务框架的收益 |
|---|---|---|
| 文档入库 | 大文件解析/OCR/ASR、Embedding 批量调用、Milvus 写入、GraphRAG LLM 抽取动辄数十秒到数分钟，必然撞上网关/浏览器超时；期间独占一个 Tomcat 请求线程 | 请求只负责提交并立即返回 taskId，后台 Worker 弹性消费，线程不被长任务占满 |
| Agent 长任务 | 多轮 LLM + 工具调用可能运行数分钟 | 步骤级进度经 SSE 推送，前端实时可见 |
| 客户端断连 | 同步请求中断，进行中的工作直接丢失且结果无处送达 | 任务状态在 MySQL，断连不影响执行，随时可回查 |
| 服务重启/发布 | 在途请求全部丢失，不可恢复 | 租约过期后 RecoveryJob 自动续跑，Checkpoint 保证不重头再来 |
| 瞬时失败 | 同步只能把错误抛给用户 | 指数退避自动重试 3 次，耗尽进 FAILED 可人工 replay |
| 人工介入 | 同步模型无法表达"暂停等人补充" | WAITING_HUMAN / suspend / resume / cancel 一等公民 |
| 流量高峰 | 请求线程被打满导致整个服务拒绝服务 | MQ 削峰填谷 + 按租户并发上限 + INGEST/AGENT 独立消费组水平扩缩 |

> 边界划分：**用户在等结果的短交互走同步流式**（chat）；**耗时长、需可靠执行、可能需要人工介入或重试的工作走任务框架**（入库、Agent 长任务）。任务以 MySQL 为真相源、MQ 只投递、Redis 只协调，三者任一抖动都有明确降级语义。

**状态机**（`TaskStateMachine`，终态仅 3 个，杜绝终态复活）：

```text
PENDING ─► DISPATCH ─► RUNNING ─┬─► COMPLETED ✅
                                ├─► WAITING_HUMAN ─► (INPUT 补充后) RUNNING / (DIRECT_RESOLVE) COMPLETED
                                ├─► SUSPENDED ─resume CAS─► PENDING（清租约）
                                ├─► CANCELING ─► CANCELLED
                                └─► FAILED（attempt<3 → 回到 PENDING 退避重试）
```

| 机制 | 实现要点 |
|---|---|
| 真相源 | MySQL 四表 `task_instance / task_step / task_event / human_task`；RocketMQ 只投递、Redis 只协调，**任何缓存状态不权威** |
| 幂等提交 | `biz_key` 唯一约束；并发撞约束时查询已存在任务返回 `duplicate:true`，不报 500 |
| 租约 | Worker `casLease` 仅在 PENDING/DISPATCH 且无主/过期时成功；TTL 30s；心跳每 10s 以 `owner + RUNNING` 双条件 `renewLease`，返回 0 立即让出，**防崩溃恢复误杀健康长任务** |
| Checkpoint | 步骤完成才 markDone；恢复时 `mergeRegistered` 先合并动态注册的 NODE 步骤再取当前步，已 DONE 绝不重跑（曾因静态 GENERATE 步骤先于动态步骤合并导致跳过 NODE 的 bug 即此机制修复） |
| 退避重试 | `attempt` 控制，10s/30s/2min（RocketMQ 延迟等级 3/4/6），最多 3 次；`ErrorClassifier` 区分可重试/致命错误 |
| 超时 | 步骤边界检查 deadline（默认 1800s）与步骤超时（300s，Agent 步骤 180s）；TIMEOUT 先走重试再 FAILED |
| 租约自愈 | `TaskRecoveryJob` 15s 扫过期租约续跑；`DispatchCompensationJob` 5s 捞 enqueue 滞留的 PENDING（outbox 思想） |
| 人工接管 | 复用同一 pending 单幂等防重复建单；Redisson 锁 + `lock_version` CAS 保证同一时刻一人接管（第二人 409） |
| 控制指令 | suspend/resume/cancel 双写 MySQL（`updateControlTrace`/CAS）+ Redis PAUSE 标志，带 `expectedVersion` 乐观锁，过期返回 409；步骤边界 `checkpointAndThrowIfSignaled` 响应 |
| HEARTBEAT 治理 | 心跳不落库（曾导致 task_event 膨胀），只更新 lease/heartbeat_at |
| 事件 SSE | `TaskSseBridgeController` 推送步骤进度；Redis 可用时走 pub/sub 跨副本，否则 JVM 总线 |
| 分类消费 | RocketMQ Tag 区分 INGEST/AGENT，独立消费组（并发 2/4）独立扩缩 |
| 降级矩阵 | RocketMQ 挂 → JVM `LocalTaskDispatcher`；Redis 挂 → MySQL 行字段（JvmLeasePort/ControlFlagPort）；**MySQL 挂 → 拒绝启动**（宁不启动不静默丢任务） |

### 8️⃣ 安全体系（全链路护栏）

| 威胁 | 防线 | 细节 |
|---|---|---|
| 提示词注入 | `InputGuardrailAdvisor` | 全链路最先生效：关键词黑名单 + LLM 评判（qwen-flash，阈值 0.7）双检测器 |
| 注入对抗 | `SpotlightingDecorator` | 用户内容用特殊分隔符包裹并指示模型"数据非指令"（spotlighting 技术） |
| 高危操作 | `RuleOfTwoStateMachine` | "双锁规则"：敏感动作需二次条件满足，状态机防绕过 |
| 信任边界 | `TrustedHeaderFilter` | `X-User-Id` 必须配共享密钥 `WIKIAGENT_INTERNAL_SECRET`（HMAC 思路），生产强制开启 |
| 输出违规 | `OutputGuardrailAdvisor` | 非流式**整段**校验后才发送；检测：内容审核（涉政/涉黄/暴恐/违法）+ 系统提示词泄露 |
| PII 泄露 | Presidio sidecar + `PiiMinimizingChatModelDecorator` | ChatModel 装饰器在外发前最小化 PII；sidecar 不可用时按配置降级；另有流式专用 `StreamingOutputGuardrailSender` |
| 审计 | `gateway_audit_log` / `content_violation_log` | 网关决策与违规内容全留痕 |
| 工具滥用 | 工具 scope + 审批 + Agent 白名单 | 见模块 6 |

### 9️⃣ 知识治理闭环

- **冲突检测**（`ConflictDetectionService`）：每日 02:30 扫描，chunk 间相似度 >0.8 候选冲突（6 种冲突类型），支持误报忽略；`ConflictResolutionService` 双栏对照式合并评审
- **指标聚合**（`MetricsAggregationJob`）：每日 02:00 聚合；`staleScore = 时间衰减(τ=180d) + 30 天使用频率衰减`，低于 0.3 标记过期嫌疑
- **反馈飞轮**：有用/无用按钮落 `kb_feedback`；**应用率（Utility Rate）只从 kb_feedback 表统计**，不依赖 metric_event 中的 chunk 反馈（避免会话级反馈伪造 chunk 指标；feedback 接口 conversationId/chunkId 均有默认值）
- **知识标签**：`KnowledgeTaggingService` 自动/人工打标，配合业务域隔离
- 看板 API：`/api/metrics/dashboard|/aggregation|/knowledge|/chunk/{id}`，空快照已修复 NPE

#### 近重复与冲突防御（入库去重 + 检索冲突守卫）

解决"两条相似知识只差一个字"造成的冗余与幻觉风险（如旧文"苹果5元"与新文"苹果3元"）。五层防御纵深：

| 层 | 组件 | 行为 |
|---|---|---|
| L0 精确去重 | `IngestionService.applyExactDedup` | chunk 正文规范化 SHA-256 `content_hash`；splitStep **同事务内**跨文档查重，命中按生效时间留晚者（相同/缺失留旧），软删 + 打点 `DEDUP_EXACT_SKIPPED` |
| L1 近重复守卫 | `NearDuplicateGuard` + `MinHashSignature` + `RedisLshIndex` + `KeyTokenDiffer` | 128 perm MinHash 签名 → Redis 32 bands × 4 rows 分桶粗筛 → 桶内精确 jaccard ≥ 0.9 认定近重复 → **关键 token 分流**（数字/日期/货币/否定词）：非关键差异自动合并（生效日晚者留，打点 `DEDUP_NEAR_MERGED`）；关键差异双方不动、写冲突单 `source=MINHASH_INGEST` 转人工 |
| 检索粗筛 | `ConflictCandidateDetector` | rerank 后对候选代表子块 O(k²) 余弦 ≥ 0.85 + 关键 token 复判，零 LLM 成本过滤无疑似对 |
| LLM 精判 | `ConflictLlmJudge`（qwen-flash） | 结构化 JSON 判断"是否同一实体同一属性给出不同事实值"，**只检测不裁决** |
| 确定性裁决 | `RetrievalConflictGuard` | 按 `kb_document.effective_date` 唯一权威信号移除输家 chunk（晚者胜；生效时间相同/缺失**不自动选边**，双保留 + 上下文标注 + 冲突单 `source=ONLINE_GUARD`），兜底走 `ConflictResolutionService` 人工仲裁（KEEP_A/B、MERGE、DELETE_A/B） |

**两个开关（默认均开启，可显式关闭）**：

| 配置 | 默认 | 说明 |
|---|---|---|
| `wikiagent.dedup.enabled` | true | L0+L1 入库侧总开关（`near-jaccard` 默认 0.9） |
| `wikiagent.conflict-guard.enabled` | true | 检索侧守卫总开关（含精判 Bean 装配；`coarse-cosine` 默认 0.85）。注意为**虚线式扁平名**：`wikiagent.conflict.*` 命名空间已被离线扫描占用 |
| `wikiagent.gray.features.conflict-guard.*` | 未配置=全量 | 守卫叠加请求级灰度门控（稳定分桶，见模块 13） |

**生效时间录入约定**：上传 `POST /api/documents` 传 `effectiveDate=yyyy-MM-dd`（缺省=服务器当天，非法格式 400）；任务框架路径经 payload 透传落库，已设置不覆盖（幂等）。存量迁移前文档 `effective_date` 可为 null → 裁决不选边、双保留。

**诚实声明**：
- MinHash 是**概率近似**：0.9 阈值 + LSH 分桶只保证高相似对大概率召回，边界样本可能漏检——关键 token 分流与在线守卫是其安全网；
- LLM 精判 **fail-open**：异常/超时/非法 JSON/候选集外 chunkId 一律按"无冲突"放行并告警，绝不阻断问答主链路（守卫整体同语义，内部任何异常原样透传）；
- Redis 不可用时 L1 降级为跳过检测并告警，**不阻断入库**；
- 所有"移除"都是 MySQL `is_active=false` 软删（Milvus 无 chunk 级删除 API），冲突单 + `metric_event`（`CONFLICT_GUARD_DETECTED/REMOVED/KEPT_BOTH`）全程可审计；同一 chunk 对（无序）已持任意状态冲突单时不重复报单。

### 🔟 数据血缘与版本

- Artifact 链：`RAW_FILE → PARSED_TEXT → CLEANED_TEXT → PARENT_CHUNK → CHILD_CHUNK → FIELD`，旁路 `OCR_PAGE / STITCHED_TABLE`；关系存 `provenance_edge`，节点存 `doc_artifact`
- 文档多版本：`doc_version` + 字段级 `field_version`，引用可解析到**具体版本的具体子块**（V11/V12 补充 chunk trace）
- API：`/api/documents/{docId}/lineage`、`/fields/{key}/lineage`、`/versions`、`/versions/{a}/diff/{b}`

### 1️⃣1️⃣ 确定性规则引擎与人工复核

- `RuleDslParser` 解析版本化规则 DSL；`RuleExecutionService` **零 LLM 确定性计算**；规则集 V13 表 `rule_set / rule_computation`
- 支持版本发布/归档/diff，ACTIVE 版本唯一
- 异常样本自动成案 `review_case`，记录处理人、旧值/新值、结论，全程可审计：`/api/review-cases`、`/api/rules/*`

### 1️⃣2️⃣ 可观测性

- **自研最小链路追踪**：`TraceSpan`/`TraceService`，每会话可查"路由→检索→生成"完整 Span；任务侧 `TaskTraceConveyor` 贯通；V16 为 agent_trace 补 trace_id。API：`/api/trace/session/{id}|/conversation/{id}|/user/{id}`
- **指标**：Micrometer + Prometheus（`/actuator/prometheus`），业务打点含灰度决策 `wikiagent.gray.decision`、LLM 成本、熔断状态
- **限流**：Resilience4j + 自研令牌桶 `SimpleTokenBucket` + `RateLimitFilter`（`wikiagent.ratelimit.enabled`）
- **熔断**：`CircuitBreakerRegistry` 统一管理 LLM/解析/Milvus 熔断器（独立状态机，失败阈值 3、开路 60s）
- 大屏数据源：`/api/observability/dashboard|/feedback|/gateway|/knowledge`

### 1️⃣3️⃣ 灰度发布（GrayReleaseService）

- **稳定分桶**：`SHA-256(feature:身份) % 100`，重启/扩缩容不漂移；无身份归入 anonymous 桶
- **优先级**：denylist（恒拒）> allowlist（恒放）> percent；未配置的特性不门控（全量放开）
- 每次决策 Micrometer 打点；首个受控特性为 **rerank**，扩量调 percent、回滚调 0 或加 denylist
- 配置：`wikiagent.gray.features.<特性>.percent/allowlist/denylist`

### 1️⃣4️⃣ 离线评测体系（7 项确定性指标 + 四套件）

**零 LLM 当评委**，全部由黄金样本确定性计算（`domain/eval/metrics`）：

| 指标 | 计算器 |
|---|---|
| 检索命中率 | `RetrievalHitCalculator` |
| 引用正确率 | `CitationCorrectCalculator` |
| 字段准确率 | `FieldAccuracyCalculator` |
| 人工修改率 | `HumanEditCalculator` |
| 评判错误率 | `JudgeErrorCalculator` |
| 单轮成本 | `CostPerTurnCalculator` |
| 响应时延 | `ResponseTimeCalculator`（含 P 分位 `Percentiles`） |

四套件：检索 `RetrieveEvalSuite` / 解析 `ParseEvalSuite` / 规则 `RuleEvalSuite` / 异常 `AnomalyEvalSuite`；评测中 Milvus 与 Embedding 以"立即失败"端口强制走本地降级，**零 Docker 零外部依赖**；报告落 `eval-reports/`，API `POST /api/eval/run`、`GET /api/eval/report/latest`。

### 1️⃣5️⃣ 身份与权限

- 5 种业务身份：admin / business / product / technology / test（默认 business，最严防越权）
- 8 大业务域垂直隔离：行业解决方案 / 商家中心 / 服务商 / 干线 / 关务 / 结算 / 首公里 / 轨迹，域内再按知识类型细分
- 管理员配置身份与域权限：`user_identity` + `identity_permission_template`（V4）；检索/工具双层鉴权
- API：`GET/PUT /api/admin/identity/{userId}`、`/permissions`、`/domains`

### 1️⃣6️⃣ 降级与开关总览

| 依赖 | 缺失时行为 |
|---|---|
| MySQL | **拒绝启动**（任务状态真相源，宁停不丢） |
| Redis | 排除 Redisson 自动装配，短期记忆/锁/标志/总线全部 JVM 进程内实现 |
| Milvus | 60s 熔断 + bigram 关键词本地检索；事件库本地降级 |
| RocketMQ | JVM 本地调度器投递 |
| DashScope Key | NoOpChatModel + Embedding NoOp，应用仍健康 UP，问答走规则兜底 |
| Presidio | PII 检测按配置跳过，不阻断输出 |
| rerank | 无 Key 自动跳过；调用失败保持原序 |
| GraphRAG | 默认关；开启后抽取失败不阻断入库 |

---

## 🚀 快速开始

### 方式一：零依赖体验（推荐）

```bash
mvn spring-boot:run          # 无需 Redis/Milvus/MySQL/Key，约 17 秒启动
# 打开 http://localhost:8090 （Next.js 静态导出产物，由 Spring Boot 直接托管，单入口单进程）
# 配置 DASHSCOPE_API_KEY 解锁真实大模型；MILVUS_HOST 解锁向量检索
```

### 方式二：Docker 全家桶（生产形态）

```bash
export DASHSCOPE_API_KEY=sk-xxx
docker-compose up -d         # MySQL+Redis+Milvus(etcd/minio)+RocketMQ+Presidio+Nginx+双应用副本
# Nginx sticky-session 入口 http://localhost；副本 8081/8082
```

### 关闭 GraphRAG

GraphRAG 默认开启（入库时自动抽取实体关系，/graph 页面生效）。如需关闭：

```bash
export WIKIAGENT_GRAPH_ENABLED=false
```

### 前端开发

日常运行时只需启动后端（8090 即托管静态产物）。开发模式才需要 3000：

```bash
cd frontend && npm install && npm run dev    # http://localhost:3000，代理 /api → 8090
```

静态产物重新构建并同步到 Spring Boot：

```bash
cd frontend
NEXT_BUILD_STATIC=true npm run build          # export 静态输出到 out/
rsync -a --delete out/ ../src/main/resources/static/
mvn compile -DskipTests                       # 同步到 target/classes/static
```

### 核心环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | — | 百炼 Key（模型能力总开关） |
| `MYSQL_URL/USER/PASSWORD` | H2 文件库 | 生产切 MySQL，Flyway 自动迁移 |
| `MILVUS_HOST/PORT` | localhost:19530 | Milvus 地址 |
| `REDIS_ENABLED` / `REDIS_HOST` | false / localhost | Redis 开关；关闭全量进程内降级 |
| `TASK_MQ` | local | `local` / `rocketmq` |
| `WIKIAGENT_TASK_ENABLED` | true | 任务框架总开关（false 时任务 API 503） |
| `WIKIAGENT_PERO_ENABLED` | true | PERO 主循环开关；false 回退 v1-v2 |
| `WIKIAGENT_GRAPH_ENABLED` | true | GraphRAG 图谱抽取与检索增强 |
| `WIKIAGENT_RERANK_ENABLED` | true | gte-rerank 精排（灰度门控） |
| `WIKIAGENT_DEDUP_ENABLED` | true | L0+L1 入库去重总开关 |
| `WIKIAGENT_CONFLICT_GUARD_ENABLED` | true | 检索侧冲突守卫总开关（精判随附装配） |
| `WIKIAGENT_PARSE_PROVIDER` | dashscope | 文档富解析：none/dashscope |
| `WIKIAGENT_FALLBACK_WEB_SEARCH_ENABLED` | true | 未命中联网搜索兜底 |
| `WIKIAGENT_FALLBACK_OWN_KNOWLEDGE_ENABLED` | true | 未命中模型自身知识兜底 |
| `WIKIAGENT_INTERNAL_SECRET` | — | 信任边界共享密钥（生产必配） |
| `WIKIAGENT_CHAT_MODEL` / 嵌入模型 | qwen-plus / text-embedding-v4 | 主模型与向量化模型 |

> 完整开关登记：[docs/operations/feature-toggles.md](docs/operations/feature-toggles.md)

---

## 🖥️ 前端（Next.js 14 管理台）

| 路由 | 页面 | 看点 |
|---|---|---|
| `/?tab=chat` | 对话 | SSE 流式、6 阶段标签、停止、引用角标、👍/👎 反馈、查链路跳转 |
| `/?tab=upload` | 材料上传 | 拖拽/多文件/业务域标签/重复提示、父块/子块/删除 |
| `/?tab=tasks` | 任务中心 | 列表/详情、步骤级 SSE 进度、事件流、暂停/恢复/取消/重试 |
| `/?tab=workbench` | 人工工作台 | 复核案件、工具审批、解密队列、历史版本 diff |
| `/?tab=graph` | 知识图谱 | Canvas 力导向图、实体搜索、类型配色、跳来源文档 |
| `/?tab=upload&docId=xxx` | 结构化结果 | 字段表 + 置信度 + 冲突标记 + 页码来源 |
| `/?tab=settings` | 身份设置 | 全局业务身份与域权限 |
| `/?tab=govern` | 知识治理 | KPI 看板、疑似过期知识、冲突审核 |
| `/?tab=observe` | 可观测 | 执行路径、业务指标、知识明细、反馈审计 |

旧一级路由（`/chat`、`/upload`、`/tasks`、`/workbench`、`/graph`、`/settings`、`/results/:docId`）已统一 303 重定向到 `/?tab=xxx`，保证旧书签不 404。

---

## 🔌 API 速览

```text
# 对话
POST /api/chat                                  # SSE 流式问答主入口（produces=text/event-stream）
GET  /api/chat/sessions | /sessions/{id}/messages
POST /api/feedback                               # 有用/无用反馈（chunkId 可空）

# 文档与血缘
POST /api/documents                              # 上传入库（multipart）
GET  /api/documents | /{id}          DELETE /api/documents/{id}
GET  /api/documents/{id}/lineage | /fields/{key}/lineage
GET  /api/documents/{id}/versions | /versions/{a}/diff/{b}

# 知识图谱（WIKIAGENT_GRAPH_ENABLED=true）
GET  /api/graph/stats | /search?q= | /entity/{id} | /entity/{id}/neighbors | /doc/{id}

# 任务框架（WIKIAGENT_TASK_ENABLED=true）
POST /api/tasks                                  # 幂等提交（bizKey）
GET  /api/tasks | /{id} | /{id}/steps | /{id}/events | /{id}/human-tasks
POST /api/tasks/{id}/suspend | resume | cancel | replay
GET  /api/tasks/{id}/stream                      # 步骤进度 SSE
POST /api/human-tasks/{id}/claim | resolve

# 治理
POST /api/conflicts/scan
GET  /api/conflicts/{id} | /resolutions | /{id}/diff
POST /api/conflicts/{id}/resolve | ignore
GET  /api/metrics/dashboard | /aggregation | /knowledge | /chunk/{chunkId}

# 规则 / 复核
GET/POST /api/rules/*（版本/发布/归档/diff/ACTIVE）
GET  /api/review-cases | /{id}      POST /api/review-cases/{id}/resolve

# 评测
POST /api/eval/run          GET /api/eval/report/latest | /report/{filename}

# 追踪 / 可观测 / 管理
GET  /api/trace/session/{id} | /conversation/{id} | /user/{id}
GET  /api/observability/dashboard | feedback | gateway | knowledge
GET/PUT /api/admin/identity/{userId} | /permissions | /domains
POST /api/admin/cache/embedding/clear | answer/clear | answer/clear-by-doc
GET  /actuator/prometheus       GET /api/health
```

---

## 🗄️ 数据模型（Flyway V1–V17，共 34 张表）

| 版本 | 表 | 主题 |
|---|---|---|
| V1 | kb_document / kb_parent_chunk / kb_child_chunk | 文档与父子块 |
| V2 | agent_trace | 链路追踪 |
| V3 | audit_log | 审计 |
| V4 | user_identity / identity_permission_template | 身份权限 |
| V5 | knowledge_metadata / knowledge_metric | 知识元数据与指标 |
| V6 | kb_feedback / metric_event | 反馈与指标事件 |
| V7 | conflict_resolution | 冲突处置 |
| V8 | gateway_audit_log / content_violation_log | 安全网关审计 |
| V9 | task_instance / task_step / task_event / human_task | 任务框架四表 |
| V10–V12 | doc_version / doc_artifact / provenance_edge / extracted_field / field_version | 血缘与版本 |
| V13 | rule_set / rule_computation / review_case | 规则引擎与复核 |
| V14 | prompt_template / model_call_log | Prompt 与模型治理 |
| V15 | tool_permission | 工具权限治理 |
| V16 | agent_trace 补 trace_id | 追踪贯通 |
| V17 | graph_entity / graph_relation | GraphRAG 图谱 |
| 其余 | user_profile、handover_checklist/node/data_ref/abandoned_path 等 | 画像与交接清单 |

---

## 📁 工程结构（DDD 四层）

```text
src/main/java/com/wikiagent/
├── domain/                 # 领域层：纯业务，无框架依赖
│   ├── task/               #   状态机/步骤/事件/人工任务/异常信号 + ports 端口
│   ├── memory/             #   记忆四个端口（短期/画像/事件/交接）
│   ├── eval/               #   黄金样本 + 7 个指标计算器
│   ├── routing/ retrieve/  #   意图/路由决策；检索模型与安全上下文
│   ├── identity/ rule/ parse/ llm/ agent/ graph/ lineage/ ...
├── application/            # 应用层：用例编排
│   ├── agent/pero/         #   PERO 25 个类：Planner/ReActExecutor/Reflector/...
│   ├── multiagent/         #   DomainSupervisor 领域路由
│   ├── task/ (+handler)    #   提交/Worker/控制/恢复/看门狗/补偿 + INGEST/AGENT handler
│   ├── graph/ gray/ gateway/ knowledge/ rule/ lineage/ identity/
│   ├── eval/ (metrics/suite/support) 与 observability/ (trace/circuit)
├── infrastructure/         # 基础设施层：可插拔适配器
│   ├── memory/             #   redis/mysql/milvus/inmemory/file 五实现
│   ├── task/               #   jpa 四表 / mq(RocketMQ+本地) / redis / jvm 降级
│   ├── llm/                #   多模型工厂/Provider 链/熔断/rerank/流式
│   ├── security/ tool/ parse/ lock/ routing/ observability/ persistence/
├── interfaces/             # 接口层：REST（chat/task/graph/eval/rule/lineage/metrics/...）
├── service/                # 跨层核心服务：ingest / retrieve / chat / agent / store
└── config/                 # WikiAgentProperties 等配置绑定
frontend/                   # Next.js 14（App Router + TS + AntD + Canvas 图谱）
```

> 依赖方向：`interfaces → application → domain ← infrastructure`（依赖倒置，domain 只定义端口）。

---

## ✅ 测试与质量

- **704** 项单元/切片测试：`mvn verify -Dtest.profile=light`（零 Docker，本地全绿，0 failures / 1 skip）
- **43** 项 Testcontainers 集成测试：MySQL/Redis/RocketMQ 真实容器，仅 CI 执行（本机无 Docker 运行时）
- 前端：typecheck + lint + `next build`（10 路由）+ Playwright e2e
- 已实测端到端链路：上传 → 解析 → 降级检索命中 → 反馈 → 看板更新 → trace 回查 → 任务留痕
- 评测报告口径基线：[docs/operations/eval-baseline.md](docs/operations/eval-baseline.md)

> 本地构建需 JDK 21 + Maven 3.9（系统自带 Maven 3.6/JDK 8 会因 TLS 无法解析依赖版本范围）。

---

## 🔄 CI/CD

| 工作流 | 触发 | 内容 |
|---|---|---|
| ci.yml | push/PR | 后端 `mvn -B verify`（含 Testcontainers IT）+ 前端三件套 + Playwright |
| eval.yml | 手动/定时 | 离线评测回归与基线对比 |
| release-deploy.yml | 发布 | 镜像构建 + 部署 + 回滚预案 |

运维手册：[final-manual-checklist.md](docs/operations/final-manual-checklist.md) · [degradation-matrix.md](docs/operations/degradation-matrix.md) · [trust-boundary.md](docs/operations/trust-boundary.md) · [backup-restore.md](docs/operations/backup-restore.md)

---

## 🎤 设计要点速记（高频追问与要点）

1. **为什么切父子块？** 子块（350 字）向量化保证召回语义聚焦；父块（1000 字）只存 MySQL，命中后回溯组装，避免小片段上下文缺失——成本与质量平衡。
2. **RRF 为什么不用分数加权？** Dense 余弦分与 BM25 分量纲不同不可直接融合；RRF 只依赖排名，鲁棒且 Milvus 服务端原生支持。
3. **GraphRAG 和向量检索怎么协同？** 图扩展结果以中等固定分 0.5 注入候选池，交由 rerank 统一裁决，避免图信号喧宾夺主。
4. **Worker 凭什么不会重复执行/误杀？** CAS 租约 + owner 双条件心跳；步骤 Checkpoint + 动态步骤先合并；租约丢失边界抛信号让出。
5. **MySQL 挂了为什么拒绝启动而 Redis/MQ 可以降级？** 任务状态机以 MySQL 为唯一真相源，缓存降级会静默丢任务；Redis/MQ 只是协调与投递，可进程内替代。
6. **如何防 LLM 被注入？** 输入网关前置 + Spotlighting 数据/指令隔离 + 双锁 + 输出审核 + PII 装饰器，纵深防御而非单点。
7. **怎么省 LLM 成本？** flash 意图识别分流 plus/max；Embedding 精确缓存；rerank 灰度；预算跟踪；未命中才联网/自知识兜底。
8. **怎么保证不"一本正经胡说"？** Prompt 禁止编造 + 引用必须可溯源到版本化子块 + 未命中诚实声明来源 + 图抽取仅取文本明示事实 + 答案缓存默认关防幻觉。
9. **DDD 落地体现在哪？** domain 定义端口（ShortTermMemoryPort 等），infrastructure 五套适配器按 ConditionalOnProperty 插拔，应用层编排用例，层级依赖单向。
10. **最有挑战的 bug？** ① Milvus 宕机 500 → 熔断+bigram 降级；② 动态 NODE 步骤被静态 GENERATE 步骤插队导致跳过 → mergeRegistered 前置；③ PERO Bean 与 v1-v2 互斥 → ObjectProvider 四层降级；④ 心跳事件撑爆 task_event → 心跳不入库。

---

## 📚 深入阅读

| 文档 | 内容 |
|---|---|
| [docs/PRODUCTION_AGENT_PLAN.md](docs/PRODUCTION_AGENT_PLAN.md) | 完整技术方案（含实施校正记录） |
| [docs/技术方案.md](docs/技术方案.md) | 架构设计细节 |
| [docs/部署指南.md](docs/部署指南.md) | 生产部署手册 |
| [docs/operations/](docs/operations/) | 开关登记 / 灰度 / 降级矩阵 / 信任边界 / 备份恢复 / 评测基线 |
| [docs/superpowers/](docs/superpowers/) | 任务框架设计规格与 B–J 实施计划 |

---

**Wiki Agent** — 让企业知识真正"活"起来的智能体工程实践 🚀
