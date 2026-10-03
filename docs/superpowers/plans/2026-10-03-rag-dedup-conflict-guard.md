# RAG 近重复去重与冲突防御实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为知识库建立"入库去重 + 检索冲突守卫 + 生效时间裁决 + 人工仲裁"的分层防御，解决两条相似知识（差一个字）造成的冗余与幻觉风险。

**Architecture:** 同文档无冲突（is_active 已保证旧版本不可见），冲突只发生在跨文档之间；跨文档裁决以 `kb_document.effective_date`（生效时间，用户录入时显式填写）为唯一权威信号，version_no 退出裁决逻辑、仅作溯源。入库侧：L0 精确 hash 去重 → L1 MinHash-LSH 近重复检测（关键 token diff 区分冗余/冲突）；检索侧：rerank 后两阶段 ConflictGuard（余弦粗筛零成本 → qwen-flash 结构化精判 → 系统按生效时间确定性移除输家 chunk，LLM 只检测不裁决）；兜底：复用现有 ConflictResolutionService 人工仲裁（KEEP_A/B、MERGE、DELETE_A/B）。

**Tech Stack:** Java 21 / Spring Boot / Spring AI (ChatModel) / MySQL + Flyway / Redis (Redisson) / Milvus / JUnit 5 / Next.js 控制台。

**Spec:** 本计划即规格。设计共识来自 2026-10-03 会话：版本号不可跨文档比较（版本号=单文档编辑计数器，非新鲜度时钟）；生效时间相同或缺失 → 不自动选边；关键 token（金额/数字/日期/否定词）冲突 → 推荐+人工确认，非关键差异才自动合并；在线检测不阻塞用户问答。

## Global Constraints

- Milvus 不支持 chunk 级删除：所有"移除"一律 `is_active=false` 软删（MySQL），检索侧靠关系库权威过滤。
- MySQL 是状态真相源，Redis 只做协调（LSH 签名桶）；Redis 不可用时 L1 降级为跳过近重复检测并告警，不得阻断入库。
- 新能力开关：`wikiagent.dedup.enabled`、`wikiagent.conflict-guard.enabled` 默认均 false；ConflictGuard 另接 GrayReleaseService 特性名 `conflict-guard`。（Task 10 E2E 更正：守卫开关最终采用虚线式扁平名 `conflict-guard`——`wikiagent.conflict.*` 已被离线扫描占用，两级 `conflict.guard` 绑不进 record 单级组件 `conflictGuard`。）
- 打点沿用 metric_event：`MetricEventEntity(chunkId, eventType, userId, sessionId, similarity, createdAt)`；新事件类型见 Task 7，chunk 级事件必须带真实 chunkId，禁止伪造。
- 冲突裁决全程可审计：每次自动移除/保留都要写日志 + metric_event + （涉冲突时）conflict_resolution 记录。
- 禁止虚构 Milvus chunk 级删除 API；诚实声明注释风格沿用现有代码。
- 本机无 Docker：Testcontainers IT 只能 CI 验证；本地验证用 `mvn test-compile` + `mvn verify -Dtest.profile=light`。
- 本机 Maven 用 `~/.local/share/maven`（3.9），JDK 用 `~/.local/share/jdks`（21）；系统 PATH 的 Maven 3.6/JDK 8 不可用。
- 测试上下文若 flyway 关闭 + ddl-auto=update，必须用独立 H2 文件 URL 与默认库隔离。

## Review Focus

1. **存量文档 effective_date 为 null**（迁移后老数据）：裁决不得崩溃、不得默认选边——按"缺失→双保留+Prompt 标注+写冲突单"处理。→ Task 7 测试钉死。
2. **LLM 精判返回候选集外的 chunkId / 非法 JSON / 超时**：一律视为"无冲突"放行并告警，绝不阻断问答主链路。→ Task 6 测试钉死。
3. **超短 chunk（长度 < 2，bigram 为空）**：MinHash 签名为空时跳过 LSH 插入与查询，不报错。→ Task 3 测试钉死。
4. **传递冲突组（A~B、B~C 两两冲突）**：按组处理，只保留组内生效时间最晚的一条 chunk，其余软移除；生效时间并列最晚 → 该组全部保留+标注。→ Task 7 测试钉死。
5. **重解析事务边界**：L0/L1 交换 is_active 必须与 splitStep 同一事务（@Transactional 内调用），防止半激活状态被检索命中。→ Task 2/4 测试钉死。

