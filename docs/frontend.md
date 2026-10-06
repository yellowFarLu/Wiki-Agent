# Wiki Agent 前端设计（面试复习）

> 本文档基于当前 `frontend/` 代码基线编写，覆盖 **技术栈 / 模块划分 / 核心链路 / 核心代码设计** 四部分，用于面试复习。

---

## 1. 技术栈

| 类别 | 选型 | 版本 | 依据 |
|---|---|---|---|
| 框架 | **Next.js**（App Router） | 14.2.35 | [package.json](../frontend/package.json) |
| 视图层 | **React** | 18 | [package.json](../frontend/package.json) |
| 语言 | **TypeScript** | ^5 | [package.json](../frontend/package.json) · [tsconfig.json](../frontend/tsconfig.json) |
| UI 组件库 | **Ant Design** + `@ant-design/icons` | antd 6.6.5 / icons 6.3.4 | [package.json](../frontend/package.json) |
| antd ↔ App Router 适配 | `@ant-design/nextjs-registry` | ^1.3.0 | [layout.tsx](../frontend/app/layout.tsx) |
| E2E 测试 | **Playwright** | ^1.63.0 | [package.json](../frontend/package.json) · [playwright.config.ts](../frontend/playwright.config.ts) |
| 构建产物形态 | `standalone`（默认）/ `export`（静态导出） | — | [next.config.mjs](../frontend/next.config.mjs) |

**关键事实源校准**：以上技术栈全部取自 [package.json](../frontend/package.json)，与根 README 首行 `Next.js 14` 一致。

### 工程脚本

```bash
cd frontend
npm install
npm run dev        # http://localhost:3000，/api 代理到 8090
npm run build      # next build
npm run start      # next start
npm run typecheck  # tsc --noEmit
npm run lint       # next lint
npm run test:e2e   # playwright test
```

---

## 2. 模块划分

前端是一个**单页控制台**：所有功能收敛到根路径 `/`，通过 URL 查询参数 `?tab=xxx` 在 8 个 Tab 间切换。

### 2.1 八大功能 Tab（产品动线）

| Tab Key | 名称 | 入口组件 | 主要子能力 |
|---|---|---|---|
| `chat` | 对话 | [ChatPanel.tsx](../frontend/components/chat/ChatPanel.tsx) | 会话列表 / SSE 流式问答 / 引用来源 / 有用-无用反馈 / 查链路跳转 |
| `upload` | 材料上传 | [UploadPanel.tsx](../frontend/components/upload/UploadPanel.tsx) + [RecentDocuments.tsx](../frontend/components/upload/RecentDocuments.tsx) | 拖拽批量上传 / 业务域子域生效日期 / 重复上传识别 / 跳任务详情 |
| `tasks` | 任务中心 | [TaskTable.tsx](../frontend/components/tasks/TaskTable.tsx) + [TaskDetail.tsx](../frontend/components/tasks/TaskDetail.tsx) | 任务列表筛选 / 步骤 Steps / 事件 Timeline / 人工任务 / 暂停恢复取消重放 |
| `workbench` | 人工工作台 | [ReviewCasesTab.tsx](../frontend/components/workbench/ReviewCasesTab.tsx) · [HumanTasksTab.tsx](../frontend/components/workbench/HumanTasksTab.tsx) · [VersionHistoryTab.tsx](../frontend/components/workbench/VersionHistoryTab.tsx) | 复核案件 / 人工任务认领处理 / 历史版本与 diff |
| `graph` | 知识图谱 | [GraphPanel.tsx](../frontend/components/graph/GraphPanel.tsx) | 实体搜索 + Canvas 力导向图 + 一跳邻居 + 关联 chunk |
| `govern` | 知识治理 | [GovernPanel.tsx](../frontend/components/govern/GovernPanel.tsx) · [ConflictReviewPanel.tsx](../frontend/components/govern/ConflictReviewPanel.tsx) | 指标聚合 / 答案评测样本 / RAGAS 历史运行 / 冲突扫描与裁决 |
| `observe` | 可观测 | [ObservePanel.tsx](../frontend/components/observe/ObservePanel.tsx) | 执行路径（trace spans）/ 业务指标 KPI / 知识明细表 / 反馈与网关审计 |
| `settings` | 身份设置 | [LocalSettingsForm.tsx](../frontend/components/settings/LocalSettingsForm.tsx) + [AdminIdentityPanel.tsx](../frontend/components/settings/AdminIdentityPanel.tsx) | 本地 userId / 身份 / 业务域子域（localStorage）+ 管理端身份画像 |

