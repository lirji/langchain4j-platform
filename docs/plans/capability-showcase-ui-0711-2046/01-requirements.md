# 01 Requirements

## requirements-analyst 视角

### 背景

本仓库是 Java 21 / Spring Boot 3.3.5 多模块微服务平台，根 `pom.xml` 聚合 `conversation-service`、`knowledge-service`、`agent-service`、`analytics-service`、`workflow-service`、`async-task-service`、`channel-service`、`interop-service`、`eval-service`、`vision-service`、`voice-service`、`edge-gateway` 与多个 `platform-*` 共享库。当前没有现成前端工程：仓库未发现 `package.json`、Vite/Next/Angular 配置、`src/main/resources/static`/`public`/`templates` 或 Springdoc/OpenAPI 依赖。

用户目标是新增一个前端页面，用来展示和实际调用本项目提供的能力，并通过页面拆解判断哪些能力适合沉淀为独立前端模块。

### 核心需求

- 展示能力全景：按能力域展示对话、RAG/GraphRAG、多模态、NL2SQL、Agent、工作流、异步任务、渠道、互操作、评测、视觉、语音、平台安全与可观测能力。
- 支持可用能力试用：页面应能经 `edge-gateway` 调用现有业务端点，例如 `/chat`、`/chat/stream`、`/rag/documents`、`/rag/query`、`/agent/run`、`/agent/tasks/{taskId}/stream`、`/analytics/sql`、`/workflow/refund/start`、`/async/tasks`、`/interop/mcp/tools`、`/eval/run`、`/vision/caption`、`/voice/chat`。
- 显示能力边界：每个能力卡应明确端点、所属服务、默认开关、所需 scope、输入类型、响应形态、是否支持 SSE、是否有异步任务、是否需要外部依赖。
- 辅助拆分前端模块：页面信息架构必须能看出哪些模块可独立复用，例如 Chat Console、RAG Workspace、Agent Runner、Task Monitor、Workflow Desk、Analytics Lab、Multimodal Console、Interop/Eval Tools。
- 使用真实仓库事实：能力、接口、类名、方法名、配置键必须来自代码或文档；不确定项标记为“待验证”。

### 业务规则

- 对外业务调用应优先走 `edge-gateway`，`edge-gateway/src/main/resources/application.yml` 已路由 `/chat`、`/rag`、`/agent`、`/async`、`/channel`、`/interop`、`/eval`、`/vision`、`/voice` 等路径。
- 前端不应接触 `X-Internal-Token`；用户输入 `X-Api-Key` 后由 `ApiKeyToInternalTokenFilter` 在网关换发内部 JWT。
- 租户与用户来自 `TenantContext`，请求体不应要求用户手工填写租户字段。
- 部分端点受 scope 约束：`DocumentController.requireIngest()` 要求 `ingest` scope；`WorkflowController.requireApprove()` 要求 `approve` scope。
- 默认关闭能力需要在 UI 上以“未启用/需配置”展示，而不是假定可用：如 `app.chat.cascade.enabled`、`app.conversation.router.enabled`、`app.rag.multimodal-embedding.enabled`、`app.vision.enabled`、`app.voice.enabled`。
- SSE 能力需要独立处理断线、完成事件和错误事件：已有 `/chat/stream`、`/agent/tasks/{taskId}/stream`、`/async/tasks/{taskId}/stream`、`/agent/reflexive/stream`、`/voice/chat/stream`、A2A `message/stream`。
- 上传能力必须区分 JSON 与 multipart：`/rag/documents` 同时支持 JSON 文本与 multipart 文件，`/vision/caption` 同时支持 JSON base64 与 multipart，`/voice/*` 使用 multipart audio。

### 边界条件

- 当前任务只规划，不改业务代码；仅写入 `docs/plans/capability-showcase-ui-0711-2046/`。
- 当前仓库没有前端依赖基线，因此任何 React/Vue/Vite/静态资源服务方案都属于新增技术选择。
- 未找到 OpenAPI 自动文档依赖，不能依赖 `/v3/api-docs` 生成能力目录。
- `edge-gateway` 的限流族识别存在窄口径：`EdgeRateLimitFilter.familyOf()` 对 `/rag/documents` 未归类为 `ingest`，当前会落到 `default`，规划中只能标记为风险，不能假设已经有完善的 UI 调用限流族。
- 现有能力发现端点不完整：`/agent/capabilities`、`/channel/capabilities`、`/eval/capabilities`、`/interop/mcp/tools` 存在，但 conversation、knowledge、analytics、workflow、vision、voice 没有统一 capability endpoint。

### 非目标

- 不重构现有业务能力实现。
- 不新增真实业务数据模型或迁移脚本，除非最终方案明确选择持久化 UI 配置；本规划推荐 MVP 不新增数据库。
- 不替代 `edge-gateway` 鉴权与服务路由。
- 不把展示页做成生产运营控制台；首期定位是能力展示、调用试验与模块拆分验证。
- 不承诺所有默认关闭能力开箱即用；页面应清晰暴露配置前置条件。

### 歧义与易遗漏点

- “前端页面”是单页演示站、可部署应用、还是嵌入现有网关的静态页面，需求未限定。
- “拆分出来独立的前端模块”可能指代码组件复用，也可能指可独立部署的微前端；需在方案中分别覆盖。
- 是否允许用户在页面内保存 API Key、运行历史、预设请求未明确；从安全角度默认仅浏览器会话保存，持久化待验证。
- 是否面向开发者、业务演示人员还是最终客户未明确；首期应按内部开发/方案演示设计，避免把敏感接口暴露给普通客户。
- 视觉/语音能力依赖外部模型和密钥，MVP 验收不能以真实云模型稳定返回为唯一标准。

### 验收标准

- 文档能指导另一个开发 Agent 直接实施，不需要重新梳理仓库能力。
- 至少覆盖代码中已确认的核心端点和默认开关。
- 给出 4 个候选方案，并能解释取舍，不机械选择单一方案。
- 最终方案包含精确到文件 / 类 / 方法的未来修改清单。
- 明确数据库、接口、配置、消息结构是否变更。
- 测试计划覆盖单元、集成、回归、异常、SSE、上传、安全、灰度和回滚。
