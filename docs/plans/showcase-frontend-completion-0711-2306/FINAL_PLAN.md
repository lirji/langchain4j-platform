# FINAL PLAN

> **阶段二复核修正（Claude 跨模型核验，2026-07-11）**
>
> 逐条核验通过（真实仓库）：`ModuleHost.vue` 的 `SPECIALIZED` 注册表（现有 chat/tasks）、`client.ts` **确未处理 `in:'header'`**、`assembleRequest` 无 `multipart-sse`、`useCapabilityRun` 的 `isSse` 仅判 `'sse'`、`curl.ts` 无 header 输出、`types/catalog.ts` 的 `RequestKind`/`ParamIn`——均属实。后端：`/voice/chat/stream` 确为 `consumes=multipart/form-data, produces=text/event-stream`，字段是 `@RequestPart("audio")` + query `chatId`（**不是 `file`**）；`McpToolCallRequest(tool, arguments)`;`EvalController.gate` 确返回 **HTTP 422**；`/channel/inbound` 有 `@RequestHeader X-Channel-Signature`；`WorkflowService.StartResult(instanceId,status,reply,taskId,priority,deduplicated)`、`TaskView(taskId,name,instanceId,priority,summary,assignee)`——全部属实，无臆造。
>
> **修正一（方案选择，用户 2026-07-11 拍板）**：我曾建议「通用优先」精简版（仅 catalog+共享层，靠 GenericModuleView 覆盖），因为核验发现 GenericModuleView+CapabilityRunner+DynamicForm 已能数据驱动渲染所有表单。但**用户明确选择全量版 B（含 4 个领域专用视图）** 以获得更顺手的体验（Workflow start→claim→complete 串联、Channel 副作用二次确认、Multimodal 图像/语音分区、Interop-Eval 的 MCP 工具浏览与 gate-422 业务化）。故**按 Solution B 执行**：共享层增强 + catalog 补齐 + 4 个专用视图（`ModuleHost.SPECIALIZED` 注册）+ 组件测试。专用视图仍必须通过 `catalog.capabilityById(...)` 取能力并复用 `runCapability`/`streamCapability`/`executionGate`，不手写路径常量、不绕过安全闸门。
>
> **修正二（准确性）**：① `/channel/inbound` 的 `X-Channel-Signature` 为 `required=false`，且仅当 `inboundSignatureEnabled=true`（默认 false）才校验 → catalog 标为**可选 header**。② `/voice/chat/stream`、`/voice/chat` 的文件字段名是 **`audio`**（form-data），`chatId` 是 query；`/voice/transcribe` 同为 multipart。③ A2A `message/stream` 的 JSON-RPC params 未核验，catalog 先给占位模板并注明「待核验」，不写死字段。
>
> **首期范围（生效）**：阶段1 catalog 补齐 + `RequestKind` 加 `multipart-sse` → 阶段2 共享层（header 注入不覆盖 `X-Api-Key`、multipart-sse 流式、curl/gate/eval-422） → 阶段3 **仅注册 catalog，不加专用视图**（全部走 GenericModuleView） → 阶段4 测试 → 阶段5 文档。专用视图（原阶段3 的 4 个 View）移入「可选二期」。

## 背景、目标与非目标

当前 `capability-showcase-frontend/` 已是独立 Vue/Vite 前端，运行方式为静态 `catalog.json` + 浏览器 direct mode 调 `edge-gateway`。P0 骨干模块可用，但 P1/P2 的 `workflow`、`multimodal`、`interop-eval`、`channel` 在 `capabilities.yml` 中仍为空能力列表；同时 P0 的高级能力大量存在于后端 controller，但前端只列了少数骨干能力。

目标：

- 补齐 P1/P2 模块交互能力，消除占位空态。
- 补齐 P0 高级能力表单，覆盖真实 controller 已有端点。
- 复用现有 `CapabilityRunner`、`DynamicForm`、`runCapability`、`streamCapability`、`executionGate`。
- 为 Workflow、Multimodal、Interop/Eval、Channel 增加必要的专用视图，处理状态串联、文件/流、协议工具、副作用确认。

非目标：

- 不修改业务服务 controller、DTO、数据库、消息结构。
- 不新增后端能力目录服务。
- 不持久化 API Key 或运行历史。
- 不保证未开启 feature flag 的后端端点可执行成功。
- 不在首期实现浏览器录音采集；首期语音能力以音频文件上传为准，录音为后续增强。

