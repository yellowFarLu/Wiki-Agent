# 知识看板 RAG 准确率统计 — 方案与实现计划

## 背景

知识看板（`/api/metrics/aggregation` + `frontend/components/govern/GovernPanel.tsx`）目前只有：知识总量、近 30 天检索/引用、有用率（kb_feedback）、召回率（实为 CITED/RETRIEVED 引用率）、疑似过期。缺少回答质量类"准确率"指标。

参考业界 RAG 评测（RAGAS faithfulness / answer relevancy；Langfuse、LangSmith 的 Online Evaluation）：线上流量没有标准答案，"准确率"只能用两类**真实信号**近似——LLM-as-judge 抽样评判、用户反馈。项目已有 CRAG 充分性评估（AgentRagService.grade，LLM-as-judge 范式）与 `ModelCallLogPurpose.JUDGE` 打点枚举可直接复用。

**铁律（禁止捏造事实）**：
- 无法评判的维度置 `null`，**不计入分母**（先例：`RetrievalHitCalculator` 无相关性标签不计分母）
- 无数据显示"暂无数据"；分母为 0 的率返回 null 而非 0
- judge 空响应/解析失败不得记为"评判不通过"，只能保持 PENDING 或置 FAILED

## 交付物一：统计方案文档 `docs/rag-accuracy-eval.md`

指标体系（写入文档，供查阅）：

| 指标 | 业界对应 | 数据来源 | 计算公式 |
|---|---|---|---|
| answerAccuracy 答案准确率 | RAGAS answer correctness 的线上近似 | rag_answer_eval（LLM-as-judge） | Σ(faithfulness=1∧relevance=1) / Σ(两维均≠null) |
| faithfulnessRate 忠实度 | RAGAS faithfulness | 同上 | Σ(f=1) / Σ(f≠null) |
| relevanceRate 相关性 | RAGAS answer relevancy | 同上 | Σ(r=1) / Σ(r≠null) |
| usefulnessRate 有用率（已有） | 用户反馈信号 | kb_feedback | 不变 |
| recallRate（已有，文档中如实说明其为引用率 CITED/RETRIEVED） | — | metric_event | 不变 |
| citationCorrectRate / retrievalHitRate（已有离线黄金集） | 离线评测 | eval 子系统 | 不重复建设 |

文档需含诚实声明：① judge 判定是模型近似而非真值，verdict_reason 供人工复核；② 无引用上下文的样本（PERO multi-agent、v1v2、闲聊/联网兜底路径不发 sources 事件）faithfulness=null，只评 relevance；③ 上下文为评判时点回查的当前文档内容，文档事后更新会引入偏差。

## 交付物二：代码实现

### 新增（5 个文件）

1. **`src/main/resources/db/migration/V18__rag_answer_eval.sql`**
   表 `rag_answer_eval`：id、session_id、user_id、question(脱敏后)、answer(脱敏后)、answer_hash(SHA-256)、source_doc_ids(JSON 数组，可 null)、channel(multi-agent/orchestrator-v1v2/agent-rag/legacy-rag)、judge_model(链降级后真实模型)、faithfulness TINYINT(null=无法评判)、relevance TINYINT、verdict_reason(1024)、status(PENDING/JUDGED/FAILED)、error、attempts、created_at、judged_at；唯一约束 `uk(session_id, answer_hash)` 防重；索引 `(status, created_at)`。

2. **`infrastructure/persistence/RagAnswerEvalEntity.java`** + **`RagAnswerEvalJpaDao.java`**（照 MetricEventEntity 风格）
   DAO 派生查询：`existsBySessionIdAndAnswerHash`、`findTop50ByStatusOrderByCreatedAtAsc`、`countByStatus`、`countByFaithfulnessIsNotNull`、`countByRelevanceIsNotNull`、`countByFaithfulness(Integer)`、`countByRelevance(Integer)`、`countByFaithfulnessAndRelevance(Integer, Integer)`。

3. **`application/knowledge/RagEvalSampleRecorder.java`**
   `@ConditionalOnProperty(wikiagent.answer-eval.enabled)`（**默认 true**）+ `@Value sample-rate:1.0`。`record(sessionId, userId, question, answer, List<Source>, channel)`：全方法 try-catch 失败仅 WARN（绝不影响对话）；采样掷骰；空白过滤；SHA-256 防重；docId 去重保序写 JSON（空→null）；status=PENDING。

4. **`application/knowledge/RagAnswerJudgeService.java`**
   同款条件装配。注入 `RagAnswerEvalJpaDao`、`KbParentChunkRepo`（已有 `findByDocIdOrderByParentIndex`，无需加 DAO）、`KbDocumentRepo`、`ChatModel`（与 AgentRagService 同款直注）、`ObjectProvider<ChatModelProviderChain>`、`ObjectProvider<ModelCallRecorder>`。
   `@Scheduled(cron="${wikiagent.answer-eval.judge.cron:0 20 * * * ?}")` + `judgePendingBatch()`：拉 PENDING 限 batch-size → 逐条评判（单条 try-catch）：
   - source_doc_ids → 父块 content 拼接（docRepo 补 filename 标题行），总预算 `context-char-budget:6000` 截断
   - LLM 调用复刻 `AgentRagService.call`（chain 优先，purpose=JUDGE；空响应/异常 → attempts+1，≥max-attempts 置 FAILED，否则保持 PENDING）
   - `JsonExtractor.parseObject` 三态解析：缺 key/显式 null → 维度 null；空 Map → 失败；成功 → JUDGED + judgeModel=链响应实际 model + judgedAt

