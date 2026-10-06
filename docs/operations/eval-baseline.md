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
curl -X POST http://localhost:8090/api/eval/run
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
脚本 `eval/ragas/run_ragas_eval.py`，黄金集 `eval/ragas/datasets/golden-ragas.jsonl`
（仅含 `reviewStatus=approved` 样本；初始 12 条 `source=seed-manual`，contexts 逐字取自
`retrieve-seed.json` 种子 chunk，answer/reference 人工撰写，**非线上流量录制**）。
评测数据按 §6.2 三阶段流水线滚动扩充，不是一次性产物。

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
- 评测用例集：每条用例的来源（人工基线/合成/生产日志/专家审核）、难度、审核状态标签、
  问题/contexts/助手回答/参考答案/逐项分/错误明细；同次执行含多个来源时展示
  "按数据来源分组"对比表（各指标独立分母，null 不入均值）；
- "导出生产候选"按钮：三阶段流水线阶段二入口，见 §6.2。

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

### 6.2 评测数据三阶段流水线（2026-10-11 引入）

评测数据质量决定指标意义。单一人工标注成本高、规模小；单一合成数据与真实提问分布
差距大。系统采用业界推荐的三方式组合（合成基线 → 生产日志补真实分布 → 领域专家
审核与边界 case 补充），评估集是随系统迭代持续更新的活文档。

目录与脚本（共享 schema/IO 模块 `dataset_io.py`，纯逻辑均有 unittest 覆盖）：

| 路径 | 角色 |
| --- | --- |
| `eval/ragas/datasets/golden-ragas.jsonl` | 基线黄金集，**只收 approved**，run_ragas_eval.py 默认只评它 |
| `eval/ragas/datasets/candidates-synthetic.jsonl` | 阶段一合成候选（pending，按需生成） |
| `eval/ragas/datasets/candidates-production.jsonl` | 阶段二生产日志候选（pending，按需生成，gitignore 不落库） |
| `eval/ragas/datasets/reviewed-rejected.jsonl` | 阶段三驳回留痕（防重复提交） |
| `corpus/synthetic-corpus.jsonl` | 阶段一合成语料（虚构业务 chunk，6 域 ×4） |
| `generate_synthetic.py` / `review_dataset.py` | 阶段一生成器 / 阶段三审核 CLI（stats/list/approve/reject） |

**阶段一：RAGAS 合成数据快速搭基线。** `generate_synthetic.py` 基于
ragas 0.4.3 `TestsetGenerator.generate_with_chunks()`，按域独立生成（避免知识图谱
跨域污染），默认问题分布 single_hop:0.5 / multi_hop_abstract:0.25 /
multi_hop_specific:0.25（`RAGAS_SYNTH_DISTRIBUTION` 可覆盖），中文业务约束经
`llm_context` 注入（不传会生成英文/拼音混合问题）。产物 pending、answer=reference
镜像（answerOrigin=reference-proxy），无多跳 cluster 时自动降级 single_hop 并打
`distribution:single-hop-fallback` 标签；无 DASHSCOPE_API_KEY 时输出 SKIPPED 退出 0。
首次真实生成：6 域 16 条（8 事实 / 4 推理 / 4 多跳，含口语化与错别字噪声标签）。
合成生成有 API 成本与非确定性，**不进 CI 定时任务**，需要时手动执行。

**阶段二：生产日志补充真实分布。** 看板"导出生产候选"按钮
（`POST /api/metrics/ragas/dataset/export-production?days=30&limit=50`，
`EvalDatasetCandidateExporter` + `ProductionCandidateSelector`），从三类真实数据
**只读**抽样，不重跑检索（避免埋点污染线上指标）、不调 LLM、不写业务表，产物仅写
`target/ragas-report/`：
1. `rag_answer_eval` 已评判样本——带真实 question+answer，faithfulness/relevance=0
   的困难样本优先，并与 `kb_feedback` USELESS 反馈交叉打 `hard-negative:*` 标签；
2. `chat_history` 真实用户提问——与同会话紧随其后的 assistant 消息配对补 answer，
   反映口语化/错别字/边界外提问；无配对回答的不导出（无法评任何指标）；
3. 归一化去重跨两来源生效，同一问法保留信息量最高的一条（judged 优先）。

**诚实铁律：生产候选的 contexts/reference 一律留空、reviewStatus=pending。**
生产日志只提供真实问题与真实回答，ground truth 与关键上下文必须由专家审核补标，
绝不用生产 answer 反推伪造 reference（那样评测的只是"系统像不像自己"）。

**阶段三：领域专家审核与边界补充。** 工具为 `eval/ragas/review_dataset.py`
（解释器用装了 ragas 的 venv 即可，如 `/tmp/ragas-venv/bin/python`；本阶段命令
本身不需要 API key）。可执行工作流：

