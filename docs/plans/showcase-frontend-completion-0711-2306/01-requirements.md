# 01 Requirements

## requirements-analyst 视角

### 背景

当前仓库已经存在独立前端工程 `capability-showcase-frontend/`，技术栈为 Vue 3 + TypeScript + Vite + Pinia + vue-router。它通过 `capabilities.yml` 生成静态 `public/catalog.json`，浏览器以 direct mode 带用户输入的 `X-Api-Key` 调用 `edge-gateway`。

现状与任务差距：

- P0 模块 Chat/RAG/Agent/Async/Analytics 已有骨干能力表单和通用运行器。
- P1/P2 模块 `workflow`、`multimodal`、`interop-eval`、`channel` 在 `capabilities.yml` 中 `capabilities: []`，页面只显示占位。
- P0 模块的高级能力已经在后端存在不少真实端点，但前端目录未补齐表单，例如 `/agent/dag/run`、`/agent/chain`、`/agent/vote`、`/agent/reflexive`、`/chat/auto`、`/chat/mcp`、`/chat/vision`、`/memory/profile`、`/rag/graph/query`、`/rag/image-search` 等。
- 本次只做分析与规划，不修改业务代码；规划文档只写入本目录。

### 目标

- 补齐 P1/P2 四个模块的可交互能力，而不是继续只展示 catalog 占位。
- 补齐 P0 模块中 flag-off / scope-required / destructive 高级能力的表单、运行规则和状态提示。
- 继续复用现有 `CapabilityRunner`、`DynamicForm`、`runCapability`、`streamCapability`、`executionGate`，避免重复发明请求装配逻辑。
- 对确实需要专用体验的能力设计专用模块视图，例如 Workflow 任务列表与审批状态、Voice 音频流、Channel 副作用二次确认。
- 所有新增能力必须基于真实 controller、DTO 或已读到的 `Map` 入参，不虚构接口。

### 已确认业务规则

- 所有业务调用经 `edge-gateway`，浏览器请求必须携带 `X-Api-Key`，目录加载无需 API Key。
- `capability-showcase-frontend/src/stores/session.ts` 明确 API Key 仅在内存保存，不写 URL、localStorage 或日志。
- `executionGate` 的规则是：
  - `flag-off` 当前禁止执行，只提示需开启 `featureFlag`。
  - `executableByDefault=false` 的危险能力需要二次确认。
  - 无 API Key 禁止执行。
  - `scope-required` 允许尝试，但提示缺 scope 会 403。
- Workflow：
  - `WorkflowController` 仅在 `app.workflow.enabled=true` 注册。
  - `start(...)` 普通合法 key 可发起退款流程。
  - `tasks/claim/unclaim/complete/purge` 需要 `approve` scope。
  - `DELETE /workflow/data?chatId=...` 是破坏性合规删除，应默认锁定。
  - `dedupeId` 按 controller 注释用于幂等去重。
- Multimodal：
  - `VisionController` 仅在 `app.vision.enabled=true` 注册，支持 JSON base64 与 multipart 文件上传。
  - `VoiceController` 仅在 `app.voice.enabled=true` 注册，支持 `/voice/chat`、`/voice/chat/stream`、`/voice/transcribe`，multipart 字段名为 `audio`。
  - `VisionConversationController` 的 `/chat/vision` 由 conversation 侧转发 vision，未启用时返回明确错误。
  - `MultimodalImageSearchController` 仅在 `app.rag.multimodal-embedding.enabled=true` 注册，图片入库需 `ingest` scope。
- Interop/Eval：
  - `InteropController` 提供 `/interop/agent-card`、`/interop/a2a/agent-card`、`/interop/mcp/tools`、`/interop/mcp/tools/{toolName}`、`/interop/mcp/call`。
  - `A2aController` 的 `POST /interop/a2a` 是 JSON-RPC 入口，`message/stream` 返回 SSE。
  - `EvalController` 提供 capabilities、retrieval、run、suite run、dual-run、gate；`/eval/gate` 失败时可能返回 HTTP 422。
