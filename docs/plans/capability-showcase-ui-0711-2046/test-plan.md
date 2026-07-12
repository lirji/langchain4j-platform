# Test Plan

## test-designer 视角

### 测试目标

- 验证能力目录与代码中已确认端点一致。
- 验证前端模块能调用同步、异步、SSE、multipart、错误响应等不同类型能力。
- 验证租户、scope、API Key、限流、禁用能力提示不会被 UI 绕过。
- 验证最终方案不影响现有业务服务。

### 单元测试

#### BFF catalog

- `CapabilityCatalogServiceTest`
  - 给定 `capabilities.yml`，返回能力域、模块、端点、scope、feature flag。
  - 缺失可选字段时有明确默认值。
  - 重复 endpoint id 应失败或记录错误。
  - 标记危险端点，如 `DELETE /workflow/data`，默认 `executable=false`。

#### BFF live discovery

- `CapabilityDiscoveryClientTest`
  - mock `/agent/capabilities` 返回 `McpToolDescriptor` 时合并到 catalog。
  - discovery 超时/500 时回退静态 manifest。
  - discovery 返回空列表时不覆盖 last-known-good。

#### Proxy / HTTP client

- `CapabilityProxyControllerTest`
  - 透传 `X-Api-Key`，但日志/响应调试信息不回显完整 key。
  - 非幂等 POST 不自动重试。
  - 后端 400/401/403/409/422/429 原样保留状态语义。
  - 禁止代理任意外部 URL，只允许 catalog 中声明的 path。

#### 前端组件

- Catalog navigation：能力域、模块筛选、启用状态标签。
- Request form：JSON、query params、multipart file/audio/image、SSE 切换。
- Response viewer：JSON tree、headers、status、duration、SSE event log。
- Sensitive input：API Key 默认 password 类型，不持久化到 localStorage。

### 集成测试

#### 网关路由

- 启动 `edge-gateway` + showcase 服务，访问 `/showcase/api/catalog` 成功。
- `/showcase/**` 不覆盖 `/chat`、`/rag`、`/agent` 等已有路由。
- 无 API Key 调业务端点返回 401；带 `dev-key-acme` 可调用 `/chat` 类能力。

#### 现有业务 smoke

- `POST /chat`：返回 `reply/chatId/tenantId/userId`。
- `POST /chat/stream`：收到 token 或 `done/error/blocked` 事件。
- `POST /rag/documents`：
  - `dev-key-acme` 无 `ingest` scope 时应 403。
  - `dev-key-acme-ingest` 成功上传文本。
- `POST /rag/query`：返回 `KnowledgeQueryReply`，`hits` 可为空但结构正确。
- `POST /agent/run`：空 goal 返回 400；有效 goal 返回 `AgentRunReply` 或模型依赖错误，UI 可展示。
- `POST /agent/run/async` + `GET /agent/tasks/{taskId}/stream`：能展示任务生命周期。
- `POST /analytics/sql`：在 `NL2SQL_ENABLED=true` 环境下可用；未启用时 UI 标记 unavailable。
- `POST /workflow/refund/start`：可发起；`/workflow/tasks` 无 `approve` scope 返回 403。
- `GET /interop/mcp/tools`：返回工具列表。
- `GET /eval/capabilities`：返回 eval 能力元数据。

### 回归测试

- 运行现有相关模块测试：
  - `mvn -pl edge-gateway test`
  - `mvn -pl platform-security test`
  - `mvn -pl conversation-service test`
  - `mvn -pl knowledge-service test`
  - `mvn -pl agent-service test`
  - `mvn -pl async-task-service test`
  - `mvn -pl capability-showcase-ui test`（新增后）
- 若修改根聚合，运行 `mvn -pl capability-showcase-ui -am test`。

### 异常场景

- API Key 错误：UI 明确展示 401，不无限重试。
- scope 不足：403 显示所需 scope，例如 `ingest`、`approve`。
- 默认关闭能力：
  - `/chat/cascade` 可能 404（controller 未注册）。
  - `/chat/auto`、`/chat/mcp`、`/chat/vision` 可能返回禁用提示。
  - `/vision/caption`、`/voice/chat` 在服务未启用时可能 404。
- 大文件上传：超过 vision/voice/rag 限制时展示 400 错误。
- SSE 断线：UI 可停止、重连或提示；`/async/tasks/{taskId}/stream` 支持 `Last-Event-ID` 或 `lastEventId`，需验证。
- 后端超时：catalog health 显示 degraded，业务响应区显示超时。

### 并发 / 幂等 / 事务

- 同时打开多个 SSE，不应阻塞 catalog 和普通请求。
- 重复创建 `AsyncTaskCreateRequest.taskId` 返回 409，UI 保留后端冲突信息。
- `WorkflowController.start(...)` 的 `dedupeId` 在页面中作为高级字段暴露，避免渠道重放创建重复流程。
- `WorkflowController.complete(...)` 并发审批冲突由后端返回 409，UI 不做乐观假设。
- BFF 不包装现有事务，不新增两阶段提交。

### 安全测试

- API Key 不进入 URL query。
- API Key 不写入 localStorage、日志、错误详情。
- Proxy path 白名单：不能通过 BFF 访问任意内网 URL。
- `/.well-known/agent-card.json` 可免鉴权；其他业务端点仍需 API Key。
- 删除/清理类能力默认不可执行，需要开发模式显式开关。

### 验收标准

- 新增展示页可通过 `/showcase` 打开。
- 能力目录至少覆盖本分析列出的服务域和核心端点。
- 至少 6 个可独立前端模块完成：Chat、RAG、Agent、Async Task、Workflow、Analytics；Interop/Eval/Multimodal 可作为次级模块。
- 未启用能力不会显示为“可正常使用”。
- 现有服务测试不因展示页新增而失败。
- 回滚展示页后，现有业务 API 不受影响。
