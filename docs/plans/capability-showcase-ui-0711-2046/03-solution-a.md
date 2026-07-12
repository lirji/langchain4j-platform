# 03 Solution A - Static SPA + Static Capability Manifest

## architecture-designer 视角

### 方案定位

新增一个纯前端单页应用，以静态 `capabilities.json/yml` 描述能力目录，所有业务调用直接经 `edge-gateway` 发起。后端业务服务零改动，适合作为最快速的内部展示页。

### 架构

```
Browser SPA
  ├─ static capability manifest
  ├─ API key session input
  ├─ fetch/EventSource/multipart clients
  └─ direct calls to http://localhost:8080
        └─ edge-gateway
              └─ existing services
```

### 模块职责

- Capability Catalog：静态列出服务、端点、请求模板、默认开关、scope、示例输入。
- Playground Shell：统一 API Key、baseUrl、租户响应展示、trace/rate limit 头展示。
- Feature Modules：
  - Chat Console：`/chat`、`/chat/stream`、`/chat/auto`、`/chat/cascade`。
  - RAG Workspace：文档上传、文档列表、`/rag/query`、GraphRAG、图片检索。
  - Agent Lab：`/agent/run`、DAG、chain、vote、reflexion、async task stream。
  - Analytics Lab：`/analytics/sql`、schema tables。
  - Workflow Desk：refund start、tasks、claim/complete、instance。
  - Async Monitor：`/async/tasks`、lease/update/status stream。
  - Multimodal：`/chat/vision`、`/vision/caption`、`/voice/chat`。
  - Interop/Eval：`/interop/mcp/tools`、`/interop/mcp/call`、`/eval/*`。

### 核心流程

1. 用户打开静态页面。
2. 输入 `edge-gateway` base URL 与 `X-Api-Key`。
3. 页面读取静态 manifest，渲染能力卡与模块入口。
4. 用户选择能力，页面生成请求表单。
5. 页面直接调用网关，展示响应、错误、SSE 事件和耗时。

### 改动范围

- 新增前端目录，例如 `capability-showcase-ui/`。
- 根 `pom.xml` 可不改，除非希望 Maven 打包静态资源。
- `deploy/docker-compose.yml` 可不改，除非提供 Nginx/静态服务容器。

### 扩展性

- 前端模块拆分非常直观，适合验证模块边界。
- 但能力目录完全手写，容易与 controller/config 漂移。
- 没有后端代理，浏览器直接持有 API Key。

## risk-reviewer 视角

- 兼容性：不改后端，兼容风险最低。
- 事务：不引入事务；工作流/异步任务仍使用现有后端事务。
- 并发：SSE 多连接由浏览器直接连网关，页面需要限制并发流数量。
- 幂等：上传、workflow start、async create 仍依赖已有 `dedupeId/taskId`；UI 必须提示可重复提交风险。
- 性能：静态资源轻量；大文件上传直接打到网关。
- 安全：API Key 存在浏览器内存/本地配置风险；不建议持久化到 localStorage。
- 数据迁移：无。
- 灰度：可独立发布静态资源，不影响后端。
- 回滚：删除静态页面即可。

### 失败场景

- manifest 写错端点或默认开关，页面展示与真实服务不一致。
- 浏览器跨域：若静态页面不是由网关同源提供，需 CORS；当前代码未看到 CORS 全局配置，待验证。
- 默认关闭能力会返回 404 或禁用提示，需 UI 能解释。

### 实施成本

低。适合 1-3 天做内部原型，但不适合作为长期能力平台。