## 已确认的业务规则

- 前端 API Key 只存在 `src/stores/session.ts` 的内存状态中。
- 所有业务调用由 `edge-gateway` 路由，`edge-gateway/src/main/resources/application.yml` 已覆盖本任务涉及的 `/workflow/**`、`/vision/**`、`/voice/**`、`/channel/**`、`/interop/**`、`/eval/**` 等路径。
- `executionGate` 已定义执行规则：`flag-off` 禁执行；危险能力需二次确认；无 API Key 禁执行；`scope-required` 可尝试但提示 403 风险。
- Workflow 端点来自 `WorkflowController`：
  - `POST /workflow/refund/start`：字段 `chatId/message/dedupeId/webhookUrl`。
  - `GET /workflow/tasks`、claim/unclaim/complete/purge 需要 `approve` scope。
  - `DELETE /workflow/data?chatId=...` 是破坏性合规删除。
  - `WorkflowService.StartResult` 字段为 `instanceId/status/reply/taskId/priority/deduplicated`。
  - `WorkflowService.TaskView` 字段为 `taskId/name/instanceId/priority/summary/assignee`。
- Multimodal：
  - `VisionController` 仅 `app.vision.enabled=true` 注册。
  - `VoiceController` 仅 `app.voice.enabled=true` 注册。
  - `/voice/chat/stream` 是 multipart + SSE，当前前端 `requestKind === 'sse'` 不能正确表达，必须先补共享请求能力。
  - `/rag/image` 需要 `ingest` scope。
- Interop/Eval：
  - `InteropController.call(...)` 入参为 `McpToolCallRequest(tool, arguments)`。
  - `A2aController.handle(...)` 的 `message/stream` 返回 SSE；具体 JSON-RPC `params` 示例实施时继续读取 A2A record，未核验字段不得写死。
  - `EvalController.gate(...)` 可能返回 422，这是门禁失败的业务结果，不应只当通用网络错误。
- Channel：
  - `/channel/messages` 会触发真实外部投递，必须默认锁定。
  - `/channel/inbound` 需要 `X-Channel-Signature` header；当前 `ParamIn` 有 `header`，但请求装配未实现 header 注入。

## 当前代码与调用链分析

- 路由入口：`src/router/index.ts` 将 `/m/:moduleId/:capId` 交给 `ModuleHost`。
- 模块分派：`src/modules/ModuleHost.vue` 当前只有 `chat`、`tasks` 专用视图，其余走 `GenericModuleView`。
- 占位根因：`capabilities.yml` 中 P1/P2 四模块 `capabilities: []`，`GenericModuleView` 因此显示“能力待补”。
- 通用表单：`DynamicForm.vue` 支持 string/text/number/integer/boolean/select/file/json/array/object。
- 请求装配：`src/api/client.ts` 支持 path/query/body/form-data，但不支持 header；SSE 只支持 JSON/无 body。
- SSE：`src/api/sse.ts` 用 fetch + ReadableStream，已有解析测试。
- 安全闸门：`src/utils/gate.ts` 是单点执行裁决。
- curl：`src/utils/curl.ts` 不泄露真实 API Key，但未支持 header 参数。
- live discovery：`src/api/catalog.ts` 只把已在静态 manifest 中命中的能力标为 live，不生成新表单。

## 候选方案对比与评分

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A Manifest-first 通用运行器增强 | 4 | 4 | 5 | 3 | 3 | 4 | 5 | 28 |
| B Catalog + 领域专用工作台 | 5 | 3 | 3 | 4 | 4 | 3 | 4 | 30 |
| C 插件化能力渲染器 | 4 | 2 | 2 | 4 | 5 | 2 | 3 | 26 |

选择：采用 B，吸收 A 的实施顺序，不采用 C 的 runner registry 重构。

理由：

- 单纯补 manifest 能最快消除占位，但 Workflow/Channel/Voice/Eval 的使用体验和风险控制不足。
- 领域专用视图能保留现有通用运行器，又能处理状态串联、文件流和副作用确认。
- 插件化 runner 长期扩展性好，但当前会重构稳定的 `CapabilityRunner`，回归面不值得。

已知弱点：

- B 的实现量大于 A。
- 专用视图必须防御式解析响应，避免依赖未核验字段。
- multipart SSE、header 参数、Eval 422 三个共享短板必须优先补，否则专用视图只是包装层。

## 最终方案

最终采用“共享请求层增强 + catalog 补齐 + 领域专用工作台”的方案。