---

### Task 1: V19 迁移 + 实体字段

**Files:**
- Create: `src/main/resources/db/migration/V19__dedup_conflict_guard.sql`
- Modify: `src/main/java/com/wikiagent/entity/KbDocument.java`（加 3 字段）
- Modify: `src/main/java/com/wikiagent/entity/KbChildChunk.java`（加 contentHash）
- Modify: `src/main/java/com/wikiagent/infrastructure/persistence/ConflictResolutionEntity.java`（加 source、resolutionHint）
- Test: `src/test/java/com/wikiagent/persistence/V19MigrationSchemaTest.java`

**Interfaces:**
- Produces: `KbDocument.getEffectiveDate()/getEffectiveSetBy()/getEffectiveSetAt(): java.time.LocalDate / String / Instant`（均 nullable）；`KbChildChunk.getContentHash(): String`（nullable，存量行回填 null）；`ConflictResolutionEntity.getSource()/getResolutionHint(): String`。

- [ ] **Step 1: 写失败测试**——H2 内存库执行 V19 SQL（直接 `RunScript` 执行文件），断言 `kb_document` 存在 `effective_date DATE`、`effective_set_by VARCHAR(64)`、`effective_set_at TIMESTAMP`；`kb_child_chunk` 存在 `content_hash VARCHAR(64)` 及普通索引 `idx_child_content_hash`；`conflict_resolution` 存在 `source VARCHAR(32)`、`resolution_hint VARCHAR(16)`。
- [ ] **Step 2: 跑测试确认失败**——`mvn test -Dtest=V19MigrationSchemaTest`，预期列不存在报错。
- [ ] **Step 3: 实现迁移 + 实体**——V19 全部 `ALTER TABLE ... ADD COLUMN`，列允许 NULL（存量兼容）；实体加字段+getter/setter，不加非空约束。
- [ ] **Step 4: 跑测试确认通过**。
- [ ] **Step 5: Commit**——`git add` 上述文件，`git commit -m "feat: V19 dedup/conflict-guard schema (effective_date, content_hash, conflict source)"`。

### Task 2: L0 入库精确去重（ContentHasher）

**Files:**
- Create: `src/main/java/com/wikiagent/service/ingest/ContentHasher.java`
- Modify: `src/main/java/com/wikiagent/service/ingest/IngestionService.java`（splitStep 内）
- Modify: `src/main/java/com/wikiagent/repo/KbChildChunkRepo.java`（加查询方法）
- Test: `src/test/java/com/wikiagent/service/ingest/ContentHasherTest.java`、`IngestionServiceExactDedupTest.java`

**Interfaces:**
- Produces: `ContentHasher.sha256Normalized(String text): String`——规范化=去除全部空白字符 `\\s+`、拉丁转小写，再 SHA-256 hex；`KbChildChunkRepo.findFirstByContentHashAndActiveTrue(String hash): Optional<KbChildChunk>`。

- [ ] **Step 1: 写失败测试**——`ContentHasherTest`： `"苹果 是 5 元"` 与 `"苹果是5元"` 同 hash；`"Apple"` 与 `"apple"` 同 hash；不同内容不同 hash。`IngestionServiceExactDedupTest`：入库文档 B 的某 chunk 与已有 active chunk（文档 A）规范化后完全相同 → 新 chunk 落库但 `is_active=false`，且当 B 的 effective_date 晚于 A 时改为 A 的 chunk 置 false、B 的保持 true（交换激活在同一事务）；metric_event 写入 `DEDUP_EXACT_SKIPPED`。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现**——splitStep 构造每个 child 时 `setContentHash(...)`；在保存后、返回前，对本批 chunk 逐条查 `findFirstByContentHashAndActiveTrue`（排除本 docId）：命中时按"生效时间晚者留 active，早者置 false；相同/缺失则留旧（已存在者）"处理，双方实体在同一 @Transactional 内 save；打 metric_event。
- [ ] **Step 4: 跑测试确认通过** + `mvn test -Dtest=IngestionServiceTest` 回归既有用例。
- [ ] **Step 5: Commit**——`git commit -m "feat: L0 exact dedup via content_hash with effective-date swap"`。

### Task 3: MinHash-LSH 与关键 token 判定器（纯工具，无 Spring）

