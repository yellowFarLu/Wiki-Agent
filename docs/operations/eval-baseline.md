# 离线评测基线（子项目 H）

> 对应规格 `docs/superpowers/specs/2026-10-02-bj-program-design.md` §H。
> 离线评测在零真实 API key、零 Docker 条件下运行：检索走本地关键词降级，
> LLM/OCR/ASR/TABLE 全部使用录制桩或确定性引擎，真实 LLM 烟雾测试
> （`@Tag("LiveLLM")`）默认排除。

## 1. 如何运行

```bash
# 本地/CI：全量单测 + 评测套件（不跑其它 *IT，不需要 Docker）
mvn -B test -Peval

# 真实 LLM 烟雾测试（仅夜间/发布前人工，需先 export DASHSCOPE_API_KEY）
mvn test -Peval -Dtest=LiveLlmSmokeTest -Dsurefire.excludedGroups=

# 或启动应用后调用接口
curl -X POST http://localhost:8080/api/eval/run
```

运行后产出 `target/eval-report/EvalReport.json`（另留带时间戳的历史副本），
查询接口：`GET /api/eval/report/latest`。CI 夜间工作流
`.github/workflows/eval.yml`（每天 23:00 UTC）自动上传该文件为 artifact。

## 2. 黄金样本规模

| 套件 | 文件 | 条数 | 有效条数 |
| --- | --- | --- | --- |
| PARSE | `src/test/resources/eval/parse-golden.json` | 8 | 7（1 条真实 TIFF 固件缺失，按 skip 计） |
| RETRIEVE | `retrieve-golden.json` + `seed/retrieve-seed.json` | 6 | 6 |
| RULE | `rule-golden.json` + `stubs/rules/*.json` | 7 | 7 |
| ANOMALY | `anomaly-golden.json` | 6 | 6 |
| **合计** | | **27** | **26** |

## 3. 七项指标定义、数据源与计算窗口

计算窗口：`EvalWindow.since(运行开始时刻 - 2 秒)`，闭区间，覆盖本次播种与
套件执行期间产生的全部数据行；窗口外的历史数据不参与计算。数据通过
`JpaEvalDataSource` 从各仓储只读投影，评测不改业务写路径。

| 指标键 | 定义 | 数据源 |
| --- | --- | --- |
| `fieldAccuracyRate` | `valid=true` 字段记录数 / 全部字段抽取记录数 | 字段抽取结果表（种子 5 行：4 有效 / 1 无效） |
| `retrievalHitRate` | 带相关性标签且命中的 RETRIEVED 事件 / 全部带标签事件（relevant 为 null 不进分母） | metric_event RETRIEVED + kb_feedback（仅采纳 `eval-doc` 前缀种子反馈，避免共享库历史反馈污染） |
| `citationCorrectRate` | 引用 `resolvable && consistent` 的比例。resolvable=目标 chunk 存在且版本号匹配；consistent=pageNo 相等且 snippet 文本包含于 chunk 内容 | RETRIEVE 套件运行期内存收集的 CitationSample |
| `judgeErrorRates`（复合） | `factErrorRate` = 事实错误案数 / 评判案数；`structureErrorRate` 同口径（结构错误） | `stubs/judge/*.json` 4 条录制评判（fact-error/structure-error/both-errors/clean） |
| `humanEditRate` | `EDITED / (APPROVED + REJECTED + EDITED)`；OPEN 未处置案件不计 | 人工复核案件表（种子 APPROVED/REJECTED/EDITED×2/OPEN） |
| `responseTimeP50P95`（复合） | `p50`/`p95` 延迟毫秒（最近秩法 rank=ceil(p/100×n)）+ `sampleCount`；仅 status=OK 且 latencyMs 非空 | model_call_log（种子 50/100/200/300/400ms） |
| `costPerTurn` | 全 purpose 成本估算之和 / CHAT 调用行数；CHAT 行数为 0 时记 missing | model_call_log（种子 CHAT 4 行合计 0.105，RERANK 0.005 计入分子） |

指标无足够数据时取值 `missing`（value=null + note 说明），报告的
`baseline.status` 在首次 CI 夜间运行前固定为
`PENDING_FIRST_CI_NIGHTLY_RUN`。

## 4. 基线数值

**待首次 CI 夜间运行填充，禁止编造数值。**

首次夜间运行成功后，以该次 artifact 中七项指标为基线，按下表登记，并注明
runId / 日期 / git 提交：

| 指标 | 首次基线 | 阈值（环比告警） | 登记日期 / runId |
| --- | --- | --- | --- |
| fieldAccuracyRate | 待填充 | 待首次基线后约定 | — |
| retrievalHitRate | 待填充 | 待约定 | — |
| citationCorrectRate | 待填充 | 待约定 | — |
| judgeErrorRates.factErrorRate | 待填充 | 待约定 | — |
| judgeErrorRates.structureErrorRate | 待填充 | 待约定 | — |
| humanEditRate | 待填充 | 待约定（上升为劣化） | — |
| responseTimeP50P95.p50 / p95 | 待填充 | 待约定（上升为劣化） | — |
| costPerTurn | 待填充 | 待约定（上升为劣化） | — |

## 5. 失败分类 taxonomy

样本失败与夜间流水线红灯统一归入以下六类，报告中的 `error` 字段与人工
复盘均沿用该分类命名：

