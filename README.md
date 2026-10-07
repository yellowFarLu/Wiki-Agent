# Wiki Agent

**一个具备感知、记忆、规划、工具调用、治理与自省能力的企业级知识智能体**

*Java 21 · Spring Boot 3.5.16 · Spring AI 1.1.2 · Spring AI Alibaba 1.1.2.0 · Milvus 2.5.x · MySQL 8 / H2 · Redis/Redisson · RocketMQ · Next.js 14 · DDD 分层架构*

***

## 定位

Wiki Agent 把"企业知识库问答"从 RAG 三件套升级为一条完整的智能体工程闭环：

- **入库**：文档经六步可断点流水线（解析 → 清洗 → 父子切分 → 向量化 → 索引 → 完成），支持 PDF/Office/图片 OCR/录音 ASR，并可选 LLM 实体关系抽取构建知识图谱；
- **问答**：输入安全网关 → qwen-flash 意图路由 → PERO Agent 主循环（Plan-Execute-Reflect-Optimize + ReAct 工具调用）→ 混合检索（Dense + BM25 服务端 RRF + rerank + GraphRAG）→ 输出安全网关，SSE 流式输出；
- **任务**：重活下沉到 MySQL 状态机 + RocketMQ 投递 + Redis 协调的通用任务框架，支持租约心跳、步骤 Checkpoint、人工接管、崩溃自愈；
- **治理**：分层记忆、血缘版本、灰度发布、全链路追踪、反馈飞轮与离线评测；
- **韧性**：每个外部中间件都可降级，零外部中间件（H2 + 本地任务调度 + Redis 关闭）也能启动。

***

## 快速开始

**前置**：JDK 21 与 Maven 3.9+（本机路径约定见 [run.sh](run.sh)，脚本自动定位；系统 mvn 3.6 过旧不可用）。

```bash
cp .env.example .env      # 填入 DASHSCOPE_API_KEY 等；不配 Key 也能启动（LLM 降级 NoOp），但无真实生成
./start.sh                # 后台启动 + 健康检查轮询
# 或 ./run.sh 前台启动
```

启动后访问 \*\*<http://localhost:8090/**，首页即内置管理台（8> 个 Tab：对话 / 材料上传 / 任务中心 / 人工工作台 / 知识图谱 / 知识治理 / 可观测 / 身份设置）。

常用命令：`./stop.sh` · `./restart.sh` · `./status.sh`；日志 `logs/app.log`。更多细节见 [STARTUP.txt](STARTUP.txt)。

**前端独立开发**：

```bash
cd frontend && npm install && npm run dev    # http://localhost:3000，/api 代理到 8090
```

**Docker**（[docker-compose.yml](docker-compose.yml)）：

- 基础设施一键启动（mysql 8 / redis 7 / etcd / minio / milvus 2.5.17 / presidio ×2 / RocketMQ namesrv+broker / nginx），例如 `docker compose up -d mysql redis milvus rmqnamesrv rmqbroker`；
- frontend 服务可构建（[frontend/Dockerfile](frontend/Dockerfile) 存在，Next.js standalone，端口 3000）；
- ⚠️ **根目录 Dockerfile 尚不存在**，因此 compose 中 `wikiagent-app-1/2`（`build: .`，host 8081/8082 → 容器 8080）目前无法构建镜像；[release-deploy.yml](.github/workflows/release-deploy.yml) 的镜像构建步骤同样以 Dockerfile 存在为条件，当前跳过。

***

## 架构速览

```text
用户（Next.js 管理台）
 → 输入安全网关（黑名单 + LLM 评判 + Spotlighting + 信任边界头校验）
 → LLM 路由（qwen-flash：简单→plus / 复杂→max / 企业内容强制检索）
 → PERO Agent：Plan → ReAct Execute（≤8 轮，5 工具，写操作需审批）→ Reflect → Optimize
     └─ 混合检索：查询改写 → Milvus Dense+BM25 服务端 RRF（k=60）
                 → GraphRAG 1-hop 扩展 → gte-rerank（灰度）→ 父块组装（预算 12000）
     └─ 分层记忆：短期 Redis（≤20 轮）/ 用户画像 MySQL / 历史事件 Milvus / 交接清单 MySQL
 → 输出网关（审核 + 提示词泄露检测 + PII 脱敏；流式逐 chunk 校验）
 → 用户（SSE，答案附引用角标 + traceId）
```

完整架构（含异步任务链路、技术选型、DDD 工程结构）：[docs/architecture.md](docs/architecture.md)

***

## 关键设计决策

- **父子 chunk 为什么是子块 350 token / 父块 1000 token（为什么不是 300/200）？** 入库为 small-to-big 两级切分：子块负责向量 + BM25 召回，父块负责上下文呈现；尺寸按 token 计量、参数可配。完整选型依据（LangChain/LlamaIndex 默认值锚点、arXiv:2505.21700 与 arXiv:2410.13070 实证、12000 字符组装预算约束、离线评测校准方法）见 [docs/ingestion.md §2.1](docs/ingestion.md)。
- **为什么切分放在应用侧、不用 Milvus 自带的父子能力？** Milvus 2.5/2.6 没有入库切分 Function，grouping search 也不等于 small-to-big；且切分与表格缝合、页号血缘、去重裁决、降级重建深度耦合。调研结论与决策理由见 [docs/ingestion.md §2.2](docs/ingestion.md)。

***

## 文档地图

### 核心设计（按当前代码基线编写）

