# 02 Codebase Analysis

## codebase-explorer 视角

### 前端工程结构

- `capability-showcase-frontend/package.json`
  - 脚本：`gen:catalog`、`dev`、`build`、`type-check`、`test`。
  - 依赖：Vue 3、Pinia、vue-router；测试用 Vitest、Vue Test Utils、jsdom。
- `capability-showcase-frontend/capabilities.yml`
  - 当前能力目录唯一事实源。
  - P0 五模块已有骨干能力。
  - `workflow`、`multimodal`、`interop-eval`、`channel` 当前 `capabilities: []`。
- `capability-showcase-frontend/scripts/gen-catalog.mjs`
  - 读取 `capabilities.yml`，写入 `public/catalog.json`。
  - 仅做结构存在性校验，没有做 path/method/param schema 深校验。
- `capability-showcase-frontend/src/types/catalog.ts`
  - 定义 `RequestKind = 'json' | 'multipart' | 'sse' | 'none'`。
  - 定义 `ParamIn` 已包含 `header`。
  - 定义 `CapabilityState` 已包含 `flag-off`、`scope-required`、`display-only`。
- `capability-showcase-frontend/src/router/index.ts`
  - 路由为 `/`、`/m/:moduleId`、`/m/:moduleId/:capId`，全部经 `ModuleHost`。
- `capability-showcase-frontend/src/modules/ModuleHost.vue`
  - 目前只有 `chat` 与 `tasks` 使用专用视图。
  - 其他模块走 `GenericModuleView`。
- `capability-showcase-frontend/src/modules/GenericModuleView.vue`
  - 若模块有 capability，展示能力卡片。
  - 若为空，展示“能力待补”占位。
  - 选中 capability 后直接渲染 `CapabilityRunner`。

### 通用请求与表单链路

- `CapabilityRunner.vue`
  - 输入：单个 `Capability`。
  - 使用 `DynamicForm` 渲染参数。
  - 使用 `useCapabilityRun` 执行。
  - 提供二次确认、执行按钮、curl 预览、响应展示、SSE 控制台。
- `DynamicForm.vue`
  - 依据 `ParamSpec.type` 渲染 string/text/number/integer/boolean/select/file/json/array/object。
  - 初始化 defaultValue。
  - 暴露 `validate()` 给父组件。
- `src/utils/validation.ts`
  - 覆盖 required、number/integer、min/max、json 解析、maxLength。
- `src/api/client.ts`
  - `buildTargetUrl(...)` 处理 path 与 query 参数。
  - `buildJsonBody(...)` 只处理 `in: body`。
  - `buildFormData(...)` 只处理 `in: form-data`。
  - `assembleRequest(...)` 为 JSON/SSE 注入 `Content-Type`，为 multipart 生成 `FormData`，为 SSE 加 `Accept: text/event-stream`。
  - 当前未处理 `in: header`。
  - 当前 `requestKind === 'sse'` 只能表达 JSON/无 body SSE，不能表达 multipart SSE。
- `src/api/sse.ts`
  - 用 fetch + ReadableStream 消费 SSE，避免 EventSource 不能带 `X-Api-Key` 的限制。
  - 已有 `SseParser` 和 `consumeSseStream` 单测。
- `src/utils/gate.ts`
  - 单一执行闸门：flag-off 禁执行、危险能力二次确认、无 API Key 禁执行、scope-required 只提示。
- `src/utils/curl.ts`
  - 生成 curl 预览，API Key 固定为 `$API_KEY` 占位。
  - 当前同样未处理 header 参数。

### 已有专用模块

- `src/modules/chat/ChatConsoleView.vue`
  - 对 `chat.sync` 与 `chat.stream` 提供对话式体验。
  - 对 `chat.extract` 等非对话能力委托 `CapabilityRunner`。
- `src/modules/tasks/AsyncMonitorView.vue`
  - 对 `async.*` 能力使用通用运行器。
  - 额外维护会话内任务时间线。
  - 复用 `runCapability` 和 `streamCapability`。
