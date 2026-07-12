# 02 Codebase Analysis

## codebase-explorer 视角

### 项目结构事实

- 根 `pom.xml`：Spring Boot `3.3.5`、Java `21`、多模块 Maven 聚合。
- 共享库：`platform-security`、`platform-observability`、`platform-gateway-client`、`platform-protocol`、`platform-audit`、`platform-metering`、`platform-eventbus`。
- 业务服务：`conversation-service`、`knowledge-service`、`agent-service`、`analytics-service`、`workflow-service`、`async-task-service`、`channel-service`、`interop-service`、`eval-service`、`vision-service`、`voice-service`。
- 边缘入口：`edge-gateway`，`edge-gateway/src/main/resources/application.yml` 已配置服务路由。
- 前端现状：未发现 `package.json`、Vite/Next/Angular 配置、Spring 静态资源目录、OpenAPI/Springdoc 依赖。

### 鉴权与调用链

- `edge-gateway/src/main/java/com/lrj/platform/edge/ApiKeyToInternalTokenFilter.java`
  - `filter(...)` 读取外部 `X-Api-Key`，按 `InternalSecurityProperties.apiKeys` 找到租户、用户、scope，调用 `InternalToken.mint(...)` 生成内部 JWT，并移除外部 API Key。
  - `isOpen(...)` 放行 actuator、`/.well-known`、飞书/钉钉回调和 `/health`。
- `edge-gateway/src/main/java/com/lrj/platform/edge/EdgeRateLimitFilter.java`
  - `filter(...)` 从内部 JWT 还原租户，按 `(tenant, endpoint family)` 限流。
  - `familyOf(...)` 已识别 `stream`、`eval`、`chat` 等；`/rag/documents` 当前不会命中 `ingest`，待未来评估。
- `platform-security/src/main/java/com/lrj/platform/security/InternalTokenAuthFilter.java`
  - 下游服务验证 `X-Internal-Token`，绑定 `TenantContext` 与 MDC。
  - 本地调试可选 `allow-api-key-fallback`。

### 能力端点清单

#### conversation-service

- `ConversationController.chat(...)`：`POST /chat`，body `{"message": "...", "category": "..."}`，返回 `reply/chatId/tenantId/userId`；包含 guardrail、RAG prompt、semantic cache、history-aware query、grounding。
- `ConversationController.invalidateCache(...)`：`DELETE /chat/cache`，按租户清语义缓存。
- `StreamingConversationController.chatStream(...)`：`POST /chat/stream`，SSE token 流，事件含默认 token、`done`、`error`、`blocked`、`grounding-warning`。
- `ChatAutoController.chatAuto(...)`：`POST /chat/auto`，未启用 router 时返回错误提示。
- `CascadeController.cascade(...)`：`POST /chat/cascade`，仅 `app.chat.cascade.enabled=true` 注册。
- `VisionConversationController.chatVision(...)`：`POST /chat/vision` multipart `image` + `message`。
- `ChatMcpController.chatMcp(...)`：`POST /chat/mcp`，未启用 MCP 时返回错误提示。
- `ExtractController.extract(...)`：`POST /extract?type=ticket`，当前注册 `ticket`。
- `MemoryProfileController.chatWithMemory(...)`、`profile(...)`、`clearProfile(...)`：`/chat/memory`、`/memory/profile`。

#### knowledge-service

- `DocumentController.uploadFile(...)`：`POST /rag/documents` multipart `file`，要求 `ingest` scope。
- `DocumentController.uploadJson(...)`：`POST /rag/documents` JSON `title/text/category` 或 `imageBase64`，要求 `ingest` scope。
- `DocumentController.list()/get()/delete(...)`：`GET /rag/documents`、`GET/DELETE /rag/documents/{docId}`。
- `KnowledgeQueryController.query(...)`：`POST /rag/query` 与 `/knowledge/query`，入参 `KnowledgeQueryRequest(query, topK, minScore, category)`，返回 `KnowledgeQueryReply(query, tenantId, hits)`。
- `MultimodalImageSearchController.ingest(...)`：`POST /rag/image`，仅 `app.rag.multimodal-embedding.enabled=true` 注册，要求 `ingest` scope。
- `MultimodalImageSearchController.search(...)`：`POST /rag/image-search`。
- `GraphController.query()/entities(...)`：`POST /rag/graph/query`、`GET /rag/graph/entities`，仅 `app.rag.graph.enabled=true` 注册。
- `ObsidianController`：`/rag/obsidian/import`，multipart 或普通 post 导入。

