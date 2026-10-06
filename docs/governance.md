# 知识治理、血缘、规则引擎与可观测

## 1. 知识打标

[KnowledgeTaggingService.java](../src/main/java/com/wikiagent/application/knowledge/KnowledgeTaggingService.java) 为文档/块打上业务域、身份等标签（[KnowledgeTagContext](../src/main/java/com/wikiagent/application/knowledge/KnowledgeTagContext.java)），作为检索期标量过滤与多租户隔离的依据：9 业务域 × 6 子域 × 5 身份。

## 2. 血缘与版本（V10–V12）

[ProvenanceService.java](../src/main/java/com/wikiagent/application/lineage/ProvenanceService.java) 维护"文档 → 制品/字段 → chunk → 版本"全链路溯源：

| 表（V10） | 用途 |
|---|---|
| `doc_artifact` | 文档级制品（V11 起 chunk 通过 artifact_id 挂接） |
| `provenance_edge` | 血缘有向边（来源 → 派生） |
| `extracted_field` | 结构化抽取字段实例 |
| `field_version` | 字段版本（字段值变更可追溯） |
| `doc_version` | 文档版本 |

V11/V12 以 ALTER 方式补齐 chunk 级溯源与字段-chunk-版本关联。答案引用中的 `artifactId` 即来源于此。

## 3. 冲突治理（离线链路）

- [ConflictDetectionService.java](../src/main/java/com/wikiagent/application/knowledge/ConflictDetectionService.java)：入库后批量扫描同主题内容；
- [ConflictResolutionService.java](../src/main/java/com/wikiagent/application/knowledge/ConflictResolutionService.java)：结合生效日期（V19）给出裁决与 `resolution_hint`，落 `conflict_resolution`（V7，V19 增 source / resolution_hint）；
- 检索期的实时冲突守卫见 [retrieval.md](retrieval.md)，两条链路共用同一裁决口径。

## 4. 近重复治理

[dedup/](../src/main/java/com/wikiagent/application/knowledge/dedup/NearDuplicateGuard.java)：

- [MinHashSignature.java](../src/main/java/com/wikiagent/application/knowledge/dedup/MinHashSignature.java) 生成文档签名；
- [RedisLshIndex.java](../src/main/java/com/wikiagent/application/knowledge/dedup/RedisLshIndex.java) 做 LSH 桶聚（Redis 不可用时降级）；
- [KeyTokenDiffer.java](../src/main/java/com/wikiagent/application/knowledge/dedup/KeyTokenDiffer.java) 输出差异关键词供人工判断；
- [NearDuplicateGuard.java](../src/main/java/com/wikiagent/application/knowledge/dedup/NearDuplicateGuard.java) 汇总判定，配合入库 content_hash（V19）防止重复知识入库。

## 5. 规则引擎与复核（V13）

```text
DSL 规则文本 -> RuleDslParser 解析 -> RuleSetService 管理规则集
  -> RuleExecutionService 执行（rule_computation 留痕）
  -> 命中存疑/例外 -> review_case 人工复核（ReviewCaseService）
```

- [RuleDslParser.java](../src/main/java/com/wikiagent/application/rule/RuleDslParser.java)
- [RuleSetService.java](../src/main/java/com/wikiagent/application/rule/RuleSetService.java)
- [RuleExecutionService.java](../src/main/java/com/wikiagent/application/rule/RuleExecutionService.java)
- [ReviewCaseService.java](../src/main/java/com/wikiagent/application/rule/ReviewCaseService.java)

## 6. 反馈与指标闭环

- `kb_feedback`（V6）：用户对答案点赞/踩与纠正；
- [MetricsAggregationJob.java](../src/main/java/com/wikiagent/application/knowledge/MetricsAggregationJob.java)：聚合 `metric_event`（V6）/`knowledge_metric`（V5）产出命中率、满意率等；
- [StaleScore.java](../src/main/java/com/wikiagent/application/knowledge/StaleScore.java)：综合时效、反馈、命中情况计算知识陈旧度分，供治理排序。

## 7. 灰度发布

[GrayReleaseService.java](../src/main/java/com/wikiagent/application/gray/GrayReleaseService.java)：按用户/租户/百分比对新能力（如 gte-rerank 精排）放量；新链路失败自动保持旧链路结果，不影响主流程。开关全景见 [operations/feature-toggles.md](operations/feature-toggles.md)。

## 8. 可观测性

- **指标**：Actuator + Micrometer Prometheus，自定义指标经 `MetricEvent` 与各业务埋点暴露；
- **追踪**：会话级 traceId 全链路透传（[TaskTraceConveyor.java](../src/main/java/com/wikiagent/application/observability/trace/TaskTraceConveyor.java)，MDC 包装见 MdcRunnable/MdcCallable）；PERO 过程落 `agent_trace`（V2）；
- **模型调用留痕**：`prompt` / `model_call_log`（V14）记录提示词、模型、token、费用；
- **限流熔断**：Resilience4j + [CircuitBreakerRegistry.java](../src/main/java/com/wikiagent/application/observability/circuit/CircuitBreakerRegistry.java)，LLM/Milvus/解析服务各自独立熔断；
- **审计**：`audit_log`（V3）、网关审计（V8）、`trace_id` 贯通（V16）。