5. **Judge Prompt**（常量放 RagAnswerJudgeService，中文，要求只输出 JSON）：
   - system：评测员角色，faithfulness=回答事实论断是否均被参考资料支撑（无事实论断/无参考资料 → null）；relevance=是否切题回答（答非所问/回避 → false）；输出 `{"faithfulness":true|false|null,"relevance":true|false|null,"reason":"≤50字"}`
   - user 模板：用户问题 / 助手回答 / 参考资料（无来源时替换为"本次回答没有引用知识库文档，faithfulness 请判 null"）

### 修改（7 个文件）

6. **`service/chat/ChatService.java`**（唯一触碰对话主链路的文件）
   - 构造器加 `ObjectProvider<RagEvalSampleRecorder>`（字段判空，null 时零影响）
   - `doChat`：三个闭包容器 `AtomicReference`（sources / 脱敏后 question / channel）；L159 装配点外再包一层私有静态内部类 `RagEvalCaptureSender extends SseSender`（照 `AnswerCacheWritingSender` 范式，拦截 `"sources"` 事件缓存后透传；capture 在 guardrail 外层：`capture(guardrail(base))`）
   - `AnswerFinalizer.onAnswer` 内（保留原 history 落库）调 `recorder.record(...)`——此时 finalAnswer 已过输出网关脱敏；blocked 路径不采集
   - L188 后 `questionRef.set(question)`；四分支各 set channel

7. **`application/knowledge/MetricsAggregationJob.java`**
   - 构造器加 `RagAnswerEvalJpaDao`；新嵌套 record `AnswerEvalSummary(judgedCount, pendingCount, failedCount, accuracyRate, faithfulnessRate, relevanceRate, accuracyJudged, faithfulnessJudged, relevanceJudged)`，静态工厂中分母为 0 → null
   - `MetricsSnapshot` 加 `answerEval` 组件 + `toMap()` 输出；`publishGauges` 加 3 个 gauge（`wikiagent.rag.eval.accuracy.rate` 等）

8. **`interfaces/metrics/MetricsController.java`**
   - `GET /api/metrics/answer-eval/samples?limit=20`：最新 N 条裁剪 Map（question/answer 截 200 字）
   - `POST /api/metrics/answer-eval/judge?limit=50`：手动触发评判（ObjectProvider 注入，未启用返回说明 JSON）

9. **`src/main/resources/application.yml`**：`wikiagent.answer-eval` 段（enabled 默认 true、sample-rate 1.0、judge.cron/batch-size 50/max-attempts 3/context-char-budget 6000），环境变量 `WIKIAGENT_ANSWER_EVAL_ENABLED` 等可覆盖

10. **`frontend/lib/types.ts`**：`AnswerEvalSummary`、`AnswerEvalSampleItem`；`MetricsAggregation` 加 `answerEval?`

11. **`frontend/lib/api.ts`**：`getAnswerEvalSamples(limit)`

12. **`frontend/components/govern/GovernPanel.tsx`**：
    - `pct` 签名改为接受 `number | null | undefined`（null → '-'）
    - KPI 追加"RAG 准确率"卡：率 + 已评/待评数；率 null 显示 '-'
    - 疑似过期表后新增"最近评判样本"表：问题摘要/channel/faithfulness Tag(有据/无据/未评)/relevance/判定原因/状态/时间；空文案"暂无评判样本"；mount 时加载，"立即聚合"同时刷新两路

### 测试

- `RagEvalSampleRecorderTest`：落库字段/JSON 去重保序、防重跳过、sample-rate=0 跳过、DAO 异常被吞
- `RagAnswerJudgeServiceTest`：三态解析、无来源样本 prompt 含"没有引用知识库文档"、空响应→PENDING+attempts+1、attempts≥max→FAILED、垃圾 JSON 不误判、judgeModel 取实际响应
- `ChatServiceRagEvalCaptureTest`：legacy 路径合规答案→recorder 收到 sources/channel/question；blocked→不调 recorder；recorder 为 null→零 NPE
- `RagAnswerEvalDaoIT`（light，照 `ObservabilityMetricsIT`：H2 file URL MODE=MySQL + flyway）：V18 可执行、唯一约束防重、null 不计分母的 count 语义
- `ChatServiceOutputGuardrailTest` 构造补参（ObjectProvider mock 返回 null）

## 验证

```bash
export JAVA_HOME=/Users/huangyuan/.local/share/jdks/jdk-21.0.2.jdk/Contents/Home
MVN=/Users/huangyuan/.local/share/maven/apache-maven-3.9.16/bin/mvn
$MVN -B test-compile
$MVN -B verify -Dtest.profile=light          # 全量 light 回归（H2，无 Docker）
cd frontend && npx tsc --noEmit && npm run build
```

运行时冒烟（dev，默认已启用）：前端问一个知识库问题 → `POST /api/metrics/answer-eval/judge` → `GET /api/metrics/aggregation?refresh=true` 核对 answerEval 段 → 看板卡片与样本表渲染。