#### agent-service

- `AgentController.run(...)`：`POST /agent/run`，入参 `AgentRunRequest(goal, webhookUrl)`。
- `AgentTaskController.runAsync/listMine/get/cancel/stream(...)`：`/agent/run/async`、`/agent/tasks/**`，含 SSE。
- `AgentDagController.run/planAndRun/runAsync/planAndRunAsync(...)`：`/agent/dag/run`、`/agent/dag/plan-run` 及 async 变体。
- `ChainController.chain(...)`：`POST /agent/chain`，若 `app.agent.chaining.steps` 为空返回 400。
- `VotingController.vote(...)`：`POST /agent/vote`。
- `ReflexionController.reflexive/reflexiveStream(...)`：`/agent/reflexive`、`/agent/reflexive/stream`。
- `DataAnalystController`：`/agent/analyst/run`、`/agent/analyst/run/async`。
- `ProcessController`：`/agent/process/run`、`/agent/process/run/async`，仅 workflow 能力开启时注册。
- `AgentCapabilitiesController.capabilities()`：`GET /agent/capabilities`，返回部分 `McpToolDescriptor` 能力。

#### analytics-service

- `AnalyticsController.chatSql(...)`：`POST /chat/sql` 与 `/analytics/sql`，仅 `app.nl2sql.enabled=true` 注册。
- `AnalyticsSchemaController.tables()/describe(...)`：`GET /analytics/schema/tables`、`GET /analytics/schema/tables/{table}`。
- DTO：`AnalyticsSqlRequest`、`AnalyticsSqlReply`、`AnalyticsTablesReply`、`AnalyticsTableSchemaReply`。

#### workflow-service

- `WorkflowController.start(...)`：`POST /workflow/refund/start`，可传 `chatId/message/dedupeId/webhookUrl`。
- `WorkflowController.tasks()/claim()/unclaim()/complete(...)`：审批待办与认领/完成，需要 `approve` scope。
- `WorkflowController.instance(...)`：`GET /workflow/instances/{instanceId}`。
- `WorkflowController.purge(...)`：`DELETE /workflow/data?chatId=...`，需要 `approve` scope，属危险能力，展示页默认不应提供一键执行。
- DTO：`WorkflowStartReply`、`WorkflowTaskView`、`WorkflowInstanceReply`。

#### async-task-service

- `AsyncTaskController.create(...)`：`POST /async/tasks`，入参 `AsyncTaskCreateRequest`，自带重复 `taskId` 409。
- `listMine/get/updateStatus/lease/cancel/stream(...)`：`/async/tasks/**`，含租户隔离、lease 冲突、终态幂等、SSE 断点续订。
- `deadWebhookOutbox(...)`：`GET /async/webhook-outbox/dead`。
- DTO：`AsyncTask`、`AsyncTaskStatus`、`AsyncTaskCreateRequest`、`AsyncTaskStatusUpdateRequest`、`AsyncTaskLeaseRequest`。

#### channel-service

- `ChannelController.capabilities()`：`GET /channel/capabilities`。
- `send/callback/asyncTaskCallback/workflowCallback/inbound(...)`：`/channel/messages`、`/channel/callbacks/**`、`/channel/inbound`。
- `FeishuInboundController`、`DingtalkInboundController`：公开回调路径由网关放行，依赖渠道签名。

#### interop-service

- `InteropController.agentCard()/a2aAgentCard()`：`GET /interop/agent-card`、`GET /interop/a2a/agent-card`。
- `InteropController.tools()/tool()/call(...)`：`/interop/mcp/tools`、`/interop/mcp/call`。
- `A2aController.wellKnownAgentCard()`：`GET /.well-known/agent-card.json`。
- `A2aController.handle(...)`：`POST /interop/a2a`，JSON-RPC，`message/stream` 返回 SSE。
- `InteropToolRegistry` 当前有 `platform.ping` 与静态/动态 agent 工具发现。

