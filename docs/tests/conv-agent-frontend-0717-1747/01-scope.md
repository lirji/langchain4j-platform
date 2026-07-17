# 01 — 被测面与事实基线

## 1. 任务边界

- 只做测试设计与前端交互逻辑审查；未修改任何业务代码，也未向 `src/` 写入测试。
- 蓝图目标仓库：`capability-showcase-frontend`（Vue 3 + TypeScript + Vite）。
- 测试环境以 `vitest.config.ts` 为准：Vitest、jsdom、`VITE_AUTH_MODE=apikey`。
- 目录夹具必须通过 `src/test/fixtures.ts` 的 `loadCatalog()` 读取 `public/catalog.json`；该 JSON 由 `npm run gen:catalog` 从 `capabilities.yml` 生成，不能在测试里另造能力目录。
- 题面 Java/Maven/H2/TenantContext 铁律不适用于本纯前端被测面。等价前端约束是：测试与被测 SFC/TS 同目录、`*.test.ts`、`mount`、`vi.mock`/`vi.stubGlobal('fetch', ...)`、Pinia 每例重建、ReadableStream 分块 SSE、`afterEach` 卸载 wrapper 并清 DOM/全局 mock。

## 2. 实际目录能力数

执行 `npm run gen:catalog` 后读取实际 `public/catalog.json`，六模块合计 **57** 项：

| 模块 | 数量 | 能力 id |
|---|---:|---|
| chat | 10 | `chat.sync`, `chat.stream`, `chat.extract`, `chat.auto`, `chat.cascade`, `chat.mcp`, `chat.memory`, `memory.profile.get`, `memory.profile.clear`, `chat.cache.clear` |
| rag | 11 | `rag.query`, `rag.upload.file`, `rag.upload.file.shared`, `rag.upload.json`, `rag.upload.json.shared`, `rag.documents.list`, `rag.documents.delete`, `rag.documents.get`, `rag.obsidian.import`, `rag.graph.query`, `rag.graph.entities` |
| agent | 18 | `agent.run`, `agent.run.async`, `agent.tasks.list`, `agent.tasks.get`, `agent.tasks.stream`, `agent.tasks.cancel`, `agent.dag.run`, `agent.dag.plan-run`, `agent.dag.run.async`, `agent.dag.plan-run.async`, `agent.chain`, `agent.vote`, `agent.reflexive`, `agent.reflexive.stream`, `agent.analyst.run`, `agent.analyst.run.async`, `agent.process.run`, `agent.process.run.async` |
| tasks | 8 | `async.create`, `async.list`, `async.get`, `async.status.update`, `async.lease`, `async.cancel`, `async.stream`, `async.deadletter` |
| workflow | 7 | `workflow.refund.start`, `workflow.tasks.list`, `workflow.tasks.claim`, `workflow.tasks.unclaim`, `workflow.tasks.complete`, `workflow.instances.get`, `workflow.data.purge` |
| analytics | 3 | `analytics.schema.tables`, `analytics.schema.describe`, `analytics.sql` |

当前生成目录的状态事实：唯一 `flag-off` 是 `chat.mcp`；`rag.graph.*`、`agent.process.*`、`analytics.sql` 当前均为 `ready`，虽然它们仍带 `featureFlag` 元数据。测试应断言目录事实和闸门实际行为，不应延续源码注释中的旧 flag-off 假设。

## 3. 受影响文件、方法与职责

### chat

- `src/modules/chat/ChatConsoleView.vue`
  - 模式/委派：`pickInitialMode`, `availableModes`, `delegateToRunner`。
  - 请求映射：`buildValues`, `send`, `regenerate`, `stop`。
  - 消息状态机：token/note/phase 三个 watcher、`finalizeIfTerminal`, `startAssistant`, `clearAll`。
  - 画像：`loadProfile`, `clearProfile`。
  - 导出：`exportMarkdown`, `exportJson`。
- 现有：`src/modules/chat/ChatConsoleView.test.ts`，6 例；只覆盖模式、深链和 MCP/画像入口。

### rag

- `src/modules/rag/RagWorkspaceView.vue`
  - 通用调用：`callCap`, `probeRagConfig`。
  - 文档库：`loadDocs`, `goToPage`, `changePageSize`, `switchTab`, `viewDetail`, `confirmDelete`, `onUploaded`。
  - 检索：`runSearch`, `parseHits`, `segmentsFor`。
  - 双控：`sharedTabEnabled = SHARED_KB_UI_ENABLED && ragConfig.publicEnabled`。
- `src/api/knowledge.ts`：`fetchRagConfig`, `listDocumentsPaged`, `getDocument`, `deleteDocument`，含 visibility/query/编码/凭证。
- 现有：`src/modules/rag/RagWorkspaceView.test.ts`，6 例；`src/api/knowledge.test.ts` 覆盖部分 API helper。

### agent

- `src/modules/agent/AgentLabView.vue`
  - 模式：`MODES`, `modeGroups`, `pickInitial`, `activeGate`。
  - 请求映射：`primaryParam`, `canSend`, `buildValues`, `send`, `stop`。
  - 响应分流：`extractSteps`, `extractFinal`, `extractTaskId`, `showAsyncPanel`, `showSteps`, `showRunnerFallback`。
- `src/modules/agent/AgentStepTimeline.vue`：`rendered` 对 string/known fields/raw 的分支。
- 现有：`src/modules/agent/AgentLabView.test.ts`，8 例；无 `AgentStepTimeline.test.ts`。