- Channel：
  - `ChannelController.capabilities()` 常开。
  - `/channel/messages`、`/channel/callbacks`、`/channel/callbacks/async-task`、`/channel/callbacks/workflow`、`/channel/inbound` 都是真实端点。
  - `/channel/messages` 有真实外部副作用，必须强二次确认。
  - `/channel/inbound` 需要 `X-Channel-Signature`，当前 `ParamIn` 支持 `header`，但请求装配代码尚未把 header 参数注入 fetch header；这点必须先补通用能力，否则此类表单不能真实工作。

### 边界条件

- `requestKind` 当前只有 `json`、`multipart`、`sse`、`none`，但 `voice/chat/stream` 是 multipart + SSE。当前 `assembleRequest` 对 `requestKind === 'sse'` 按 JSON 组包，无法表达 multipart SSE。需要扩展 schema 或新增前端专用处理。
- 当前 `ParamIn` 类型包含 `header`，但 `buildTargetUrl`、`buildJsonBody`、`buildFormData` 和 `assembleRequest` 未处理 header 参数。Channel inbound 若需要签名头，必须补 header 注入。
- `DynamicForm` 对 `array/object/json` 都用 JSON 文本框，复杂数组如 `EvalRunRequest.cases`、`AgentDagRunRequest.tasks` 可以直接用 JSON 字段，短期不必做嵌套表单。
- `CapabilityRunner` 的通用响应区适合大多数 JSON/SSE；但工作流、语音、渠道这种有状态或有强副作用能力需要专用视图提升可用性与安全性。
- `featureFlagDefault=false` 不等于一定不可用，实际运行环境可能已开启；但当前前端没有配置探测能力，只能按 manifest 保守显示。
- `live discovery` 只会把静态清单中命中的能力标为 `source='live'`，不会自动生成新表单。补齐能力仍需维护 `capabilities.yml` 或引入更强的 schema 生成。

### 非目标

- 不新增后端接口、数据库表、消息结构。
- 不修改业务 controller、DTO、服务逻辑。
- 不实现完整生产级运营后台，不保存用户运行历史。
- 不在浏览器持久化 API Key。
- 不把所有高级能力都做成专用复杂 UI；能由通用运行器可靠覆盖的先走 manifest 表单。
- 不承诺 flag-off 后端未开启时可执行成功。

### 歧义与待验证

- 待验证：用户期望“补齐前端能力”是否包含实际录音采集控件；当前可先规划文件上传音频，浏览器录音作为增强项。
- 待验证：A2A JSON-RPC 的推荐示例 payload 需要继续阅读 `MessageSendParams` 等类型，本文只确认 controller 入口和 `message/stream` 行为，不细写未核验字段。
- 待验证：Channel 外部真实发送是否应默认只允许 `webhook`，还是可直接暴露 `feishu`、`voice`。规划中按强确认 + 默认示例 webhook 处理。
- 待验证：`RAG_GRAPH_ENABLED` 在 `knowledge-service/application.yml` 默认是 true，但代码 `GraphController` 仍有 `@ConditionalOnProperty(app.rag.graph.enabled=true)`，manifest 状态应按实际部署策略确认，可先标 `ready` 或 `flag-off` 都有理由。
- 待验证：生产静态部署是否仍使用 `VITE_EDGE_BASE_URL` 跨域方式，还是未来改同源路径；前端当前两种都支持。

### 验收标准

- `workflow`、`multimodal`、`interop-eval`、`channel` 四个模块不再显示占位空态，至少展示已核验端点的能力卡片。
- P0 高级能力在 catalog 中有真实路径、方法、参数、scope、flag、risk、state，并能进入表单页。
- 通用运行器支持新增端点所需的请求形态，至少包括 header 参数、multipart SSE 或明确绕过到专用视图。
- 危险/副作用能力必须默认锁定并需要二次确认，curl 预览不得泄露真实 API Key。
- `npm test`、`npm run type-check`、`npm run build` 应通过。
- 新增能力目录可由 `npm run gen:catalog` 生成合法 `public/catalog.json`。