- `src/modules/tasks/AsyncTaskTimeline.vue`
  - 可作为 Workflow/Agent 专用状态区的参考组件。

### 目录加载与 live discovery

- `src/stores/catalog.ts`
  - `load()` 先拉静态目录，然后 fire-and-forget 调 `refreshLive()`。
  - `refreshLive()` 需要 API Key，否则不拉 live。
- `src/api/catalog.ts`
  - `LIVE_DISCOVERY_ENDPOINTS` 包含 `/agent/capabilities`、`/interop/mcp/tools`、`/channel/capabilities`、`/eval/capabilities`。
  - `mergeLive(...)` 只把已在静态 catalog 中命中的能力标为 `source='live'`，不会生成新能力。

### 网关与部署现状

- `edge-gateway/src/main/resources/application.yml`
  - CORS 已允许 `http://localhost:5173`、`http://localhost:4173`、`http://localhost:8093`。
  - 路由已覆盖 `/workflow/**`、`/vision/**`、`/voice/**`、`/channel/**`、`/interop/**`、`/eval/**`、`/rag/**`、`/chat/**`、`/agent/**`、`/async/**`。
  - `dev-key-acme` scopes 包含 `chat, ingest, approve, agent, channel, eval, vision, voice`。
- `capability-showcase-frontend/vite.config.ts`
  - dev 代理业务前缀到 edge-gateway。
  - 业务前缀已包含 `workflow`、`interop`、`eval`、`vision`、`voice`、`channel`。
- `capability-showcase-frontend/README.md`
  - 明确前端为独立静态 SPA，docker 暴露 8093。

### P1/P2 后端端点事实

#### workflow-service

- 文件：`workflow-service/src/main/java/com/lrj/platform/workflow/controller/WorkflowController.java`
- 类：`WorkflowController`
- 方法与端点：
  - `start(...)`：`POST /workflow/refund/start`，body 是 `Map<String, String>`，字段 `chatId`、`message`、`dedupeId`、`webhookUrl`。
  - `tasks()`：`GET /workflow/tasks`，需要 `approve` scope。
  - `claim(String taskId)`：`POST /workflow/tasks/{taskId}/claim`，需要 `approve` scope，已被他人领取时 409。
  - `unclaim(String taskId)`：`POST /workflow/tasks/{taskId}/unclaim`，需要 `approve` scope。
  - `complete(String taskId, Map<String,Object> body)`：`POST /workflow/tasks/{taskId}/complete`，字段 `approved`、`comment`，需要 `approve` scope，并发双重审批 409。
  - `instance(String instanceId)`：`GET /workflow/instances/{instanceId}`。
  - `purge(String chatId)`：`DELETE /workflow/data?chatId=...`，需要 `approve` scope，破坏性删除。

#### vision-service / voice-service / conversation multimodal / knowledge multimodal

- `vision-service/src/main/java/com/lrj/platform/vision/controller/VisionController.java`
  - `caption(VisionCaptionRequest)`：`POST /vision/caption` 或 `/vision/describe`，JSON，字段 `imageBase64`、`mimeType`、`instruction`。
  - `captionUpload(...)`：同路径 multipart，字段 `file`、`instruction`。
- `platform-protocol/src/main/java/com/lrj/platform/protocol/vision/VisionCaptionRequest.java`
  - record：`VisionCaptionRequest(String imageBase64, String mimeType, String instruction)`。
- `voice-service/src/main/java/com/lrj/platform/voice/VoiceController.java`
  - `chat(...)`：`POST /voice/chat` multipart，字段 `audio`、query `chatId`。
  - `chatStream(...)`：`POST /voice/chat/stream` multipart + `text/event-stream`，字段 `audio`、query `chatId`。
  - `transcribe(...)`：`POST /voice/transcribe` multipart，字段 `audio`。