**Files:**
- Create: `src/main/java/com/wikiagent/application/knowledge/dedup/MinHashSignature.java`
- Create: `src/main/java/com/wikiagent/application/knowledge/dedup/RedisLshIndex.java`
- Create: `src/main/java/com/wikiagent/application/knowledge/dedup/KeyTokenDiffer.java`
- Test: `src/test/java/com/wikiagent/application/knowledge/dedup/MinHashSignatureTest.java`、`RedisLshIndexTest.java`、`KeyTokenDifferTest.java`

**Interfaces:**
- Produces:
  - `MinHashSignature.of(String text): long[]`（128 perm；shingle=CJK 字符 bigram + 拉丁词，复用 RetrievalService L513-534 的切词思路但独立实现）；`MinHashSignature.jaccard(long[] a, long[] b): double`。
  - `RedisLshIndex`（构造注入 `StringRedisTemplate`）：`Set<String> query(long[] sig)`、`void put(String chunkId, long[] sig)`、`void remove(String chunkId, long[] sig)`；分带 32 bands × 4 rows；key：`lsh:band:{bandIdx}:{bandHash}`（Set 存 chunkId），`lsh:sig:{chunkId}`（String 存签名 Base64，供 remove）。
  - `KeyTokenDiffer.hasCriticalDiff(String a, String b): boolean`——先做最长公共前后缀剥离取 diff 区间，diff 内命中以下任一即 true：数字/小数/百分号、日期模式（`\\d{4}[-/年]`等）、货币量词（元/万/千/亿）、否定词（不/未/无/非/禁止/不得/取消）。

- [ ] **Step 1: 写失败测试**——MinHash：`"苹果是5元。…(50字正文)"` 与差 1 字版本 jaccard ≥ 0.9；完全不同文本 < 0.3；空/单字文本返回空签名不抛异常（Review Focus #3）。RedisLshIndex：用嵌入式 mock（或 fake StringRedisTemplate 封装接口）验证 put→query 命中、remove 后 miss。KeyTokenDiffer：`"苹果是5元"vs"苹果是3元"`→true；`"请及时处理"vs"请尽快处理"`→false；`"可以申请"vs"不可以申请"`→true。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现三个类**——MinHash 用 128 个随机种子 `(a*i+b) mod prime` 族对 shingle 的 64 位 hash 取最小值；RedisLshIndex 的 bandHash 对每带 4 个 long 做 Arrays.hashCode；所有 Redis 操作包 try/catch，异常降级为空结果+warn 日志（Global Constraint：Redis 不可用不阻断）。
- [ ] **Step 4: 跑测试确认通过**。
- [ ] **Step 5: Commit**——`git commit -m "feat: MinHash-LSH index + key-token differ utilities"`。

### Task 4: L1 入库近重复守卫（NearDuplicateGuard）

**Files:**
- Create: `src/main/java/com/wikiagent/application/knowledge/dedup/NearDuplicateGuard.java`
- Modify: `src/main/java/com/wikiagent/service/ingest/IngestionService.java`（embedAndPersistStep 开头调用，省 embedding 费用）
- Modify: `src/main/java/com/wikiagent/config/WikiAgentProperties.java`（加 `dedup` 配置）
- Test: `src/test/java/com/wikiagent/application/knowledge/dedup/NearDuplicateGuardTest.java`

**Interfaces:**
- Consumes: Task 3 的 `MinHashSignature/RedisLshIndex/KeyTokenDiffer`；Task 1 的 `KbDocument.getEffectiveDate()`。
- Produces: `NearDuplicateGuard.inspect(String docId): int`（返回处理的近重复对数）；配置 `wikiagent.dedup.enabled:false`、`wikiagent.dedup.near-jaccard:0.9`（WikiAgentProperties 新增 `record Dedup(boolean enabled, double nearJaccard)`，`WikiAgentProperties.dedup()`）。