### tasks

- `src/modules/tasks/AsyncMonitorView.vue`
  - 本地投影：`upsert`, `ingest`, `onRunnerResult`。
  - 动作：`refreshTask`, `cancelTask`, `streamTask`。
  - SSE 资源：`streamHandles`、`onUnmounted`。
  - 死信：`parseDeadRows`, `loadDeadletter`, `deadFallback`。
- `src/modules/tasks/AsyncTaskTimeline.vue`
  - 分组/过滤：`groupOf`, `counts`, `toggleFilter`, `visibleTasks`。
  - 阶段：`reachedIndex`, `terminalTone`, `stageLabel`, `isTerminal`。
- `src/modules/tasks/types.ts`：`TrackedTask`。
- 现有：`AsyncMonitorView.test.ts` 3 例，`AsyncTaskTimeline.test.ts` 4 例，主要为结构/过滤冒烟。

### workflow

- `src/modules/workflow/WorkflowDeskView.vue`
  - 统一动作：`exec`。
  - 发起串联：`onStartResult`。
  - 待办：`parseInbox`, `refreshInbox`, `selectTask`, `claim`, `unclaim`, `complete`。
  - 优先级：`prioDigit`, `prioLabel`, `prioTone`。
- 现有：`src/modules/workflow/WorkflowDeskView.test.ts`，6 例；仅发起回调串联、区域和危险闸门。

### analytics

- `src/modules/analytics/AnalyticsLabView.vue`
  - 通用调用：`callCap`。
  - schema：`parseTables`, `loadTables`, `selectTable`, `toRowObjects`, `describeRows`。
  - NL2SQL：`extractSql`, `runSql`, `generatedSql`, `sqlRows`, `sqlFallback`。
- `src/modules/_shared/ResultTable.vue`：列并集与值格式化。
- 现有：`src/modules/analytics/AnalyticsLabView.test.ts`，5 例；只覆盖结构、凭证前置与深链。

### 六模块共享基础设施

- `src/components/capability/CapabilityRunner.vue`
  - `buildExampleValues`, `loadExample`, `execute`, 键盘提交/停止、历史记录 watcher、重放。
  - 当前无测试。
- `src/composables/useCapabilityRun.ts`
  - `run`, `runOnce`, `runStream`, `abort`, `reset`, `resetOutputs`；核心状态机，无测试。
- `src/composables/useAbortable.ts`
  - `fresh`, `abort`, scope dispose；无测试。
- `src/components/capability/SseConsole.vue`
  - token/事件视图、搜索、下载、note/error；无测试。
- `src/api/client.ts`, `src/api/sse.ts`, `src/api/errors.ts`
  - 已有纯逻辑测试，但没有与六个专用视图的端到端组件交互串联。
- `src/stores/history.ts`, `src/stores/favorites.ts`
  - 无测试；`session.ts` 已有凭证模式测试。

## 4. feature flag / 安全边界

- 当前目录 `chat.mcp.state=flag-off`，必须禁发请求并显示 `app.conversation.mcp.enabled`。
- `scope-required`：rag tenant upload=`ingest`、shared upload=`public-ingest`、workflow task actions=`approve`。固定 apikey 测试环境下 scope 不透明，允许发请求但必须有提示；Bearer 缺 scope 分支由 gate 单测覆盖，不能在 apikey 视图测试中伪造为同一行为。
- `workflow.data.purge` 为 `display-only` 且 `executableByDefault=false`：未确认禁止；确认后才允许 DELETE，并带 `chatId` query。
- 租户不由前端表单传 `tenantId`；身份来自 `X-Api-Key`/Bearer。测试应确认无 `tenantId` 头或跨租户 body，shared KB 只以 `visibility=public` 显式分区。

## 5. 已执行基线

2026-07-17 在 `capability-showcase-frontend` 执行：

```bash
npx vitest run \
  src/modules/chat/ChatConsoleView.test.ts \
  src/modules/rag/RagWorkspaceView.test.ts \
  src/modules/agent/AgentLabView.test.ts \
  src/modules/tasks/AsyncMonitorView.test.ts \
  src/modules/tasks/AsyncTaskTimeline.test.ts \
  src/modules/workflow/WorkflowDeskView.test.ts \
  src/modules/analytics/AnalyticsLabView.test.ts
```

结果：7 files / 38 tests 全绿。这只证明现有壳层基线，不代表 57 能力交互已覆盖。

## 6. 蓝图落地后的运行命令

```bash
cd capability-showcase-frontend
npm run gen:catalog
npx vitest run src/components/capability/CapabilityRunner.interaction.test.ts
npx vitest run src/composables/useCapabilityRun.test.ts
npx vitest run src/modules/chat/ChatConsoleView.interaction.test.ts
npx vitest run src/modules/rag/RagWorkspaceView.interaction.test.ts
npx vitest run src/modules/agent/AgentLabView.interaction.test.ts src/modules/agent/AgentStepTimeline.test.ts
npx vitest run src/modules/tasks/AsyncMonitorView.interaction.test.ts src/modules/tasks/AsyncTaskTimeline.test.ts
npx vitest run src/modules/workflow/WorkflowDeskView.interaction.test.ts
npx vitest run src/modules/analytics/AnalyticsLabView.interaction.test.ts
npm test
npm run type-check
```

`mvn`、`INTERNAL_JWT_SECRET`、H2、Spring profile 在本前端范围均不适用。