核心原则：

- `capabilities.yml` 仍是能力目录事实源。
- 所有能力必须先以 manifest 形式登记，专用视图通过 `catalog.capabilityById(...)` 获取能力，不手写路径常量，除非是明确的模块内 fallback。
- 共享层先补 `header` 参数与 multipart SSE。
- P1/P2 模块通过 `ModuleHost.SPECIALIZED` 注册专用视图。
- P0 高级能力优先走通用运行器；只有已有专用体验明显不足时再增强对应模块。

## 精确修改清单

### 数据结构与类型

- `capability-showcase-frontend/src/types/catalog.ts`
  - 修改 `RequestKind`：推荐从 `'json' | 'multipart' | 'sse' | 'none'` 扩展为包含 `'multipart-sse'`。
  - 保留 `ParamIn = 'header'`，但补充注释：header 参数会作为业务 header 注入，禁止覆盖 `X-Api-Key`。
  - 可新增 `examples?: string[]` 或 `dangerPrompt?: string` 仅在确有 UI 文案重复时加入；首期不强制。

### 共享请求层

- `capability-showcase-frontend/src/api/client.ts`
  - 修改 `assembleRequest(cap, values, ctx)`：
    - 处理 `p.in === 'header'`，写入 `headers[p.name] = String(value)`。
    - 禁止 header 参数覆盖 `API_KEY_HEADER`，`X-Api-Key` 仍只来自 `ctx.apiKey`。
    - 当 `cap.requestKind === 'multipart-sse'` 时，body 使用 `buildFormData(...)`，header 加 `Accept: text/event-stream`，不设置 `Content-Type`。
  - 可新增纯函数 `buildHeaderParams(params, values)` 便于测试。
- `capability-showcase-frontend/src/api/sse.ts`
  - 修改 `streamCapability(...)` 对 `multipart-sse` 走同一流式消费路径。
  - 确保 `isSse` 判断同时覆盖 `sse` 与 `multipart-sse`。如果当前逻辑在 `useCapabilityRun.ts`，则修改该文件。
- `capability-showcase-frontend/src/utils/curl.ts`
  - 增加 header 参数输出。
  - `multipart-sse` 输出 `-F` 与 `-N`。
  - 保持 `X-Api-Key: $API_KEY` 占位。
- `capability-showcase-frontend/src/utils/validation.ts`
  - 如 header 参数 required，沿用现有 required 校验即可。

### Catalog

- `capability-showcase-frontend/capabilities.yml`
  - 为 P1/P2 四模块补齐能力：
    - Workflow：`workflow.refund.start`、`workflow.tasks.list`、`workflow.tasks.claim`、`workflow.tasks.unclaim`、`workflow.tasks.complete`、`workflow.instances.get`、`workflow.data.purge`。
    - Multimodal：`vision.caption.json`、`vision.caption.file`、`chat.vision`、`voice.transcribe`、`voice.chat`、`voice.chat.stream`、`rag.image.ingest`、`rag.image.search`。
    - Interop/Eval：`interop.agent-card`、`interop.a2a.agent-card`、`interop.mcp.tools`、`interop.mcp.tool`、`interop.mcp.call`、`interop.a2a.call`、`eval.capabilities`、`eval.retrieval`、`eval.run`、`eval.suite.run`、`eval.dual-run`、`eval.gate`。
    - Channel：`channel.capabilities`、`channel.messages.send`、`channel.callbacks`、`channel.callbacks.async-task`、`channel.callbacks.workflow`、`channel.inbound`。
  - 为 P0 高级能力补齐能力：
    - Chat：`chat.auto`、`chat.cascade`、`chat.mcp`、`chat.memory`、`memory.profile.get`、`memory.profile.clear`、`chat.vision`、`chat.cache.clear`。
    - RAG：`rag.documents.get`、`rag.obsidian.import`、`rag.graph.query`、`rag.graph.entities`、`rag.image.ingest`、`rag.image.search`。
    - Agent：`agent.dag.run`、`agent.dag.plan-run`、`agent.dag.run.async`、`agent.dag.plan-run.async`、`agent.chain`、`agent.vote`、`agent.reflexive`、`agent.reflexive.stream`、`agent.analyst.run`、`agent.analyst.run.async`、`agent.process.run`、`agent.process.run.async`。
  - 对每个能力补全 `featureFlag`、`featureFlagDefault`、`riskLevel`、`state`、`requiredScopes`、`executableByDefault`。