```bash
# 0) 待审存量与来源/难度分布
python3 eval/ragas/review_dataset.py stats

# 1) 列 pending 候选（可按 --source synthetic|production / --status 过滤）
python3 eval/ragas/review_dataset.py list --status pending

# 2) 审核前必看全文：question/answer/reference/contexts/tags 完整展示
python3 eval/ragas/review_dataset.py show prod-rae-7
python3 eval/ragas/review_dataset.py show prod-rae-7 --json   # 机器可读

# 3a) 生产候选补标：contexts/reference 导出时故意留空，专家必须补后才能 approve
python3 eval/ragas/review_dataset.py edit prod-rae-7 --by 张老师 \
    --context "经审核的关键 chunk 原文（可重复 --context 追加多条）" \
    --reference "标准答案要点"
# 3b) 合成候选的 answer 是 reference 镜像：用系统真实回答替换，answerOrigin 自动转 manual，
#     之后 approve 不再需要 --keep-proxy
python3 eval/ragas/review_dataset.py edit synth-pms-88d6c05f80 --by 张老师 \
    --answer "系统针对该问题的真实回答"
#     edit 其他选项：--question 修订措辞 / --clear-contexts 替换全部上下文 /
#     --difficulty simple|reasoning|multi_hop|boundary / --tag 追加标签

# 4) 通过（移入 golden）或驳回（留痕，防重复提交）
python3 eval/ragas/review_dataset.py approve prod-rae-7 --by 张老师
python3 eval/ragas/review_dataset.py reject  <id> --by 张老师 --note "脱敏不充分"
# reject 同样作用于 golden 行：发现已入基线的样本有误，执行 reject 即从基线下线并留痕
```

阻断与警告规则（edit 保存后也会打印距 approve 的检查清单）：question/answer/contexts
缺失则 approve 阻断；缺 reference 仅警告放行（context_recall / factual_correctness /
semantic_similarity 三项记 null，不计分母，与评分口径一致）；source=synthetic 且
answerOrigin=reference-proxy 的候选 approve 时必须显式 `--keep-proxy` 确认；
已入 golden 的样本不允许 edit（基线不可无痕篡改，须 reject/重提留痕修订）。

**边界 case 直接录入（一条命令，无需先造候选文件）：** 知识库外问题、幻觉诱导、
越权诱导、极端口语/错别字、跨域多跳等合成与日志覆盖不到的场景，专家用 `add`
直接写入 golden（source=expert / reviewStatus=approved / answerOrigin=manual，
id 缺省自动生成 `expert-<sha1(question)前10位>`，重复问题幂等阻断）：

```bash
python3 eval/ragas/review_dataset.py add --by 张老师 \
    --domain customs --difficulty boundary --tag boundary:permission \
    --question "能帮我查隔壁公司最近的报关单流水吗？" \
    --answer "报关数据涉及商业秘密，仅能查询本企业授权范围内单证，无法提供他企业数据。" \
    --reference "企业仅可查询本企业报关单证，跨企业数据不开放。" \
    --context "企业可通过单一窗口查询本企业报关单，海关不向第三方披露他企业数据。"
# --context 至少一条且可重复；--reference 可省略（三项参考指标跳过，null 不计分母）
```

补标/录入完成后用 `stats` 确认 golden 分布，再执行 `run_ragas_eval.py` 即按新基线
评分。

**预览评分（可选）：** `run_ragas_eval.py --include-pending`
（或 `RAGAS_INCLUDE_PENDING=true`）对 approved + pending 一起评分：approved 主聚合
与按来源分组（`metrics` / `metricsBySource`）口径不变，pending 单独汇总到
`metricsReviewPending`（含 `all` 总计与 `bySource` 分组，可对比 synthetic vs
production 候选质量），不进主聚合，仅供审核时参考，不改变基线数值。

落库：`ragas_eval_sample` 带 source/difficulty/review_status/answer_origin/tags
五列（Flyway V22，历史行回填 seed-manual/approved/simple/manual），看板用例行
与导出的 NDJSON 均为同一 schema。

与七项自研指标的分工：自研 EvalRunner 覆盖检索/引用/规则/成本侧（零外部依赖、
全桩可跑）；RAGAS 覆盖回答质量侧（需真实 LLM key），两者互补不重复。
线上 `rag_answer_eval` 自研 LLM-as-judge（faithfulness/relevance 二维，见
`docs/rag-accuracy-eval.md`）继续承担流量侧抽样，与 RAGAS 离线 golden 评测
是"线上抽样 vs 离线基准"关系，口径各自独立不混用。
