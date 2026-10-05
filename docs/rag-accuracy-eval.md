# 知识看板 RAG 回答准确率统计方案

> 版本：2026-10-03（V18 引入）
> 原则：**禁止捏造事实**——所有指标必须由真实数据计算得出；无法评判的样本不计入分母；无数据时看板如实显示"暂无数据"。

## 1. 背景

知识看板原有指标（`MetricsAggregationJob` / `GET /api/metrics/aggregation`）只覆盖使用行为与反馈：知识总量、近 30 天检索/引用、有用率、召回率（CITED/RETRIEVED）、疑似过期知识。缺少"回答质量"维度的准确率指标。

参考业界 RAG 评测方案：

- **RAGAS**：faithfulness（忠实度）、answer relevancy（答案相关性）、context precision/recall、answer correctness；
- **Langfuse / LangSmith Online Evaluation**：线上流量按采样率抽取 (question, answer, context) 三元组，用 LLM-as-judge 按固定维度评判后聚合为通过率。

线上流量没有标准答案（golden answer），"准确率"只能由两类**真实信号**近似：LLM-as-judge 抽样评判、用户反馈。本项目已具备 LLM-as-judge 基础（AgentRagService CRAG 充分性评估、`ModelCallLogPurpose.JUDGE` 打点、`ChatModelProviderChain` 降级链），本方案新增线上抽样评判链路。

## 2. 指标体系

| 指标 | 业界对应 | 数据来源 | 计算公式 | 状态 |
|---|---|---|---|---|
| **answerAccuracy 答案准确率** | RAGAS answer correctness 的线上近似 | `rag_answer_eval`（LLM-as-judge） | Σ(faithfulness=1 ∧ relevance=1) / Σ(两维均≠null) | 本方案新增 |
| **faithfulnessRate 忠实度** | RAGAS faithfulness | `rag_answer_eval` | Σ(faithfulness=1) / Σ(faithfulness≠null) | 本方案新增 |
| **relevanceRate 相关性** | RAGAS answer relevancy | `rag_answer_eval` | Σ(relevance=1) / Σ(relevance≠null) | 本方案新增 |
| usefulnessRate 有用率 | 用户反馈信号 | `kb_feedback` | USEFUL / (USEFUL+USELESS) | 已有，不变 |
| recallRate | —（实为**引用率**：进入生成上下文的来源均同时记 RETRIEVED+CITED） | `metric_event` | CITED / RETRIEVED | 已有，不变 |
| citationCorrectRate / retrievalHitRate 等 | 离线黄金集评测（RAGAS context recall 等） | eval 子系统（`EvalRunner` 七项指标） | 见离线评测报告 | 已有，不重复建设 |

### 2.1 数据链路

```
对话回答完成（经输出安全网关，答案已脱敏）
  └─ AnswerFinalizer.onAnswer → RagEvalSampleRecorder.record   [wikiagent.answer-eval.enabled，默认 true]
       ├─ 采样率 sample-rate（默认 1.0）
       ├─ SHA-256(answer) 防重（同会话同答案只采一条）
       └─ 落库 rag_answer_eval，status=PENDING
每小时第 20 分钟（judge.cron 可配）或 POST /api/metrics/answer-eval/judge 手动触发
  └─ RagAnswerJudgeService 拉取 PENDING（限 batch-size，默认 50）
       ├─ source_doc_ids → 回查父块内容拼参考资料（字符预算 6000）
       ├─ LLM-as-judge（chain 优先，purpose=JUDGE 打点，模型可配）
       └─ 回填 faithfulness / relevance / verdict_reason / judge_model
每日 02:00（metrics.aggregation-cron）聚合三率入看板快照 + Micrometer Gauge
```

### 2.2 判定语义（judge prompt 契约）

- **faithfulness 忠实度**：回答中的事实性论断是否都能被引用的参考资料支撑。含无法支撑的关键事实 → false；回答只有通用表述/澄清、或该次回答未引用知识库文档 → **null（无法评判）**。
- **relevance 相关性**：回答是否切题回应用户问题。答非所问、回避或拒绝回答 → false。
- JSON 三态：`true` / `false` / `null`；解析失败或 LLM 空响应 → 样本保持 PENDING（重试至 max-attempts 后置 FAILED），**绝不记为"评判不通过"**。

### 2.3 分母口径（诚实三原则）

1. 每个率**独立分母**：faithfulnessRate 的分母是 faithfulness≠null 的样本数；accuracyRate 的分母是两维均≠null 的样本数。不允许"至少一维可判即计入"的混口径。
2. 分母为 0 → 率返回 `null` → 看板显示 `-` / "暂无数据"，**不显示 0%**。
3. PENDING / FAILED 样本数在看板如实展示（已评 N / 待评 M），不隐藏积压。

## 3. 诚实声明（局限）

1. **judge 判定是模型近似，不是真值**：LLM 评判存在误判可能，`verdict_reason` 全量保留供人工抽查复核。
2. **覆盖范围**：当前只有 agent-rag 与 legacy-rag 两条路径会发 SSE `sources` 事件；PERO multi-agent、v1-v2 Orchestrator、闲聊 direct、联网兜底路径无引用来源，样本的 faithfulness=null（只评 relevance）。这是事实而非缺陷，看板口径如实呈现。
3. **上下文回查偏差**：评判时的参考资料是按 docId 回查的**当前**文档内容；知识库事后更新会导致评判基准与回答当时略有偏差，依赖 verdict_reason 人工复核，不另建版本快照（避免过度设计）。
4. **文本为脱敏后内容**：question/answer 均为安全网关脱敏后的文本，与用户实际所见一致。
