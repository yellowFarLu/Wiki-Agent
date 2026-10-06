# 文档解析与入库

> 核心代码：[IngestionService.java](../src/main/java/com/wikiagent/service/ingest/IngestionService.java)
> 富解析：[RichDocumentParser.java](../src/main/java/com/wikiagent/application/parse/RichDocumentParser.java)
> 任务框架：见 [task-framework.md](task-framework.md)

## 1. 支持介质

由 [ParseInputValidator.java](../src/main/java/com/wikiagent/application/parse/ParseInputValidator.java) 的 `DocKind` 分流：

| DocKind | 格式 | 解析方式 |
|---|---|---|
| TEXT | txt / md / markdown | 直读 UTF-8 |
| OFFICE_OOXML | docx / xlsx | PDFBox 3.0.8 / POI 5.5.1 本地解析（[DocumentParser.java](../src/main/java/com/wikiagent/service/ingest/DocumentParser.java)） |
| OFFICE_LEGACY | doc / xls 等 | Apache Tika 3.3.2（[TikaTextExtractor.java](../src/main/java/com/wikiagent/infrastructure/parse/TikaTextExtractor.java)） |
| PDF | pdf | PDFBox 逐页抽取文本层；文本层少于扫描阈值（默认 20 字符）的页面以 144dpi 栅格化后走 OCR；文本层疑似 cmap 乱码时 OCR 双跑差异比对 |
| IMAGE | jpg / jpeg / png 等 | 多模态视觉模型 OCR（qwen-vl-plus） |
| AUDIO | wav / m4a / aac 等 | ASR 转写（paraformer-v2） |

异常处理（不静默吞错）：

- 加密 PDF 且无可用内容 → 抛 `EncryptedDocumentException`，handler 建 **DECRYPT 人工任务**（等待密码后继续）；
- 损坏文件 → `IllegalStateException`，归类 `PARSE_FAILED`，**致命不重试**；
- 图片/录音/全扫描件在供应商未配置或熔断时 → `aiSkipped=true`，文档终态 **AI_SKIPPED**，不产出空 chunk。

云端富解析能力（OCR / ASR / 版面 / 表格）通过 [DocumentAiGateway](../src/main/java/com/wikiagent/application/parse/DocumentAiGateway.java) 以 Capability 探测方式调用：默认配置：OCR 超时 30s、ASR 120s、版面 30s、表格 60s，重试 3 次，熔断 60s（连续 3 次失败打开）。

## 2. 入库六步流水线

[IngestionService.java](../src/main/java/com/wikiagent/service/ingest/IngestionService.java) 在任务步骤内逐步推进，每步更新文档状态并做终态校验，任何步失败置 FAILED 并写 error：

```text
PARSING ──► CLEANING ──► CHUNKING ──► EMBEDDING ──► INDEXING ──► READY
（另有 EXTRACTING 结构化字段抽取可选态）
```