- `conversation-service/src/main/java/com/lrj/platform/conversation/vision/VisionConversationController.java`
  - `chatVision(...)`：`POST /chat/vision` multipart，字段 `image`、query `message`。
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/MultimodalImageSearchController.java`
  - `ingest(...)`：`POST /rag/image` multipart 字段 `image`，需要 `ingest` scope。
  - `search(...)`：`POST /rag/image-search` JSON，字段 `query`、`topK`、`minScore`。

#### interop-service / eval-service

- `interop-service/src/main/java/com/lrj/platform/interop/InteropController.java`
  - `agentCard()`：`GET /interop/agent-card`。
  - `a2aAgentCard()`：`GET /interop/a2a/agent-card`。
  - `tools()`：`GET /interop/mcp/tools`。
  - `tool(String toolName)`：`GET /interop/mcp/tools/{toolName}`。
  - `call(McpToolCallRequest request)`：`POST /interop/mcp/call`。
- `platform-protocol/src/main/java/com/lrj/platform/protocol/interop/McpToolCallRequest.java`
  - record：`McpToolCallRequest(String tool, Map<String,Object> arguments)`。
- `interop-service/src/main/java/com/lrj/platform/interop/a2a/A2aController.java`
  - `wellKnownAgentCard()`：`GET /.well-known/agent-card.json`。
  - `handle(JsonNode body)`：`POST /interop/a2a`，JSON-RPC；`method == "message/stream"` 返回 SSE。
- `eval-service/src/main/java/com/lrj/platform/eval/EvalController.java`
  - `capabilities()`：`GET /eval/capabilities`。
  - `retrieval(RetrievalRunRequest)`：`POST /eval/retrieval`。
  - `run(EvalRunRequest)`：`POST /eval/run`。
  - `runSuite(String suiteName, EvalSuiteRunRequest)`：`POST /eval/suites/{suiteName}/run`。
  - `dualRun(EvalDualRunRequest)`：`POST /eval/dual-run`。
  - `gate(EvalDualRunRequest)`：`POST /eval/gate`，回归时 422。
- `platform-protocol/src/main/java/com/lrj/platform/protocol/eval/*.java`
  - 已确认 `EvalRunRequest`、`EvalSuiteRunRequest`、`EvalDualRunRequest`、`RetrievalRunRequest`、`EvalCase`、`RetrievalCase` 字段。

#### channel-service

- `channel-service/src/main/java/com/lrj/platform/channel/ChannelController.java`
  - `capabilities()`：`GET /channel/capabilities`。
  - `send(ChannelMessageRequest)`：`POST /channel/messages`。
  - `callback(ChannelCallbackRequest)`：`POST /channel/callbacks`。
  - `asyncTaskCallback(Map payload, headers...)`：`POST /channel/callbacks/async-task`。
  - `workflowCallback(Map payload, headers...)`：`POST /channel/callbacks/workflow`。
  - `inbound(ChannelInboundEvent, X-Channel-Signature)`：`POST /channel/inbound`。
- `platform-protocol/src/main/java/com/lrj/platform/protocol/channel/ChannelMessageRequest.java`
  - record：`channel`、`target`、`message`、`metadata`。
- `platform-protocol/src/main/java/com/lrj/platform/protocol/channel/ChannelCallbackRequest.java`
  - record：`source`、`sourceId`、`status`、`channel`、`target`、`message`、`metadata`。
- `platform-protocol/src/main/java/com/lrj/platform/protocol/channel/ChannelInboundEvent.java`
  - record：`eventId`、`channel`、`source`、`eventType`、`payload`、`receivedAt`。

### P0 高级能力事实

- `agent-service/src/main/java/com/lrj/platform/agent/dag/AgentDagController.java`
  - `/agent/dag/run`、`/agent/dag/plan-run`、`/agent/dag/run/async`、`/agent/dag/plan-run/async`。
- `agent-service/src/main/java/com/lrj/platform/agent/chaining/ChainController.java`
  - `/agent/chain`，body `ChainRunRequest(input)`。
- `agent-service/src/main/java/com/lrj/platform/agent/voting/VotingController.java`
  - `/agent/vote`，body `VoteRequest(question,n)`。
- `agent-service/src/main/java/com/lrj/platform/agent/reflexion/ReflexionController.java`
  - `/agent/reflexive`、`/agent/reflexive/stream`，body `ReflexionRequest(question)`。
- `agent-service/src/main/java/com/lrj/platform/agent/analyst/DataAnalystController.java`
  - `/agent/analyst/run`、`/agent/analyst/run/async`。
- `agent-service/src/main/java/com/lrj/platform/agent/process/ProcessController.java`
  - `/agent/process/run`、`/agent/process/run/async`，需要 `app.agent.workflow.enabled=true`。
- `conversation-service/src/main/java/com/lrj/platform/conversation/routing/ChatAutoController.java`
  - `/chat/auto`。
- `conversation-service/src/main/java/com/lrj/platform/conversation/cascade/CascadeController.java`
  - `/chat/cascade`，需要 `app.chat.cascade.enabled=true`。
- `conversation-service/src/main/java/com/lrj/platform/conversation/mcp/ChatMcpController.java`
  - `/chat/mcp`。
- `conversation-service/src/main/java/com/lrj/platform/conversation/memory/profile/MemoryProfileController.java`
  - `/chat/memory`、`GET /memory/profile`、`DELETE /memory/profile`。
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/graph/GraphController.java`
  - `/rag/graph/query`、`/rag/graph/entities`。
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/ObsidianController.java`
  - `/rag/obsidian/import`。

### 当前测试覆盖

- `src/api/sse.test.ts`：SSE 解析与消费。
- `src/components/form/DynamicForm.test.ts`：表单控件、校验、URL/JSON/FormData 组装。
- `src/stores/catalog.test.ts`：静态目录加载、live 合并、失败回退。
- `src/utils/gate.test.ts`：执行闸门。
- `src/utils/redact.test.ts`：API Key 脱敏。

### 可复用代码

- 通用表单：`DynamicForm.vue`、字段组件、`validateParams`。
- 通用请求：`runCapability`、`streamCapability`、`assembleRequest`。
- 通用展示：`CapabilityCard`、`CapabilityHeader`、`ResponseViewer`、`SseConsole`、五态 badge。
- 专用状态组件参考：`AsyncTaskTimeline.vue`。
- catalog 机制：`capabilities.yml`、`gen-catalog.mjs`、`fetchCatalog`、`mergeLive`。

### 受影响文件清单

未来实施预计只修改/新增以下前端文件：

- `capability-showcase-frontend/capabilities.yml`
- `capability-showcase-frontend/public/catalog.json`（生成产物，是否提交按仓库当前策略确认）
- `capability-showcase-frontend/src/types/catalog.ts`
- `capability-showcase-frontend/src/api/client.ts`
- `capability-showcase-frontend/src/api/sse.ts`
- `capability-showcase-frontend/src/utils/curl.ts`
- `capability-showcase-frontend/src/utils/validation.ts`
- `capability-showcase-frontend/src/modules/ModuleHost.vue`
- `capability-showcase-frontend/src/modules/GenericModuleView.vue`
- `capability-showcase-frontend/src/modules/chat/ChatConsoleView.vue`
- `capability-showcase-frontend/src/modules/tasks/AsyncMonitorView.vue`
- 计划新增：`capability-showcase-frontend/src/modules/workflow/WorkflowDeskView.vue`
- 计划新增：`capability-showcase-frontend/src/modules/multimodal/MultimodalConsoleView.vue`
- 计划新增：`capability-showcase-frontend/src/modules/interop/InteropEvalView.vue`
- 计划新增：`capability-showcase-frontend/src/modules/channel/ChannelConsoleView.vue`
- 计划新增或扩展测试：
  - `capability-showcase-frontend/src/api/client.test.ts`
  - `capability-showcase-frontend/src/utils/curl.test.ts`
  - `capability-showcase-frontend/src/stores/catalog.test.ts`
  - `capability-showcase-frontend/src/modules/**/*.test.ts`

不应修改：

- 各业务服务 controller、DTO、数据库、消息结构。
- `edge-gateway` 路由和 CORS，除非实施过程中发现前端路径未在 dev proxy 或网关路由覆盖。当前已覆盖目标业务前缀。
