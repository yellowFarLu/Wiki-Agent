# API 速览

> Base URL：`http://localhost:8090`（server.port）
> 所有接口除标注外返回 JSON；问答接口返回 SSE 事件流。

## 问答与历史

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat` | SSE 问答（`text/event-stream`）；事件：stage / sources / delta / done / error / blocked |
| GET | `/api/chat/sessions` | 会话列表（`chat_history` V20） |
| GET | `/api/chat/sessions/{sessionId}/messages` | 会话消息历史 |

[ChatController.java](../src/main/java/com/wikiagent/controller/ChatController.java) · [ChatHistoryController.java](../src/main/java/com/wikiagent/interfaces/chat/ChatHistoryController.java)

## 文档与血缘

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/documents` | multipart 上传；可选 `effectiveDate`（缺省当天，非法返回 400） |
| GET | `/api/documents` | 文档列表 |
| GET | `/api/documents/{id}` | 文档详情（含状态/父子块计数） |
| DELETE | `/api/documents/{id}` | 删除文档 |
| GET | `/api/documents/{docId}/lineage` | 文档血缘图 |
| GET | `/api/documents/{docId}/fields/{key}/lineage` | 字段级血缘 |
| GET | `/api/documents/{docId}/versions` | 版本列表 |
| GET | `/api/documents/{docId}/versions/{a}/diff/{b}` | 版本 diff |

[DocumentController.java](../src/main/java/com/wikiagent/controller/DocumentController.java) · [LineageController.java](../src/main/java/com/wikiagent/interfaces/lineage/LineageController.java)

## 任务

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/tasks` | 提交任务（biz_key 幂等）；`wikiagent.task.enabled=false` 时返回 503 |
| GET | `/api/tasks` | 任务列表 |
| GET | `/api/tasks/{taskId}` | 任务详情 |
| GET | `/api/tasks/{taskId}/steps` | 步骤 Checkpoint |
| GET | `/api/tasks/{taskId}/events` | 事件流水 |
| GET | `/api/tasks/{taskId}/human-tasks` | 关联人工任务 |
| POST | `/api/tasks/{taskId}/suspend` `/resume` `/cancel` `/replay` | 控制操作（version CAS） |
| GET | `/api/tasks/{taskId}/stream` | 任务进度 SSE |

[TaskController.java](../src/main/java/com/wikiagent/interfaces/task/TaskController.java) · [TaskSseBridgeController.java](../src/main/java/com/wikiagent/interfaces/task/TaskSseBridgeController.java)

## 冲突与反馈

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/conflicts` · `/{id}` · `/{id}/diff` | 冲突列表/详情/diff |
| GET | `/api/conflicts/resolutions` | 允许的裁决方式 |
| POST | `/api/conflicts/scan` | 触发扫描 |
| POST | `/api/conflicts/{id}/resolve` · `/{id}/ignore` | 裁决/忽略 |
| POST | `/api/feedback` | 提交反馈（点赞/踩/纠正） |
| GET | `/api/feedback/{chunkId}` | 块反馈统计 |

[ConflictController.java](../src/main/java/com/wikiagent/interfaces/knowledge/ConflictController.java) · [FeedbackController.java](../src/main/java/com/wikiagent/interfaces/feedback/FeedbackController.java)

## 图谱与规则

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/graph/stats` · `/search` · `/doc/{docId}` | 图谱统计/检索/按文档查看 |
| GET | `/api/graph/entity/{id}` · `/entity/{id}/neighbors` | 实体详情/邻居 |
| POST | `/api/rules` | 创建规则集 |
| GET | `/api/rules/{code}` · `/{code}/active` · `/{code}/diff/{a}/{b}` | 规则集/生效版本/diff |
| POST | `/api/rules/{code}/versions/{version}/publish` · `/archive` | 发布/归档 |
| POST | `/api/rules/{code}/compute` | 执行计算 |
| GET | `/api/rules/computations/{id}` · POST `/computations/{id}/replay` | 计算结果/重放 |

[GraphRagController.java](../src/main/java/com/wikiagent/interfaces/graph/GraphRagController.java) · [RuleSetController.java](../src/main/java/com/wikiagent/interfaces/rule/RuleSetController.java) · [RuleComputeController.java](../src/main/java/com/wikiagent/interfaces/rule/RuleComputeController.java)

## 指标、可观测、评测、追踪

| 前缀 | 主要端点 |
|---|---|
| `/api/metrics` | `/dashboard` · `/aggregation` · `/knowledge` · `/chunk/{chunkId}` · `/answer-eval/samples` · POST `/answer-eval/judge` · POST `/ragas/run` · `/ragas/runs` · `/ragas/runs/{runId}` · POST `/ragas/dataset/export-production` |
| `/api/observability` | `/dashboard` · `/feedback` · `/gateway` · `/knowledge` |
| `/api/eval` | GET `/report/latest` · GET `/report/{filename}` · POST `/run` |
| `/api/trace` | `/session/{sessionId}` · `/conversation/{conversationId}` · `/user/{userId}` |
| `/api/health` | 健康检查（[HealthController.java](../src/main/java/com/wikiagent/controller/HealthController.java)） |

## 旧路由兼容

旧版前端路径由 [LegacyRedirectController.java](../src/main/java/com/wikiagent/interfaces/web/LegacyRedirectController.java) 以 **HTTP 307**（临时重定向，保留方法与请求体）重定向到新路由，Next.js 侧 `next.config` 对应 `permanent:false`。