- `capability-showcase-frontend/public/catalog.json`
  - 由 `npm run gen:catalog` 生成。是否提交按当前仓库实践确认；当前仓库已有 `public/catalog.json`，实施时应同步更新。

### 模块视图

- `capability-showcase-frontend/src/modules/ModuleHost.vue`
  - 注册：
    - `workflow: WorkflowDeskView`
    - `multimodal: MultimodalConsoleView`
    - `interop-eval: InteropEvalView`
    - `channel: ChannelConsoleView`
- 新增 `capability-showcase-frontend/src/modules/workflow/WorkflowDeskView.vue`
  - 使用 `workflow.refund.start` 发起流程，并把 `StartResult` 记录到本地列表。
  - 使用 `workflow.tasks.list/claim/unclaim/complete` 管理审批任务。
  - 使用 `workflow.instances.get` 查询实例。
  - `workflow.data.purge` 放入危险区。
- 新增 `capability-showcase-frontend/src/modules/multimodal/MultimodalConsoleView.vue`
  - 图像分区：vision caption、chat/vision、rag image ingest/search。
  - 语音分区：transcribe、voice chat、voice stream。
  - 首期只支持文件上传；录音采集后续增强。
- 新增 `capability-showcase-frontend/src/modules/interop/InteropEvalView.vue`
  - MCP 工具列表/详情/调用。
  - A2A agent card 与 JSON-RPC 调用；`message/stream` 先以 JSON 模板暴露，具体模板实施时继续核验。
  - Eval retrieval/run/suite/dual-run/gate。
  - 对 `/eval/gate` 的 422 做业务化展示。
- 新增 `capability-showcase-frontend/src/modules/channel/ChannelConsoleView.vue`
  - capabilities 只读。
  - messages/callbacks/inbound 分区。
  - messages 默认锁定并二次确认。

### 测试

- 新增或扩展：
  - `capability-showcase-frontend/src/api/client.test.ts`
  - `capability-showcase-frontend/src/utils/curl.test.ts`
  - `capability-showcase-frontend/src/stores/catalog.test.ts`
  - `capability-showcase-frontend/src/modules/workflow/WorkflowDeskView.test.ts`
  - `capability-showcase-frontend/src/modules/multimodal/MultimodalConsoleView.test.ts`
  - `capability-showcase-frontend/src/modules/interop/InteropEvalView.test.ts`
  - `capability-showcase-frontend/src/modules/channel/ChannelConsoleView.test.ts`

## 数据库、接口、配置、消息结构变更

- 数据库：无变更。
- 后端业务接口：无变更。
- 前端 catalog schema：新增 `requestKind: multipart-sse`，属于前端静态目录结构变更。
- 配置：无后端配置变更；前端仍使用现有 `VITE_EDGE_BASE_URL`、`VITE_BASE`、`VITE_CATALOG_URL`、`VITE_LIVE_DISCOVERY`。
- 消息结构：无变更。
- 网关：当前路由与 CORS 已覆盖目标路径，不计划修改。

## 分阶段实施步骤及依赖关系

### 阶段 1：数据结构与领域模型

任务：

- 扩展 `RequestKind` 支持 `multipart-sse`。
- 在 `capabilities.yml` 补 P1/P2 与 P0 高级能力目录。
- 为所有新增能力补齐 feature flag、scope、risk、state。

依赖：

- 以本计划列出的 controller/method 为事实源。
- 对 A2A `message/stream` 的具体 JSON-RPC 示例继续读取相关 record 后再写入。

完成标准：

- `npm run gen:catalog` 成功。
- 四个 P1/P2 模块的 `capabilities.length > 0`。
- 新增能力的 path/method/params 可追溯到真实 controller 或 protocol record。

### 阶段 2：核心业务逻辑

任务：

- 在 `client.ts` 支持 header 参数与 multipart SSE。
- 在 `sse.ts` / `useCapabilityRun.ts` 识别 `multipart-sse` 为流式能力。
- 在 `curl.ts` 支持 header 与 multipart SSE。
- 保证 header 参数不能覆盖 `X-Api-Key`。

完成标准：

- 单元测试证明 `/channel/inbound` 可注入 `X-Channel-Signature`。
- 单元测试证明 `/voice/chat/stream` 生成 multipart body + `Accept: text/event-stream`。
- `/chat/stream` 等已有 SSE 能力不回归。

### 阶段 3：接口与适配层

任务：