- [ ] **Step 1: 写失败测试**——构造：active chunk X（doc A，effective_date=2025-01-01）签名已入 LSH；新 doc B（effective_date=2026-01-01）含 chunk Y 与 X 仅差非关键虚词 → inspect(B) 后 X `is_active=false`、Y 保持 true、metric_event `DEDUP_NEAR_MERGED`；若差在关键 token（5元→3元）→ 双方均不动，写 conflict_resolution（status=DETECTED, source=`MINHASH_INGEST`，resolution_hint=`CONFLICT`），chunkIdA/B=双方 id；同 docId 命中跳过；`wikiagent.dedup.enabled=false` 时 inspect 直接返回 0；Redis 抛异常时降级跳过并告警不阻断。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现**——inspect 流程：读 doc 全部 active chunk → 逐条算签名 → LSH query 候选 → 桶内精确 jaccard ≥ 阈值才认定 → 排除同 docId → KeyTokenDiffer 分流（非关键=自动合并留生效时间晚者；关键=写冲突单）→ 存活 active chunk 的签名 put 进 LSH。交换激活与 chunk 状态修改在调用方事务内（embedAndPersistStep 开头、embedding 之前调用；整个方法保持 @Transactional 语义与现有代码一致）。冲突单 pairKey 去重沿用无序对规则。
- [ ] **Step 4: 跑测试确认通过** + IngestionService 既有测试回归。
- [ ] **Step 5: Commit**——`git commit -m "feat: L1 ingest near-duplicate guard (MinHash-LSH + key-token split)"`。

### Task 5: ConflictGuard 粗筛（检索侧候选对检测）

**Files:**
- Create: `src/main/java/com/wikiagent/service/retrieve/ConflictCandidateDetector.java`
- Test: `src/test/java/com/wikiagent/service/retrieve/ConflictCandidateDetectorTest.java`

**Interfaces:**
- Consumes: Task 3 的 `KeyTokenDiffer`。
- Produces: `record RepChunkView(String childId, String docId, String content, double score)`；`ConflictCandidateDetector.detect(List<RepChunkView> chunks, float[][] vectors, double coarseThreshold): List<int[]>`（返回疑似对的下标对；vectors 与 chunks 按下标对齐，来源见 Task 7——优先用 Milvus 查询返回的 dense 向量，取不到则 EmbeddingModel 重 embed 代表子块并走 EmbeddingCacheService）。

- [ ] **Step 1: 写失败测试**——3 条候选：两条余弦 0.92 且关键 token 不同 → 返回该对；两条余弦 0.92 但仅虚词不同 → 不返回（非冲突，交给冗余折叠，不在守卫范围）；余弦 0.6 → 不返回；候选 ≤1 → 空。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现**——O(k²) 两两余弦（k=最终候选数，≤finalTopk，量级 ≤20，成本可忽略），阈值参数化；命中相似后再过 `KeyTokenDiffer.hasCriticalDiff`，双条件都满足才进疑似对。
- [ ] **Step 4: 跑测试确认通过**。
- [ ] **Step 5: Commit**——`git commit -m "feat: retrieval conflict candidate detector (coarse cosine + key-token)"`。

### Task 6: ConflictGuard LLM 精判（qwen-flash 结构化输出）

**Files:**
- Create: `src/main/java/com/wikiagent/service/retrieve/ConflictLlmJudge.java`
- Test: `src/test/java/com/wikiagent/service/retrieve/ConflictLlmJudgeTest.java`

**Interfaces:**
- Produces: `record ConflictVerdict(String chunkIdA, String chunkIdB, String entity, String attribute, String valueA, String valueB)`；`ConflictLlmJudge.judge(String query, List<RepChunkView> pair): Optional<ConflictVerdict>`。Bean 装配：`@ConditionalOnProperty(name="wikiagent.conflict-guard.enabled", havingValue="true")`（Task 10 更正为虚线式扁平名，原因见全局约束）；构造注入 `@Qualifier("intentChatModel") ChatModel`（默认 qwen-flash，与 DashScopeLlmRouter L61-70 同一路由）。

- [ ] **Step 1: 写失败测试**——mock ChatModel 返回合法 JSON `{"conflict":true,"entity":"苹果","attribute":"价格","valueA":"5元","valueB":"3元"}` → 解析为 ConflictVerdict；返回 `{"conflict":false}` → empty；返回非法 JSON / 候选集外 chunkId / 抛异常超时 → empty + warn（Review Focus #2，绝不阻断）。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现**——Prompt 要求仅输出单行 JSON（instruction 内嵌两条 chunk 原文与 query，声明"只判断是否对同一实体同一属性给出不同事实值"）；ObjectMapper 解析（风格沿用 DashScopeLlmRouter.callIntent）；返回的 chunkIdA/B 必须等于输入对的两个 id（顺序可调换），否则丢弃；调用用 ModelCallRecorder.record(purpose="CONFLICT_JUDGE", model=qwen-flash, ...) 打点。
- [ ] **Step 4: 跑测试确认通过**。
- [ ] **Step 5: Commit**——`git commit -m "feat: LLM conflict judge with structured verdict (qwen-flash, fail-open)"`。