| 步骤 | 状态 | 实现 | 关键规则 |
|---|---|---|---|
| 1. 解析 | PARSING | RichDocumentParser | 见上；解析后校验非空 |
| 2. 清洗 | CLEANING | [TextCleaner.java](../src/main/java/com/wikiagent/service/ingest/TextCleaner.java) | 去控制字符、合并空白、去页眉页脚噪声 |
| 3. 分块 | CHUNKING | [ChunkSplitter.java](../src/main/java/com/wikiagent/service/ingest/ChunkSplitter.java) | **父子块两级结构**（small-to-big）：父块默认 **1000 token**（重叠 150），子块 **350 token**（重叠 60）；按 Markdown 标题→代码围栏→段落→行→中英文句读→空格递归回退切分；口径与选型依据见 [§2.1](#21-切分算法与尺寸选型)，与 Milvus 原生能力的对比见 [§2.2](#22-为什么切分放在应用侧不用-milvus-原生能力) |
| 4. 向量化 | EMBEDDING | DashScope embedding | text-embedding-v4，1024 维；批量 10；Embedding 缓存默认开启（TTL 86400s），命中跳过重复计费 |
| 5. 索引 | INDEXING | [MilvusStoreService.java](../src/main/java/com/wikiagent/service/store/MilvusStoreService.java) | 子块写入 collection `wikiagent_chunks`（向量 + BM25 字段 + docId/父块 id 等标量）；[ContentHasher.java](../src/main/java/com/wikiagent/service/ingest/ContentHasher.java) 为子块算 content_hash（V19） |
| 6. 完成 | READY | — | parentCount/childCount 落库；触发可选后置：GraphRAG 抽取、结构化字段抽取 |

结构化信息（页号等）保留在子块元数据（按文本匹配解析页），答案引用可回溯到具体页。

### 2.1 切分算法与尺寸选型

**算法（对标 LangChain `RecursiveCharacterTextSplitter` 的递归字符切分）**：

1. 按分隔符优先级逐级回退，分隔符在文本中出现才生效，不存在则降级：
   `Markdown 标题（\n# …\n###### ）→ 代码围栏（\n```）→ 段落（\n\n）→ 行（\n）→ 中文句读（。！？；）→ 英文句点（. ! ? ;）→ 空格 → 单字符硬切兜底`；
2. 标题、围栏保留在**新片段开头**，句读、换行保留在**当前片段结尾**——块尾落在自然边界，块首不携带悬空标点；
3. 每级只在片段超长时下沉下一级，小片段在目标长度内合并、重叠窗口从前部弹出；
4. 长度计量走可插拔的 `LengthMeasurer` 接口，默认 `QwenStyleTokenEstimator`：CJK（含中文标点、假名、全角字符）1 码元 ≈ 1 token，ASCII 字母数字约 4 字符/token（向上取整、每词至少 1 token），ASCII 标点 1 token、空白 0 token，其他码元保守计 1 token。该口径是**离线估算而非精确 BPE**，刻意偏保守以避免超出 embedding 输入限制；需要精确口径可注入基于模型 tokenizer 的实现；
5. 构造参数校验：父块 ≥100 token、子块 ≥50 token，重叠必须小于块尺寸。

**默认尺寸（token 口径；配置 `wikiagent.ingest.*`，改尺寸需重建索引）**：

| 参数 | 默认值 | 角色 |
|---|---|---|
| `parent-size` / `parent-overlap` | 1000 / 150 | 父块：上下文呈现单元，检索命中后组装给 LLM |
| `child-size` / `child-overlap` | 350 / 60 | 子块：检索单元，写 Milvus（dense + BM25） |
| `embedding-batch` | 10 | embedding 请求批大小 |

**尺寸选型：数字不是拍脑袋，而是"业界主流默认锚点 + 学术实证区间 + 本项目约束"三者取交集。** 父子两级的动机是：**子块**求向量语义聚焦、负责召回；**父块**保留完整上下文，子块命中后回查父块组装给 LLM（small-to-big / parent-document 模式）。

| 参数 | 取值 | 依据 |
|---|---|---|
| 子块 `child-size` | **350 token**（重叠 60，约 17%） | ① "小文档向量语义更聚焦、检索更准，再通过父块补上下文"是 [LangChain ParentDocumentRetriever 官方 how-to](https://python.langchain.com/docs/how_to/parent_document_retriever/) 的明确动机，其"检索更大父块"示例为 child=400 / parent=2000（字符口径）；② Bhat et al. 2025 多数据集实证（[arXiv:2505.21700](https://arxiv.org/abs/2505.21700)，*Rethinking Chunk Size For Long-Document Retrieval*）：事实型短答案数据 **64–128 token** 最优，需要跨句/跨段上下文的数据 **512–1024 token** 更优，且最优尺寸随 embedding 模型不同而变化，**不存在通用最优值**。企业 wiki 以制度/手册/FAQ 长段落为主，取 256–512 检索常用区间的中段偏大值 350 |
| 父块 `parent-size` | **1000 token**（重叠 150，15%） | ① 与 LlamaIndex `SentenceSplitter` 官方默认 `chunk_size=1024` token（[API 文档](https://docs.llamaindex.ai/en/stable/api_reference/node_parsers/sentence_splitter/)）同档，对应"一个小节 / 几个段落"的自然呈现粒度；② 受检索侧约束：父块组装预算 12000 字符（约 10–12 个父块，见 `wikiagent.retrieve.parent-char-budget`），父块过大会过快耗尽 LLM 上下文预算 |
| 重叠 | 150 / 60 | 父块 15%、子块约 17%，落在业界常用的 10–20% 区间（LangChain `RecursiveCharacterTextSplitter` 默认参数 1000 / 200 即 20%，字符口径），既防止答案跨块边界被切断，又避免重复内容稀释检索 |

**追问"为什么不是 300 / 200？"**

- 200–300 token（中文约 200–300 字）只能容纳 2–4 个完整长句，而中文制度类文档单句常达 50–100 字；压到这个尺寸，子块会频繁落在句子中间，牺牲"小块语义完整"这个选小块的初衷。切分器做的是上面第 1–3 步的**递归边界回退**，目标是"尽量贴 350，但沿自然边界收口"，而不是机械等长。
- 子块越小，子块总数越多，向量存储、召回去重与父块组装成本越高，而学术实证表明 64–128 的优势仅出现在事实型短答案场景；本项目问答多需跨句上下文，350 比 200/300 更稳。
- 父块不取 200/300 是因为父块是**喂给 LLM 的呈现单元**，200–300 token 装不下一个完整小节，失去父子两级的意义；不取 2000（LangChain 示例值）是因为 12000 字符组装预算下只能放 4–5 个父块，会压缩多文档证据覆盖。

**为什么不用"语义切分"（先算 embedding 再按语义漂移切点）？** Qu, Tu & Bao 2024（[arXiv:2410.13070](https://arxiv.org/abs/2410.13070)，*Is Semantic Chunking Worth the Computational Cost?*）在文档检索、证据检索、检索式答案生成三类任务上系统比较，结论是语义切分的额外算力**没有带来一致的性能收益**。因此本项目采用零额外模型调用、可离线复现的递归结构切分。

**如何校准：** 四个参数均可在 [application.yml](../src/main/resources/application.yml) 调整（改尺寸需重建索引），并通过项目自带[离线评测](evaluation.md)（7 项自研指标 + RAGAS 黄金集）在自己语料上做网格对比（如 `child-size ∈ {256, 350, 512}`）。默认值是**有依据的起点，不是宣称的最优解**。

> 一句话版：1000/350 锚定 LangChain（400/2000）与 LlamaIndex（1024 token）的主流默认值，落在学术实证的有效区间内，再结合中文长句特性与 12000 字符父块组装预算取整；全程 token 计量、参数可配、用离线评测校准，而非拍脑袋。

切分为什么放在应用侧而不是用 Milvus 自带能力，见 [§2.2](#22-为什么切分放在应用侧不用-milvus-原生能力)。

### 2.2 为什么切分放在应用侧（不用 Milvus 原生能力）

Milvus 2.5/2.6 没有"父子切分"这个可直接替代应用侧切分的能力，逐条核实如下：

1. **没有入库切分的内置 Function**。Milvus Built-in Function（FunctionType）在 2.5 仅提供 `BM25`；2.6 新增 `TEXTEMBEDDING`、`RERANK`——它们消费字段中**已存在的完整文本**去生成向量或重排，不负责把长文档切成存储/检索单元。2.6 RERANK function 内部的 chunking 只用于 reranker 处理超长输入，不产生、也不回存 chunk。
2. **Grouping Search（`group_by_field`，2.4+）不是 small-to-big**。它只对命中向量按某标量字段分组、每组取一个代表结果，典型用途是同文档去重；它**不存储父文本，也不做"子块命中 → 返回父块上下文"的扩展**。本项目混合检索用的是服务端 RRF（`RRFRanker`），未使用 groupBy。
3. **Milvus 官方的分层/父子切分教程，切分动作同样在应用侧**（如 Docling `HierarchicalChunker`、LangChain `ParentDocumentRetriever` 集成示例），Milvus 只承担向量存储与检索。
4. **版本约束**：[docker-compose.yml](../docker-compose.yml) 锁定 `milvusdb/milvus:v2.5.17`，连 2.6 的 TEXTEMBEDDING Function 都不可用；即使升级到 2.6，也依旧没有服务端切分能力。
5. **本项目的切分与业务闭环深度耦合**，下沉重做成本高、无收益：
   - 解析层 [TableStitcher.java](../src/main/java/com/wikiagent/application/parse/TableStitcher.java) 已做跨页表格缝合，切分必须建立在缝合后的文本上；
   - 子块承载页号血缘（`IngestionService.mapToPageNo` 按文本匹配到解析页），答案引用可回溯到具体页；
   - 父子全文落 MySQL/H2、子块 content_hash 支撑幂等与 L0 精确去重、文档版本替换、生效日期裁决；
   - 零中间件降级模式（H2 + 本地任务调度、Milvus 关闭）下应用侧仍需保留完整切分与重建能力。

**决策：保持现状（应用侧切分）。** Milvus 只存子块向量与标量（`parent_id` 为普通标量字段），父块全文与父子关系在关系库；检索时由 `RetrievalService` 完成"子块命中 → 父块回查与组装（12000 字符预算）"。这与 Milvus 官方推荐的集成方式一致，且不牺牲上述业务能力。

## 3. 结构化字段抽取（子项目 B）

[FieldExtractionService.java](../src/main/java/com/wikiagent/application/extract/FieldExtractionService.java)：

```text
schema 注册（ExtractionSchemaRegistry）→ 按 schema 构造提示词
  → LLM 输出 JSON → 逐字段类型/枚举校验
  → 低置信度（默认 <0.75）字段进入修复重抽（最多 1 轮）
  → 产出 ExtractionReport（schema key/version + accepted fields + errors）
```

schema 外字段一律拒绝并登记校验错误，不做静默忽略。

## 4. 知识生效日期（V19）

上传时可指定 `effectiveDate`（[DocumentController](../src/main/java/com/wikiagent/controller/DocumentController.java)）：

- 缺省默认当天；非法日期格式返回 400；
- 与 `effective_set_by` / `effective_set_at` 一并落库，存量文档为 null；
- 作为**去重裁决与冲突守卫**的时效依据（见 [retrieval.md](retrieval.md)）。
