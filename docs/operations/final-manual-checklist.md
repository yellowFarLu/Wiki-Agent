# 全项目手工执行清单（终版）与合并建议

日期：2026-10-03 ｜ 分支：`feature/task-framework`（子项目 A–J 全部完成）
适用范围：合入主干前的手工验收、部署与回滚操作。

---

## 1. 启动前检查（环境）

| 项 | 要求 | 校验命令 |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Node | 20+（前端） | `node -v` |
| Docker | 本地无 Docker 时后端默认 H2 文件库可裸跑 | `docker info`（可选） |
| 中间件 | MySQL/Redis/RocketMQ/Milvus 任一缺失均有降级（见 `docs/operations/feature-toggles.md`、降级矩阵） | `docker compose ps` |

## 2. 启动步骤

```bash
# 1) 中间件（可选，缺省走降级）
docker compose up -d mysql redis rmqnamesrv milvus

# 2) 后端（dev profile，H2 文件库零依赖）
SPRING_PROFILES_ACTIVE=dev ./run.sh   # 或 mvn spring-boot:run
curl -s localhost:8090/api/health

# 3) 前端（波4 G）
cd frontend && npm ci && npm run dev   # http://localhost:3000，/api 代理到 8090

# 4) 全栈 compose（可选）
docker compose up -d                   # nginx :80 → / 前端、/api 后端 sticky
```

## 3. 分域手工验收清单

| 域 | 步骤 | 预期 |
|---|---|---|
| 上传/入库(A/B) | 前端 材料上传 拖入 txt/md/pdf；再传一次同文件 | 首次成功并出现任务链接；二次提示「重复上传」幂等 |
| 任务中心(A) | 任务详情看步骤/事件；RUNNING 时暂停→恢复→取消；FAILED 重放 | 状态机流转正确，事件留痕 |
| 血缘版本(C) | 结构化结果页选版本；工作台 历史版本 选两版本对比 | diff 行级增删改、changed 高亮 |
| 规则/复核(D) | 复核案件 通过/驳回/编辑 三动作 | 处置成功提示 + field_version 新行 + task_event |
| RAG/对话(E) | 对话页提问带知识库命中问题 | stage 进度 → sources → delta 流式 → 引用角标可点开页码片段 |
| Agent 治理(F) | 越权身份调用受控工具 | 拒绝 + audit_log TOOL_DENIED |
| 可观测(I) | `curl localhost:8090/actuator/prometheus` | 含 `wikiagent_*` 任务积压/模型调用/熔断指标 |
| 安全(J) | 无 X-User-Id 或越权 domain 访问 | 401/429/越权零命中；审计可查 |
| 评测(H) | `mvn -B test -Peval` | EvalReport JSON 产出（无 key 可跑录制固件） |
| 前端 e2e(G) | `cd frontend && npm run test:e2e`（CI 设 PW_CHROMIUM=1） | mock 全链路 2 passed；真实后端 `E2E_REAL_BACKEND=1` 冒烟 |

## 4. CI 合并后必看

1. `verify` job：`mvn -B verify` 含 Testcontainers IT（本机无 Docker，**TaskFrameworkE2eIT 只能在 CI 验证**）。
2. `frontend` job：npm ci → typecheck/lint/build → Playwright（chromium）。
3. `Eval Nightly`：离线评测报告 artifact。
4. `Release and Deploy`：prod 部署为 manual gate。

## 5. 回滚

- 应用：docker 镜像按 tag 回滚；单体 jar 替换重启。
- 数据库：Flyway 迁移均为增量（V1–V16），回滚见 `docs/operations/` 备份回滚手册；Milvus 软删（is_active）无物理删除。
- 开关兜底：`wikiagent.task.enabled=false`、`wikiagent.pero.enabled=false` 可整体回退 v1–v6 行为。

## 6. 合并建议

- 方式：**merge commit（非 squash）**——A–J 分波 20+ 提交均有独立验收语义，保留历史便于回溯复查记录。
- 顺序：先确认 CI 两 job 绿 → 合入 → 观察首个 `TaskFrameworkE2eIT` 与 frontend e2e 结果。
- 风险提示：①前端 antd v6 为当前最新主版本，后续升级注意 deprecated API（本次已踩 Alert.message/Drawer.width/Spin.tip）；②本机 Playwright 用系统 Chrome，CI 用官方 chromium，选择器差异风险已在两侧验证；③生产首次启动前按 `docs/operations/feature-toggles.md` 逐项确认开关默认值。