### Task 7: ConflictGuard 裁决与检索链路集成

**Files:**
- Create: `src/main/java/com/wikiagent/service/retrieve/RetrievalConflictGuard.java`
- Modify: `src/main/java/com/wikiagent/service/retrieve/RetrievalService.java`（L629-696 组装段之前插入守卫；构造器加可选注入）
- Modify: `src/main/java/com/wikiagent/config/WikiAgentProperties.java`（加 `conflictGuard` 配置）
- Modify: `src/main/java/com/wikiagent/interfaces/observability/ObservabilityController.java`（指标块）
- Test: `src/test/java/com/wikiagent/service/retrieve/RetrievalConflictGuardTest.java`

**Interfaces:**
- Consumes: Task 5 `RepChunkView/ConflictCandidateDetector`、Task 6 `ConflictLlmJudge/ConflictVerdict`、Task 1 `KbDocument.getEffectiveDate()`。
- Produces: `RetrievalConflictGuard.apply(String query, List<RepChunkView> candidates): GuardResult`；`record GuardResult(List<RepChunkView> kept, String conflictNote /* nullable，追加进 ctx 的标注段 */, List<ConflictVerdict> verdicts)`；配置 `wikiagent.conflict-guard.enabled:false`、`wikiagent.conflict-guard.coarse-cosine:0.85`（虚线式扁平名，Task 10 更正）；新 metric eventType：`CONFLICT_GUARD_DETECTED`/`CONFLICT_GUARD_REMOVED`/`CONFLICT_GUARD_KEPT_BOTH`；conflict_resolution.source=`ONLINE_GUARD`。

- [ ] **Step 1: 写失败测试**——（a）候选含苹果5元(doc A, eff 2025-01-01)与苹果3元(doc B, eff 2026-01-01)，mock judge 确认冲突 → kept 只含 B 的 chunk，`CONFLICT_GUARD_REMOVED` 打点，写 DETECTED 冲突单（pairKey 已存在任意状态则不重复写）；（b）双方 effective_date 相同或一方为 null → 双保留，conflictNote 含"来源A（生效于X）…与来源B（生效于Y）…冲突"字样，打点 `CONFLICT_GUARD_KEPT_BOTH`（Review Focus #1）；（c）三条传递冲突组 → 只留 eff 最晚者（Review Focus #4）；（d）守卫开关关闭 / grayRelease.isEnabled("conflict-guard", identity) 未命中 → 原样透传零副作用；（e）粗筛无疑似对 → 不调 judge（mock 验证零调用）。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现**——RetrievalService 构造器按 rerankProvider 同款"可选注入"模式加 `RetrievalConflictGuard`（nullable/ObjectProvider）；在 maybeRerank 之后、按 parentCharBudget 组装之前调用 apply；移除粒度=chunk（输家 chunk 从该父块的代表子块候选中剔除，父块若无存活代表子块才整体剔除）；conflictNote 以独立段落拼进 ctx 末尾；裁决只做系统侧确定性比较（eff 晚者赢），LLM 输出不参与选边。
- [ ] **Step 4: 跑测试确认通过** + `mvn verify -Dtest.profile=light` 全量轻量回归（基线 682+ 全绿）。
- [ ] **Step 5: Commit**——`git commit -m "feat: retrieval conflict guard with effective-date arbitration"`。

### Task 8: 生效时间录入链路（后端 + 前端）

**Files:**
- Modify: `src/main/java/com/wikiagent/controller/DocumentController.java`（L57-116 同步路径 + L119-164 任务框架路径）
- Modify: `src/main/java/com/wikiagent/application/task/handler/IngestTaskHandler.java`（payload 透传）
- Modify: 前端 Next.js 控制台文档上传组件（Glob `web/` 下上传弹窗/表单，含 `Upload` 或 `document` 关键词的文件）
- Test: `src/test/java/com/wikiagent/controller/DocumentControllerEffectiveDateTest.java`

**Interfaces:**
- Consumes: Task 1 的 `KbDocument` 新字段。
- Produces: `POST /api/documents` 新增可选表单参数 `effectiveDate`（ISO-8601 `yyyy-MM-dd`）；缺省=服务器当前日期；TaskPayload("INGEST", ...) 的 payload map 增加 `effectiveDate` 键。