1. **parse**：解析类（文本层抽取、表格跨页拼接、页数、加密/损坏/超大等异常态判定）失败。
2. **retrieve**：黄金 chunk 未召回，或 forbidden chunk 被召回；检索命中率劣化。
3. **citation**：引用不可解析（chunk/版本失配）或不一致（页码、snippet 失配）。
4. **judge**：事实/结构错误录制评判与预期不符（校验器/提示词回归）。
5. **rule**：确定性规则 DSL 执行结果与黄金期望不符（规则引擎回归）。
6. **infra**：评测基础设施问题（种子落库、上下文启动、文件读写、报告产出），非业务质量退化。

## 6. RAGAS 官方指标族（2026-10-06 引入）

七项自研指标之外，系统引入业界标准 RAGAS 框架（ragas==0.4.3，Apache 2.0）做回答质量评测，
脚本 `eval/ragas/run_ragas_eval.py`，黄金集 `eval/ragas/golden-ragas.jsonl`（12 条，
provenance 见脚本头部：contexts 逐字取自 `retrieve-seed.json` 种子 chunk，
answer/reference 人工撰写，**非线上流量录制**）。

**两种执行方式（同一份脚本，结果口径一致）：**

1. **知识看板内触发（JVM 编排 Python，主路径）**："知识治理 → 知识看板"页面
   "执行 RAGAS 评测"按钮 → JVM 预检（Python 可执行、已装 ragas、脚本可读、
   DASHSCOPE_API_KEY 非空）→ 后台 ProcessBuilder 执行 → 读取报告 JSON → 事务落库
   → 页面轮询展示。单飞保护（已有执行中返回 409）；应用重启遗留的 RUNNING
   在启动时如实标记为 ERROR（进程不可能跨重启继续）。
2. **CI 夜间独立 job**：`.github/workflows/eval.yml` 的 `ragas-eval` job 独立跑
   脚本并上传 artifact（CI 无法访问应用数据库，结果不进看板历史，仅作 CI 记录）。

主机前置条件：应用主机需 python3 环境并安装 `eval/ragas/requirements.txt`，
解释器/脚本路径可用 `wikiagent.ragas.python` / `wikiagent.ragas.script-path` 配置，
总超时 `wikiagent.ragas.timeout-sec`（默认 900s），功能开关
`wikiagent.ragas.enabled`（默认 true）。

**看板展示内容（历史全量保留）：**
- 历史执行表：runId、状态（成功/失败/执行中）、开始时间、耗时、用例数、四项指标；
- 选中执行详情：忠实度（忠诚度）、答案相关性、上下文精确率、上下文召回率四张指标卡
  （另含事实正确性、语义相似度，在评测用例展开行）；
- 评测用例集：每条用例的问题/contexts/助手回答/参考答案/逐项分/错误明细。

judge 走 DashScope OpenAI 兼容端点（`qwen-plus` + `text-embedding-v3`，与线上
同供应商，可用 `RAGAS_JUDGE_MODEL` / `RAGAS_EMBEDDING_MODEL` 覆盖）；
**CI secret 未配置时脚本如实输出 SKIPPED 退出 0，绝不编造分数**。

| RAGAS 指标 | 口径（与 ragas 官方定义一致） | 需要 reference |
| --- | --- | --- |
| faithfulness | 回答论断被 contexts 支撑的比例 | 否 |
| answer_relevancy | 由回答反推问题与原问题的余弦相似度 | 否 |
| context_precision_without_reference | 上下文精确率无参考变体（response 替代 reference） | 否 |
| context_recall | reference 论断被 contexts 支撑的比例（**召回率唯一诚实口径**） | 是 |
| factual_correctness | response vs reference 论断重叠 F1 | 是 |
| semantic_similarity | response vs reference 向量余弦 | 是 |

聚合口径：每项指标独立分母，单（样本，指标）失败记 null + error 不记 0；
某指标全体缺失 → `value=null`（看板显示"-"，不显示 0%）；全部指标失败 →
报告 `status=ERROR`，job 红灯（归入 infra 类）。

基线数值（report-only，**禁止编造**）：

> 首次基线性质声明：下表是看板 JVM 编排跑出的首个真实结果。黄金集 answer 系人工
> 按 contexts 撰写的忠实答案，近满分是**管线正确性 sanity 结果**（证明接线与评分
> 链路通畅），不代表真实问答管道质量；待黄金集逐步替换为真实流量样本后，数值才
> 具有质量基线意义。阈值仍待样本真实化后按 §4 惯例约定。

| 指标 | 首次基线 | 阈值（环比告警） | 登记日期 / runId |
| --- | --- | --- | --- |
| faithfulness | 1.0000 | 待真实样本基线后约定 | 2026-10-05 / ragas-20261005222202-65477293 |
| answer_relevancy | 0.8721 | 待约定 | 2026-10-05 / 同上 |
| context_precision_without_reference | 1.0000 | 待约定 | 2026-10-05 / 同上 |
| context_recall | 1.0000 | 待约定 | 2026-10-05 / 同上 |
| factual_correctness | 0.9583 | 待约定 | 2026-10-05 / 同上 |
| semantic_similarity | 0.9564 | 待约定 | 2026-10-05 / 同上 |

（执行耗时 240s，12 样本，零评分错误；judge qwen-plus，embedding text-embedding-v3。）

与七项自研指标的分工：自研 EvalRunner 覆盖检索/引用/规则/成本侧（零外部依赖、
全桩可跑）；RAGAS 覆盖回答质量侧（需真实 LLM key），两者互补不重复。
线上 `rag_answer_eval` 自研 LLM-as-judge（faithfulness/relevance 二维，见
`docs/rag-accuracy-eval.md`）继续承担流量侧抽样，与 RAGAS 离线 golden 评测
是"线上抽样 vs 离线基准"关系，口径各自独立不混用。