### 2.2 通用组件

- [AppShell.tsx](../frontend/components/AppShell.tsx)：全局外壳，顶部品牌栏 + 当前用户/身份 chip。
- [Console.tsx](../frontend/components/Console.tsx)：8 Tab 主容器，URL 与 Tab 双向同步。
- [common/TaskStatusTag.tsx](../frontend/components/common/TaskStatusTag.tsx) · [DocStatusTag.tsx](../frontend/components/common/DocStatusTag.tsx)：状态标签。

### 2.3 工具层 `lib/`

| 文件 | 职责 |
|---|---|
| [api.ts](../frontend/lib/api.ts) | 全部 REST 接口的 fetch 封装，自动注入身份头、统一错误处理 |
| [sse.ts](../frontend/lib/sse.ts) | 对话流 + 任务流的 SSE 解析（手写 fetch 流式读取，非 EventSource） |
| [settings.ts](../frontend/lib/settings.ts) | 本地身份配置读写（localStorage）+ 选项常量 |
| [types.ts](../frontend/lib/types.ts) | 后端 DTO 的 TypeScript 精确类型 |
| [datetime.ts](../frontend/lib/datetime.ts) | 时间格式化 |

### 2.4 结构化结果（上传 Tab 内的二级视图）

- [ResultsPanel.tsx](../frontend/components/results/ResultsPanel.tsx) · [FieldTable.tsx](../frontend/components/results/FieldTable.tsx) · [FieldLineageDrawer.tsx](../frontend/components/results/FieldLineageDrawer.tsx)：展示文档字段抽取结果与字段血缘。

---

## 3. 核心链路

### 3.1 页面启动与布局链路

```
浏览器请求 /
  ↓
app/layout.tsx（RootLayout，服务端组件）
  ├─ <html lang="zh-CN">
  ├─ <AntdRegistry>          ← antd 6 在 App Router 下的样式注入
  ├─ <ConfigProvider locale={zhCN} theme={token}>  ← 主题色 #6366f1、圆角、字体
  ├─ <AntdApp>               ← 提供 App.useApp() 的 message/modal 上下文
  ├─ <Suspense fallback={<Spin/>}>
  └─ <AppShell>{children}</AppShell>
       ↓
app/page.tsx（'use client'）→ <Console/>
       ↓
Console 读取 ?tab= → 渲染对应 Tab 组件
```

**要点**：`layout.tsx` 是服务端组件，只负责 antd 全局注入；所有交互在客户端组件内完成。`AppShell` 订阅 `wikiagent-settings-changed` 自定义事件 + `storage` 事件，保证跨 Tab/页面身份变更实时刷新头部。

### 3.2 对话 SSE 流式链路（核心）