- [ ] **Step 1: 写失败测试**——MockMvc 上传带 `effectiveDate=2026-01-15` → kb_document 行 `effective_date=2026-01-15`、`effective_set_by`=X-User-Id 头、`effective_set_at` 非空；不传 → 默认当前日期；非法格式 → 400。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现后端**——两条上传路径都解析该参数；同步路径直接落库，任务框架路径经 payload 透传到 handler 落库。
- [ ] **Step 4: 实现前端**——上传弹窗加日期选择器，默认今天，附一行说明文案"文档内容生效日期，冲突时以此为准"；提交时带上 `effectiveDate`。
- [ ] **Step 5: 验证**——后端测试通过；前端 `npm run typecheck && npm run lint && npm run build` 0 错误。
- [ ] **Step 6: Commit**——`git commit -m "feat: effective_date capture on document upload (api + console)"`。

### Task 9: 离线扫描分流 + 观测指标

**Files:**
- Modify: `src/main/java/com/wikiagent/application/knowledge/ConflictDetectionService.java`
- Modify: `src/main/java/com/wikiagent/interfaces/observability/ObservabilityController.java`
- Test: `src/test/java/com/wikiagent/application/knowledge/ConflictDetectionServiceTest.java`（既有，扩展）

**Interfaces:**
- Consumes: Task 3 `KeyTokenDiffer`；Task 1 的 `ConflictResolutionEntity.setSource/setResolutionHint`。

- [ ] **Step 1: 写失败测试**——扫描命中相似对时：关键 token diff → resolution_hint=`CONFLICT`；仅虚词差异 → resolution_hint=`REDUNDANT`；source 一律 `SCHEDULED_SCAN`。
- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现**——saveConflict 前过 KeyTokenDiffer 填 hint；ObservabilityController 在现有 pendingConflicts（L84）旁加：近 7 天各 metric eventType（DEDUP_EXACT_SKIPPED / DEDUP_NEAR_MERGED / CONFLICT_GUARD_*）计数，按 eventType group by。
- [ ] **Step 4: 跑测试确认通过**。
- [ ] **Step 5: Commit**——`git commit -m "feat: offline scan redundancy/conflict split + dedup metrics"`。

### Task 10: 端到端验证 + 文档

**Files:**
- Create: `src/test/java/com/wikiagent/service/retrieve/ConflictGuardFlowIT.java`（H2 + stub ChatModel，独立 H2 URL 与 flyway 库隔离）
- Modify: `README.md`（知识治理章节加"近重复与冲突防御"小节）

**Interfaces:**
- Consumes: Task 1-9 全部。

- [ ] **Step 1: 写 IT**——两份文档（苹果5元 eff 旧 / 苹果3元 eff 新）入库（dedup 开启但关键 token 差异→应写冲突单不自动合并）→ 检索"苹果多少钱"（guard 开启、judge stub 确认冲突）→ 断言上下文只含 3 元、conflict_resolution 有 ONLINE_GUARD 与 MINHASH_INGEST 两条单、metric_event 计数正确。
- [ ] **Step 2: 跑 IT 确认失败→实现修复→通过**（预期主要暴露 wiring 遗漏）。
- [ ] **Step 3: README**——新增小节描述五层防御、两个开关、生效时间录入约定；诚实声明 MinHash 阈值与 LLM 精判 fail-open 语义。
- [ ] **Step 4: 全量轻量回归**——`mvn verify -Dtest.profile=light` 全绿。
- [ ] **Step 5: Commit**——`git commit -m "test+docs: conflict guard E2E flow and readme section"`。

---

## Self-Review 记录

- 规格覆盖：L0(Task2)/L1(Task3-4)/L2 粗筛+精判+裁决(Task5-7)/生效时间录入(Task8)/离线分流与观测(Task9)/E2E(Task10) — 无缺口。
- 类型一致性：`RepChunkView/ConflictVerdict/GuardResult/ContentHasher/MinHashSignature/RedisLshIndex/KeyTokenDiffer/NearDuplicateGuard/ConflictCandidateDetector/ConflictLlmJudge/RetrievalConflictGuard` 跨任务签名已对齐。
- Review Focus 五条均已落到对应任务测试。
- 刻意不做：MMR（候选规模 ≤20 且 rerank 已在线，近重复折叠已被守卫覆盖，等真实数据证明冗余仍显著再加）；逻辑文档 lineage_id（跨文档归并留作后续演进，本期靠生效时间+人工仲裁闭环）。