| 主题                            | 文档                                               |
| ----------------------------- | ------------------------------------------------ |
| 全链路架构、技术选型、工程结构               | [docs/architecture.md](docs/architecture.md)     |
| 文档解析与六步入库流水线                  | [docs/ingestion.md](docs/ingestion.md)           |
| 混合检索、GraphRAG、去重与冲突守卫         | [docs/retrieval.md](docs/retrieval.md)           |
| PERO 主循环、多 Agent、工具、分层记忆、成本路由 | [docs/agent.md](docs/agent.md)                   |
| 通用任务框架（状态机/租约/重试/人工接管）        | [docs/task-framework.md](docs/task-framework.md) |
| 知识治理、血缘版本、规则引擎、可观测            | [docs/governance.md](docs/governance.md)         |
| 安全体系（输入/输出网关、PII、审批）          | [docs/security.md](docs/security.md)             |
| 离线评测（7 项自研指标 + RAGAS + 真实基线）  | [docs/evaluation.md](docs/evaluation.md)         |
| REST API 速览                   | [docs/api-reference.md](docs/api-reference.md)   |
| 前端设计（技术栈 / 模块 / 核心链路 / 核心代码）  | [docs/frontend.md](docs/frontend.md)             |

### 运维手册

| 主题                             | 文档                                                                                     |
| ------------------------------ | -------------------------------------------------------------------------------------- |
| 开关全景（所有 wikiagent.\* 特性开关与默认值） | [docs/operations/feature-toggles.md](docs/operations/feature-toggles.md)               |
| 降级矩阵（中间件故障 → 影响/预案/恢复）         | [docs/operations/degradation-matrix.md](docs/operations/degradation-matrix.md)         |
| 信任边界与威胁模型                      | [docs/operations/trust-boundary.md](docs/operations/trust-boundary.md)                 |
| 评测基线登记（含 RAGAS 基线）             | [docs/operations/eval-baseline.md](docs/operations/eval-baseline.md)                   |
| 备份恢复                           | [docs/operations/backup-restore.md](docs/operations/backup-restore.md)                 |
| 手工验收清单                         | [docs/operations/final-manual-checklist.md](docs/operations/final-manual-checklist.md) |

### 历史方案与需求文档

| 文档                                                               | 说明                            |
| ---------------------------------------------------------------- | ----------------------------- |
| [docs/PRODUCTION\_AGENT\_PLAN.md](docs/PRODUCTION_AGENT_PLAN.md) | 生产级 G2G Agent 技术方案 v3（完整长篇方案） |
| [docs/技术方案.md](docs/技术方案.md)                                     | 技术方案 v1.1                     |
| [docs/部署指南.md](docs/部署指南.md)                                     | 部署指南                          |
| [docs/rag-accuracy-eval.md](docs/rag-accuracy-eval.md)           | RAG 准确率评测方案                   |
| [docs/子项目A-手工执行清单.md](docs/子项目A-手工执行清单.md)                       | 子项目 A 手工执行清单                  |
| [docs/1001-需求.txt](docs/1001-需求.txt)                             | 原始需求                          |
| [docs/superpowers/](docs/superpowers/plans)                      | 开发过程的 plans / specs 记录        |

***

## 测试与 CI

**单元测试**（2026-10-06 本地干净环境实测：H2 文件库、未加载 .env）：

```text
Tests run: 835, Failures: 0, Errors: 0, Skipped: 1   （136 个测试类，耗时 4:19）
```

本地复现：

```bash
mvn test                      # 默认 H2，无需 Docker/API Key
```

**集成测试**：`src/test` 下 24 个 `*IT` 类（83 个测试方法），基于 Testcontainers，在 CI 的 `mvn verify` 中与单测一体执行。

**CI 工作流**（[.github/workflows/](.github/workflows/ci.yml)）：

| 工作流                                                        | 内容                                                                                             |
| ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| [ci.yml](.github/workflows/ci.yml)                         | push/PR：`mvn verify`（单测 + Testcontainers IT）；前端 typecheck/lint/build + Playwright e2e（mock 后端） |
| [eval.yml](.github/workflows/eval.yml)                     | 每晚离线黄金集评测（7 指标，零 API Key 全桩）+ RAGAS 评测（无 Key 时 SKIPPED）                                        |
| [release-deploy.yml](.github/workflows/release-deploy.yml) | 发布与部署；镜像构建以 Dockerfile 存在为条件（当前跳过）                                                             |

***

## 关键事实速查

| 项                | 值                                        | 依据                                                                                                        |
| ---------------- | ---------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| 服务端口             | 8090                                     | [application.yml](src/main/resources/application.yml)                                                     |
| 数据库迁移            | Flyway **V1–V22**，共 **40 张表**            | [db/migration/](src/main/resources/db/migration)                                                          |
| 默认库（开发）          | H2 文件库 `./data/h2/kb`，MODE=MySQL         | application.yml                                                                                           |
| Chat / Embedding | qwen-plus（默认）/ text-embedding-v4（1024 维） | application.yml                                                                                           |
| 视觉 OCR / ASR     | **qwen-vl-plus** / paraformer-v2         | application.yml `parse`                                                                                   |
| 业务域 / 子域 / 身份    | **9** 域 × 6 子域 × **5** 身份（默认 business）   | DomainTag / BusinessIdentity                                                                              |
| PERO ReAct 上限    | 8 次迭代                                    | application.yml `pero`                                                                                    |
| 旧路由兼容            | **HTTP 307** 临时重定向                       | [LegacyRedirectController.java](src/main/java/com/wikiagent/interfaces/web/LegacyRedirectController.java) |

