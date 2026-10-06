# 离线评测体系

> 相关基线：[operations/eval-baseline.md](operations/eval-baseline.md)
> RAGAS 脚本：[eval/ragas/run_ragas_eval.py](../eval/ragas/run_ragas_eval.py)

## 1. 三套评测机制

| 机制 | 触发 | 数据 | 产出 |
|---|---|---|---|
| 子项目 H 离线黄金集评测 | 每晚 23:00 UTC（CI `eval.yml`，`mvn test -Peval`），可手动触发 | 黄金样本（[EvalGoldenLoader.java](../src/main/java/com/wikiagent/application/eval/EvalGoldenLoader.java)） | `target/eval-report/EvalReport.json` |
| 线上答案裁判 | 应用内定时（[RagAnswerJudgeService.java](../src/main/java/com/wikiagent/application/knowledge/RagAnswerJudgeService.java)，表 `rag_answer_eval` V18） | 线上问答采样（默认采样率 1.0） | 裁判分写库，供指标聚合 |
| RAGAS 指标评测 | CI `eval.yml` 的 ragas-eval job / 本地手动 | `eval/ragas/datasets/golden-ragas.jsonl`（仅 reviewStatus=approved） | `target/ragas-report/ragas-report.json` |

离线黄金集评测**零真实 API key、零 Docker**：LLM/OCR/ASR/TABLE 全部用桩（[OfflineEvalAiProvider](../src/main/java/com/wikiagent/application/eval/support/OfflineEvalAiProvider.java)、[ScriptedExtractionClient](../src/main/java/com/wikiagent/application/eval/support/ScriptedExtractionClient.java)、[ThrowingEmbeddingModel](../src/main/java/com/wikiagent/application/eval/support/ThrowingEmbeddingModel.java)、[UnavailableMilvusStoreService](../src/main/java/com/wikiagent/application/eval/support/UnavailableMilvusStoreService.java)），真实 LLM 烟雾测试（@Tag("LiveLLM")）默认排除。评测套件：Parse / Retrieve / Rule / Anomaly 四组（`application/eval/suite/`），由 [EvalRunner.java](../src/main/java/com/wikiagent/application/eval/EvalRunner.java) 编排、[EvalReportStore.java](../src/main/java/com/wikiagent/application/eval/EvalReportStore.java) 落盘。

## 2. 七项自研指标（业界标准口径）

指标计算器位于 [domain/eval/metrics/](../src/main/java/com/wikiagent/domain/eval/metrics)，统一规则：**窗口内无合格样本时结果为 missing（缺失），不输出 0、不伪造标签**。

| 指标 | key | 定义 |
|---|---|---|
| 检索命中率 | retrievalHitRate | 命中相关块的检索事件数 ÷ 带相关性标签的检索事件总数（relevant 判定来自 kb_feedback 的 USEFUL/USELESS 或 golden 对照；无反馈信号的行不计入分母） |
| 引用正确率 | citationCorrectRate | 正确引用数 ÷ 引用总数；正确 = 引用可解析（docId/versionNo 定位到有效 chunk）且内容一致（pageNo/snippet 与来源 chunk 相符） |
| 字段准确率 | fieldAccuracyCalculator | 抽取正确的字段数 ÷ 已判定字段总数（与 golden 字段值逐项比对） |
| 人工编辑率 | humanEditRate | 需人工编辑的样本数 ÷ 样本总数（编辑距离/人工修改记录判定） |
| 评审错误率 | judgeErrorRate | 裁判错误数 ÷ 裁判样本总数（裁判结论与 golden/复核结论不符计错） |
| 单轮成本 | costPerTurn | 窗口内模型调用总费用 ÷ 对话轮次数（按 application.yml 定价表，元/轮） |
| 响应时间 | responseTime | 窗口内样本响应耗时的统计量（毫秒） |

## 3. 线上答案裁判（V18）

- 采样：默认 100%（`sample-rate=1.0`），[RagEvalSampleRecorder.java](../src/main/java/com/wikiagent/application/knowledge/RagEvalSampleRecorder.java) 记录样本；
- 调度：每小时第 20 分钟执行，每批 50 条，最多重试 3 次，裁判上下文预算 6000 字符；
- 裁判模型对答案忠实度、相关性等打分，结果落 `rag_answer_eval`。

## 4. RAGAS 评测（ragas 0.4.3）

6 项指标，使用业界标准 RAGAS 定义：

- faithfulness（答案陈述可由上下文支撑的比例）
- answer_relevancy（答案与问题的相关程度）
- context_precision_without_reference（无参考答案口径的上下文精确率：相关上下文排在前列的程度）
- context_recall（参考答案所需信息能被上下文覆盖的比例）
- factual_correctness（答案相对参考答案的事实正确比例）
- semantic_similarity（答案与参考答案的语义相似度）

judge 走 DashScope OpenAI 兼容端点（qwen-plus + text-embedding-v3）。无 `DASHSCOPE_API_KEY` 时脚本如实输出 **SKIPPED 并退出 0**，不编造分数；门禁为 report-only。

**已有真实基线**（2026-10-05，runId `ragas-20261005222202-65477293`，12 样本 / 240s）：

| 指标 | 值 |
|---|---|
| faithfulness | 1.0000 |
| answer_relevancy | 0.8721 |
| context_precision | 1.0000 |
| context_recall | 1.0000 |
| factual_correctness | 0.9583 |
| semantic_similarity | 0.9564 |

近满分为小样本 sanity 结果，登记与说明见 [eval-baseline.md §6](operations/eval-baseline.md)，不代表生产水平。样本表 `ragas_eval_run` / `ragas_eval_sample`（V21；V22 增 source / difficulty / review_status / answer_origin / tags）。

## 5. 评测数据集流水线

- [ProductionCandidateSelector.java](../src/main/java/com/wikiagent/application/knowledge/ProductionCandidateSelector.java)：从真实问答中挑选评测候选；
- [EvalDatasetCandidateExporter.java](../src/main/java/com/wikiagent/application/knowledge/EvalDatasetCandidateExporter.java)：导出候选供人工审核；
- 合成/生产候选/审核 CLI 的 Python 实现位于 `eval/ragas/`，配套单测 `eval/ragas/tests`（无 API key 可跑）。