**入口**：[ChatPanel.send()](../frontend/components/chat/ChatPanel.tsx#L163) → [chatStream()](../frontend/lib/sse.ts#L19)

```
用户输入问题
  ↓
ChatPanel.send()
  ├─ 立即在 messages 末尾追加 user 消息 + 空 assistant（streaming=true）
  ├─ 读取 getSettings() → 带上 domain/subDomain/identity/sessionId
  └─ chatStream(body, onEvent) 返回 AbortController
       ↓
POST /api/chat（fetch + signal）
  ├─ 请求头：Content-Type: application/json
  │         X-User-Id: <userId>
  │         X-Business-Identity: <identity>   ← 信任边界，后端据此做权限路由
  └─ res.ok 后 → parseSseStream(res.body, onEvent, signal)
       ↓
parseSseStream 按行解析 SSE 帧：
  event: session  →  记录 sessionId（用于反馈/查链路）
  event: stage    →  更新当前阶段标签（routing/rewriting/retrieving/grading/generating/fallback）
  event: sources  →  写入引用来源数组
  event: delta    →  content += text（流式拼接）
  event: blocked  →  输入/输出网关拦截，标记 blocked 并停止
  event: error    →  标记 error 并停止
  event: done     →  streaming=false，刷新会话列表
```

**关键设计**：
- **不用 EventSource，而用 `fetch` + `ReadableStream`**：因为 EventSource 不支持自定义 HTTP 头，而本系统要求全链路携带 `X-User-Id` / `X-Business-Identity`（信任边界）。
- **`updateLastAssistant` 不可变更新**：只修改数组最后一条 assistant 消息，避免整列表重渲染抖动。
- **`abortRef`**：组件卸载或点「停止」时 `abortRef.current.abort()`，中断 fetch。
- **阶段进度可视化**：`STAGE_ORDER` 渲染 6 个 Tag，当前阶段高亮 `processing`。

### 3.3 文档上传链路

**入口**：[UploadPanel.doUpload()](../frontend/components/upload/UploadPanel.tsx#L28)

```
用户拖拽/选择文件
  ↓
antd Upload.Dragger（customRequest，不使用默认上传列表）
  ↓
doUpload(file)
  ├─ 生成唯一 key，results 头部插入 state=uploading
  ├─ 合并业务域：本批选择 > 全局 settings.domain
  └─ uploadDocument(file, domain, subDomain, effectiveDate)
       ↓
POST /api/documents（multipart/form-data，带身份头）
  ↓
返回 DocumentView { id, taskId, duplicate, ... }
  ├─ duplicate=true  → warning「重复上传，已关联既有任务」
  └─ duplicate=false → success，提供「查看任务」链接 → /?tab=tasks&taskId=xxx
```

**要点**：上传是**同步返回任务 ID**，真正的解析/切分/向量化在后端任务框架异步执行，前端跳转到任务详情查看进度。

### 3.4 任务详情实时链路

**入口**：[TaskDetail.tsx](../frontend/components/tasks/TaskDetail.tsx)

```
进入 /?tab=tasks&taskId=xxx
  ↓
useEffect → loadSnapshot()（Promise.all 并行拉 4 个接口）
  ├─ GET /api/tasks/{id}            → 任务状态/进度/错误
  ├─ GET /api/tasks/{id}/steps      → 执行步骤 Steps
  ├─ GET /api/tasks/{id}/events     → 事件 Timeline
  └─ GET /api/tasks/{id}/human-tasks → 人工任务
  ↓
若 status 非终态（COMPLETED/FAILED/CANCELLED）→ startStream()
  ↓
subscribeTaskStream(taskId, handlers)
  ├─ GET /api/tasks/{id}/stream（SSE）
  ├─ progress 事件 → 更新 progressPercent
  ├─ done/error 事件 → 停止重连 + 重新 loadSnapshot
  └─ 断线 → 指数退避重连（1s,2s,4s,8s,16s，最多 5 次）
       ↓
终态后 → close() 关闭流
```

**关键设计**：任务流复用同一个 `parseSseStream` 解析器，并在 `subscribeTaskStream` 内封装了**指数退避自动重连**。收到 `done`/`error` 后主动 break，不再重连。

### 3.5 身份与信任边界链路

```
LocalSettingsForm 保存
  ↓
saveSettings() → 写入 localStorage 4 个 key（wikiagent.userId / .identity / .domain / .subDomain）
  ↓
window.dispatchEvent(new Event('wikiagent-settings-changed'))
  ↓
AppShell 监听该事件 → 刷新头部用户/身份 chip
  ↓
后续所有 api.ts / sse.ts 请求
  ↓
authHeaders() 自动注入：
  X-User-Id: <userId>
  X-Business-Identity: <identity>   ← 后端 TrustBoundary 校验
```

**要点**：身份信息**纯前端 localStorage**，无登录态；后端通过请求头识别用户与业务身份，做权限与数据域隔离。

---

## 4. 核心代码设计

### 4.1 AppShell —— 全局外壳与身份热更新

[AppShell.tsx](../frontend/components/AppShell.tsx)

- `useState<LocalSettings>` + `useEffect` 监听两个事件：
  - `wikiagent-settings-changed`（同页表单保存派发）
  - `storage`（跨标签页 localStorage 变更）
- 路由跳转：品牌区 → `/?tab=upload`；用户 chip → `/?tab=settings`。
- `minWidth: 1200`，固定宽屏工作台布局。

### 4.2 Console —— URL 驱动的单页多 Tab

[Console.tsx](../frontend/components/Console.tsx)

- **Tab 状态与 URL 双向同步**：`useSearchParams().get('tab')` 决定 `activeKey`；`onChange` 调用 `router.replace('/?tab=xxx', { scroll: false })`。
- **详情视图在 Tab 内展开**：`?tab=tasks&taskId=xxx` 时 TasksPane 渲染 `TaskDetail`，点返回清除参数回到列表。
- **旧深链兼容**：`/tasks/:taskId`、`/results/:docId` 由 [next.config.mjs](../frontend/next.config.mjs) `redirects` 做 **307 临时重定向** 到 `/?tab=...`；静态导出模式由后端 `LegacyRedirectController` 做同样重定向。
- **Tab 不销毁**：antd Tabs 默认保留已激活 Tab 的 DOM，因此切走再切回对话时 SSE 状态不丢失。

### 4.3 lib/api.ts —— 统一请求层

[api.ts](../frontend/lib/api.ts)

```
request<T>(path, init?)
  ├─ 合并 authHeaders()（X-User-Id / X-Business-Identity）
  ├─ fetch(path, { ...init, headers })
  └─ handleResponse<T>(res)
       ├─ res.ok  → res.json() as T
       └─ !res.ok → 尝试解析 BackendErrorBody.message，否则默认「请求失败（HTTP N）」，throw Error
```

- `jsonBody()` 工具：生成 `{ method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body) }`。
- `taskAction()` 工厂：暂停/恢复/取消/重放共用一个 `/api/tasks/{id}/{action}` 模式。
- 所有路径走相对路径 `/api/...`，由 Next.js `rewrites` 代理到后端（dev/standalone）或同域（export）。

### 4.4 lib/sse.ts —— 手写 SSE 解析器

[sse.ts](../frontend/lib/sse.ts)

**为什么不用 EventSource**：EventSource 规格不支持自定义请求头，无法携带 `X-User-Id` / `X-Business-Identity`。因此用 `fetch` 获取 `ReadableStream<Uint8Array>`，手动按 SSE 协议解析。

**`parseSseStream` 状态机**：
- 维护 `buffer` 字符串，按 `\n` 切行（去掉 `\r`）。
- `event:` 行 → 记录事件名；`data:` 行 → 追加到 `dataLines`；空行 → `flush()`。
- `flush()` 把 `dataLines.join('\n')` 尝试 `JSON.parse`，失败则原样传递，回调 `onEvent(eventName, data)`。
- 注释行（`:` 开头）跳过，用于心跳。
- `signal.aborted` 时 `reader.cancel()` 退出。

**`subscribeTaskStream` 重连策略**：
- `MAX_RETRIES = 5`，延迟 `Math.min(1000 * 2 ** retries, 16000)`。
- 收到 `done`/`error` 事件设 `terminal=true`，break 不再重连。
- 暴露 `close()`，组件卸载时调用。

### 4.5 lib/settings.ts —— 本地身份配置

[settings.ts](../frontend/lib/settings.ts)

- 4 个 key：`wikiagent.userId` / `.identity` / `.domain` / `.subDomain`。
- `getSettings()` SSR 安全：`typeof window === 'undefined'` 时返回 `DEFAULT_SETTINGS`。
- `saveSettings()` 写 localStorage 后 `dispatchEvent('wikiagent-settings-changed')`，驱动 AppShell 刷新。
- 常量：`IDENTITY_OPTIONS`（5 身份）、`DOMAIN_OPTIONS`（9 域）、`SUB_DOMAIN_OPTIONS`（6 子域）。

### 4.6 next.config.mjs —— 双构建模式

[next.config.mjs](../frontend/next.config.mjs)

- **`NEXT_BUILD_STATIC=true`** → `output: 'export'`（静态导出，交给 Spring Boot 托管，`images.unoptimized`）。
- **默认** → `output: 'standalone'`（Next.js 独立服务，见 [Dockerfile](../frontend/Dockerfile) 端口 3000）。
- `rewrites`：`/api/:path*` → `${API_BASE_URL || http://localhost:8090}/api/:path*`（dev/standalone 代理）。
- `redirects`：旧一级路由 307 到 `/?tab=xxx`（export 模式不支持 redirects，由后端兜底）。

### 4.7 GraphPanel —— Canvas 力导向图

[GraphPanel.tsx](../frontend/components/graph/GraphPanel.tsx)

- 不引入第三方图库，用原生 `<canvas>` 自绘力导向布局。
- `fetch('/api/graph/search?q=...')` 返回 `{ matchedEntities, neighborEntities, edges, relatedChunkIds }`。
- 实体按类型着色（人物/组织/产品/地点/概念/事件/规则）。

---

## 5. 面试高频问答速记

**Q1：为什么对话流不用 EventSource？**
A：EventSource 不支持自定义请求头，而本系统要求全链路携带 `X-User-Id` 与 `X-Business-Identity` 做信任边界校验。因此用 `fetch` + `ReadableStream` 手动解析 SSE 帧，同时获得 `AbortController` 中止能力。

**Q2：Tab 切换为什么不丢失对话 SSE 状态？**
A：antd `Tabs` 默认不销毁已激活 Tab 的子组件，`ChatPanel` 一直挂载；且 `Console` 用 `router.replace` 只改 URL 查询参数，不触发页面级导航。

**Q3：身份信息存在哪里？后端如何鉴权？**
A：存在浏览器 `localStorage`（`wikiagent.userId` 等 4 个 key），无传统登录态。每次请求由 `authHeaders()` 自动注入 `X-User-Id` / `X-Business-Identity` 头，后端 `TrustBoundaryProperties` 校验并据此做数据域隔离。

**Q4：任务进度如何实时更新？断线怎么办？**
A：进入非终态任务时 `subscribeTaskStream` 订阅 `/api/tasks/{id}/stream`（SSE），`progress` 事件更新进度条，`done`/`error` 后重新拉快照。断线走指数退避（1→2→4→8→16s，最多 5 次），仍失败提示手动刷新。

**Q5：前端如何处理后端错误？**
A：`api.ts` 的 `handleResponse` 在 `!res.ok` 时尝试解析 `BackendErrorBody.message`，解析失败用默认「请求失败（HTTP N）」，统一 `throw new Error(message)`，由各组件 `App.useApp().message.error` 展示。

**Q6：旧路由 `/tasks/123` 怎么不 404？**
A：[next.config.mjs](../frontend/next.config.mjs) 的 `redirects` 把 `/tasks/:taskId` 307 到 `/?tab=tasks&taskId=:taskId`；静态导出模式由后端 `LegacyRedirectController` 做相同重定向，保证旧书签可用。

**Q7：Next.js standalone 和 export 两种产物怎么选？**
A：默认 `standalone`（Next.js 跑 Node 服务，支持 rewrites/redirects）；设 `NEXT_BUILD_STATIC=true` 走 `export` 静态导出，交给 Spring Boot 同域托管，此时 `/api` 不再需要代理，旧路由重定向由后端接管。
