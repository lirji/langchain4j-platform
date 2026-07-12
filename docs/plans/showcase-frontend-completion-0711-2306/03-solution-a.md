# 03 Solution A

## architecture-designer 视角：Manifest-first 通用运行器增强

### 核心思路

以 `capabilities.yml` 为核心，把所有 P1/P2 与 P0 高级能力都补进静态 manifest。前端仍以 `GenericModuleView + CapabilityRunner + DynamicForm` 为主，只对当前通用运行器不能表达的请求形态做小幅增强。

这是一条最小改动路径：优先让所有真实端点“能看到、能填表、能发请求、能看响应”。

### 架构

- Catalog 层：扩展 `capabilities.yml`，按真实 controller 补齐能力。
- Schema 层：在 `Catalog` 类型中补足请求表达能力，重点解决 `header` 参数与 multipart SSE。
- Request 层：扩展 `assembleRequest`、`toCurl`、测试。
- UI 层：多数模块继续走 `GenericModuleView`；只增加少量状态说明和危险能力文案。

### 模块职责

- `capabilities.yml`
  - 维护所有端点、参数、scope、feature flag、state、risk。
- `DynamicForm.vue`
  - 继续负责基础字段渲染。
- `client.ts`
  - 新增 header 参数注入。
  - 设计 multipart SSE 表达方式。推荐新增 `requestKind: multipart-sse`，或新增字段 `bodyKind: multipart` 与 `stream: true`。前者改动更小但会扩展枚举。
- `sse.ts`
  - 接收由 `assembleRequest` 生成的 multipart SSE plan。
- `curl.ts`
  - curl 预览支持 header 与 multipart SSE。
- `GenericModuleView.vue`
  - 不再显示 P1/P2 占位，因为 catalog 已补能力。

### 核心流程

1. `npm run gen:catalog` 把新增 manifest 写入 `public/catalog.json`。
2. 用户进入 `/m/workflow`，看到能力卡片。
3. 选择 `/workflow/refund/start`，`CapabilityRunner` 根据 params 渲染表单并执行。
4. 对 `/channel/messages`、`DELETE /workflow/data` 等副作用或破坏性能力，`executionGate` 要求二次确认。
5. 对 `/voice/chat/stream`，新增 request kind 走 multipart body + SSE reader。

### 改动范围

- `capability-showcase-frontend/capabilities.yml`：最大改动。
- `src/types/catalog.ts`：可能新增 `RequestKind` 值。
- `src/api/client.ts`、`src/api/sse.ts`、`src/utils/curl.ts`：增强请求装配。
- `src/components/form/DynamicForm.vue`：如需更好的 `header` 字段标识，可只改样式/提示。
- 测试：补 client/curl/catalog schema 测试。

### 扩展性

- 新端点主要通过 manifest 增加。
- 对复杂工作流的用户体验有限，但基础可运行。
- 未来仍可逐步为单个模块增加专用视图，不会推翻 manifest。

### 实施成本

低到中。

- 优点：改动集中、风险低、最快消除占位。
- 成本点：`capabilities.yml` 会变长，复杂 JSON 参数体验一般。

### 已知弱点

- Workflow 审批、任务刷新、实例状态、Channel 副作用等缺少领域化引导，用户需要自己复制 taskId/instanceId。
- Eval 的 `cases`、Agent DAG 的 `tasks` 都是 JSON 文本框，容易填错。
- `flag-off` 仍按静态 state 禁执行，不能体现当前运行环境已开启的情况。
- 如果简单新增 `requestKind: multipart-sse`，会让 schema 更具体，但仍没有解决未来其他混合请求形态。
