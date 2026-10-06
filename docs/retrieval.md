# 混合检索与 GraphRAG

> 核心代码：[RetrievalService.java](../src/main/java/com/wikiagent/service/retrieve/RetrievalService.java)
> 相关：[governance.md](governance.md)（去重/冲突治理的离线链路）

## 1. 检索主流程

```text
原始 query
  │
  ├─ 查询改写 QueryRewriteService（多查询扩展，生成多个同义子查询并行召回）
  ├─ Embedding：text-embedding-v4（1024 维）
  │
  ├─ Milvus 双路召回（collection: wikiagent_chunks）
  │    Dense 向量路 + BM25 稀疏路（k1=1.2，b=0.75）
  │    服务端 RRF 融合：rrf-k=60；子查询 topK=20
  │    标量过滤：身份 / 业务域标签（RetrievalFilter + RetrievalSecurityContext）
  │
  ├─ GraphRAG 扩展（wikiagent.graph.enabled=true，默认开启）
  │    实体名匹配 → 1-hop 邻居扩展（max-neighbors=10），按 chunk 反查并入候选
  │
  ├─ gte-rerank 精排（final topK=10）
  │    灰度门控（GrayReleaseService）：rerank 供应商失败/未放量时保持原序，不阻断
  │
  └─ 父块组装
       命中子块替换为父块，按命中顺序去重，字符预算 12000 内拼接
       Accumulator 支持 Agentic 多轮检索：跨轮按父块去重（保留最高分/首次顺序）
```

BM25 与 RRF 均在 Milvus 服务端完成（Milvus 2.5 原生混合检索能力），不在应用层手工融合。

## 2. 引用来源六字段

每条来源（`RetrievalService.Source`）回填：`docId / versionNo / pageNo / snippet（代表子块前 200 字符）/ artifactId / score`，外加 filename 供前端兼容。

- 代表子块：父块下得分最高者，平局取 childIndex 最小者；
- `artifactId` 由 `knowledge_metadata`（V11 血缘元数据）按代表子块回填，未打标的公共知识为 null。

## 3. Milvus 故障降级

- 探测失败后进入 **60s 冷却**（`FALLBACK_COOLDOWN_MS`），冷却期内不再尝试连接，避免每次请求付出约 10s 超时；
- 降级路径：中文 **bigram 关键词本地检索**（每查询最多 12 个词项，`FALLBACK_MAX_TERMS`）直接查 MySQL 子块表；
- 冷却结束自动探测恢复。

## 4. 去重与冲突守卫（检索期）

在候选集上串联两道守卫（均为默认开启）：

| 守卫 | 默认策略 | 阈值 | 行为 |
|---|---|---|---|
| 近重复去重 | near-jaccard | 0.9 | 近重复子块只保留一份进入上下文，防止同一内容重复挤占预算 |
| 冲突守卫 | coarse-cosine 粗筛 → LLM 精判 | 粗筛 0.85；判定 0.8 | 检测同主题互相矛盾的内容；冲突时 **KEPT_BOTH 双保留**并打 conflict 标注，不静默二选一 |

`RetrievalResult.conflict=true` 时，"存在互相矛盾的两说"本身作为可回答事实透传给生成层；CRAG 充分性评估不得因此判证据不足而触发联网兜底。

冲突判定的时效依据是文档**生效日期**（V19 `effectiveDate`）：同内容新版本与旧版本冲突时，生效日期参与裁决，并给出 resolution_hint；相关字段见 V19 迁移（conflict_resolution 增 source / resolution_hint）。

相关组件：

- [ConflictCandidateDetector.java](../src/main/java/com/wikiagent/service/retrieve/ConflictCandidateDetector.java)
- [RetrievalConflictGuard.java](../src/main/java/com/wikiagent/service/retrieve/RetrievalConflictGuard.java)
- [ConflictLlmJudge.java](../src/main/java/com/wikiagent/service/retrieve/ConflictLlmJudge.java)

## 5. GraphRAG 离线抽取

[GraphExtractionService.java](../src/main/java/com/wikiagent/application/graph/GraphExtractionService.java) 在文档入库后异步执行：

- LLM 从 chunk（截断上限 1500 字符）抽取，实体类型 8 种：人物/组织/产品/地点/概念/事件/规则/其他；关系类型 9 种：属于/包含/依赖/影响/合作/对抗/引用/继承/其他；
- 同名同类型实体 upsert 合并 description；source+target+type 相同的关系累加 weight 合并；
- 单 chunk 抽取失败只告警不阻断入库；
- 表：`graph_entity` / `graph_relation`（V17）。