- 新增四个专用模块视图。
- 修改 `ModuleHost.vue` 注册专用视图。
- 专用视图全部通过 catalog 查找 capability 并复用 `runCapability` / `streamCapability`。
- Eval gate 422 在 `InteropEvalView` 中作为门禁失败结果展示。

完成标准：

- `/m/workflow`、`/m/multimodal`、`/m/interop-eval`、`/m/channel` 不再出现占位空态。
- Workflow 能发起、列任务、查询实例，危险清理默认锁定。
- Channel messages 默认锁定。
- Multimodal voice stream 可调用流式 runner。

### 阶段 4：测试

任务：

- 补共享请求层单测。
- 补 catalog 代表能力存在性测试。
- 补四个专用视图组件测试。
- 执行全量前端校验。

完成标准：

```bash
cd capability-showcase-frontend
npm run gen:catalog
npm test
npm run type-check
npm run build
```

全部通过。

### 阶段 5：文档与最终检查

任务：

- 更新 `capability-showcase-frontend/README.md` 的能力覆盖说明。
- 如有必要更新 `docs/平台工程/能力展示控制台.md` 与 `docs/平台工程/能力前端模块拆分建议.md`。
- 检查 curl 预览、API Key 内存策略、danger gate 文案。

完成标准：

- 文档与实际 catalog 一致。
- 不存在未核验接口字段被写成事实。
- `git diff` 只包含前端与文档相关文件，不包含业务后端代码。

## 测试方案

- 单元测试：
  - header 参数装配。
  - multipart SSE 装配。
  - curl 脱敏与 header 输出。
  - gate 对 flag-off/scope/destructive 的规则。
  - catalog 包含 P1/P2 与 P0 高级代表能力。
- 组件测试：
  - `GenericModuleView` 不再对 P1/P2 显示空态。
  - `WorkflowDeskView` 渲染关键区域并处理 409/403 错误。
  - `MultimodalConsoleView` 文件字段与 stream 调用。
  - `InteropEvalView` 对 422 展示响应体。
  - `ChannelConsoleView` messages 二次确认。
- 手工 smoke：
  - `dev-key-acme` 测 `/interop/mcp/tools`、`/channel/capabilities`、workflow start。
  - `dev-key-globex` 测 approve/ingest scope 不足。
  - 未开启 flag 时确认 UI 不执行 flag-off 能力。

## 风险、监控、灰度与回滚方案

### 风险

- Manifest 漂移：新增目录可能与 controller 变化不一致。
- multipart SSE 支持不完整会影响 voice stream。
- header 参数若实现不严谨，可能引入敏感 header 覆盖风险。
- Channel messages 有真实副作用，误点成本高。
- Eval gate 422 若仍走通用错误展示，会降低可用性。

### 监控

- 前端展示 `X-Trace-Id` 的现有响应能力继续保留。
- 手工 smoke 时记录失败能力的 HTTP status、traceId、响应体。
- 不新增后端监控指标。

### 灰度

1. 先合入 catalog 与共享请求层，专用视图不注册时可走 generic。
2. 依次注册 `interop-eval`、`workflow`、`multimodal`、`channel`。
3. Channel outbound 最后开放，并保持 `executableByDefault=false`。

### 回滚

- 回滚专用视图：从 `ModuleHost.SPECIALIZED` 移除对应模块，自动回到 `GenericModuleView`。
- 回滚某个能力：从 `capabilities.yml` 删除该 capability 并重新生成 catalog。
- 回滚共享 schema：如果 `multipart-sse` 出问题，可先把相关能力标为 `display-only` 或 `flag-off`，不影响已有 P0 骨干能力。
- 无数据库/消息迁移，因此无数据回滚。

## 最终验收清单

- [ ] `workflow`、`multimodal`、`interop-eval`、`channel` 均有能力卡片和交互入口。
- [ ] P0 高级能力已按真实端点补入 catalog。
- [ ] `header` 参数支持完成，且不能覆盖 `X-Api-Key`。
- [ ] `multipart-sse` 支持完成，`/voice/chat/stream` 可走流式控制台。
- [ ] Workflow 危险删除默认锁定。
- [ ] Channel outbound 默认锁定。
- [ ] Eval gate 422 有业务化展示。
- [ ] API Key 不落 URL、localStorage、日志、curl 明文。
- [ ] `npm test` 通过。
- [ ] `npm run type-check` 通过。
- [ ] `npm run build` 通过。
- [ ] 最终 diff 不包含业务后端代码修改。