#### eval-service

- `EvalController.capabilities()`：`GET /eval/capabilities`。
- `retrieval/run/runSuite/dualRun/gate(...)`：`/eval/retrieval`、`/eval/run`、`/eval/suites/{suiteName}/run`、`/eval/dual-run`、`/eval/gate`。

#### vision-service / voice-service

- `VisionController.caption(...)`：`POST /vision/caption` 或 `/vision/describe`，JSON `VisionCaptionRequest(imageBase64, mimeType, instruction)`，仅 `app.vision.enabled=true`。
- `VisionController.captionUpload(...)`：同路径 multipart `file` + `instruction`。
- `VoiceController.chat/chatStream/transcribe(...)`：`/voice/chat`、`/voice/chat/stream`、`/voice/transcribe`，仅 `app.voice.enabled=true`。

### 数据模型与持久化

- 本次 UI 展示 MVP 不需要新增数据库。
- 已有自动建表/持久化点：
  - `async-task-service/src/main/java/com/lrj/platform/asynctask/JdbcAsyncTaskStore.java`：`ASYNC_TASK`。
  - `AsyncTaskWebhookOutbox.java`：`ASYNC_TASK_WEBHOOK_OUTBOX`。
  - `AsyncTaskLifecycleOutbox.java`：`ASYNC_TASK_LIFECYCLE_OUTBOX`。
  - `workflow-service/src/main/java/com/lrj/platform/workflow/JdbcWorkflowReplyStore.java`：`WF_REPLY`。
  - `WorkflowOutbox.java`：`WF_OUTBOX`。
  - `WorkflowTerminalEventOutbox.java`：`WF_TERMINAL_EVENT_OUTBOX`。
  - `knowledge-service/src/main/java/com/lrj/platform/knowledge/graph/JdbcGraphStore.java`：`RAG_GRAPH_TRIPLE`。
  - `knowledge-service/src/main/java/com/lrj/platform/knowledge/store/doris/DorisEmbeddingStore.java`：Doris 向量表。

### 可复用代码

- `platform-protocol`：已有跨服务 DTO，可直接作为能力示例的请求/响应依据。
- `platform-security`：已有 API Key / 内部 JWT / 租户传播，不应由 UI 绕过。
- `platform-observability`：traceId 透传，可在 UI 请求响应中显示 `X-Trace-Id`（待验证响应头是否对浏览器可见）。
- `interop-service` 的 `McpToolDescriptor`：可复用为能力目录 schema 的参考，但它偏 MCP 工具，不覆盖全部 REST 能力。
- `eval-service` 可作为回归验证工具，最终 UI 可调用 `/eval/run` 演示端到端能力。

### 受影响文件范围（未来实施）

最小独立 UI/BFF 方案预计新增或修改：

- 根 `pom.xml`：新增模块。
- 新模块 `capability-showcase-ui/pom.xml`。
- 新模块后端：`CapabilityShowcaseApplication`、`CapabilityCatalogController`、`CapabilityProxyController`、`CapabilityCatalogProperties`、`CapabilityCatalogService`、`CapabilityHttpClient`、`ShowcaseSecurityConfig`（类名为建议，实施时可调整）。
- 新模块资源：`capabilities.yml` 或 `application.yml`，静态前端产物目录。
- `edge-gateway/src/main/resources/application.yml`：新增 `/showcase/**` 或 `/capabilities/**` 路由（若前端/BFF 独立服务经网关访问）。
- `deploy/docker-compose.yml`：新增服务并设置 `SHOWCASE_*`、`EDGE_BASE_URL` 等环境变量。
- 文档：`README.md`、`docs/参考/capabilities.md` 或新增 `docs/能力展示-ui.md`（最终是否改由实施任务决定）。

避免首期触碰：

- 各业务 controller 的方法签名。
- 现有 `platform-protocol` DTO。
- 已有数据库表。
- 事件消息结构 `platform-protocol/src/main/java/com/lrj/platform/protocol/event/*`。
