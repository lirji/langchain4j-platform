# Test Plan

## test-designer 视角

### 测试目标

- 证明新增 catalog 能生成、加载、展示。
- 证明通用请求装配支持新增能力所需参数形态：path/query/body/form-data/header/multipart SSE。
- 证明危险能力、flag-off、scope-required 的执行闸门符合规则。
- 证明 P1/P2 模块不再是占位空态。
- 证明专用视图只复用公共 API 客户端，不绕过 API Key 规则。

### 单元测试

#### catalog 生成与合并

文件：

- `capability-showcase-frontend/src/stores/catalog.test.ts`
- 可新增 `capability-showcase-frontend/src/api/catalog-schema.test.ts`

用例：

- 静态 catalog 包含 `workflow`、`multimodal`、`interop-eval`、`channel`，且每个模块 `capabilities.length > 0`。
- 新增 P0 高级能力可通过 `capabilityById(...)` 找到。
- `mergeLive(...)` 仍只标记已存在能力，不生成未知表单。
- `featureFlag`、`riskLevel`、`state` 字段完整。

验收：

- `workflow.refund.start`、`voice.transcribe`、`interop.mcp.tools`、`eval.retrieval`、`channel.messages.send` 等代表能力均可查到。

#### 请求装配

文件：

- 当前 `DynamicForm.test.ts` 已覆盖一部分 `buildTargetUrl`、`buildJsonBody`、`buildFormData`。
- 建议新增 `capability-showcase-frontend/src/api/client.test.ts`。

用例：

- `in: header` 参数写入 fetch headers，但不能覆盖 `X-Api-Key`。
- multipart capability 同时支持 form-data 文件和 query 参数，例如 `/voice/chat?chatId=...`。
- multipart SSE capability 生成 `FormData` body 且 `Accept: text/event-stream`。
- JSON SSE capability 继续生成 JSON body，不回归 `/chat/stream`。
- `none` request 不带 body。

验收：

- `/channel/inbound` 可生成 `X-Channel-Signature`。
- `/voice/chat/stream` 可生成 multipart + SSE request plan。

#### curl 预览

文件：

- 建议新增 `capability-showcase-frontend/src/utils/curl.test.ts`。

用例：

- 真实 API Key 不出现在 curl。
- header 参数显示为 `-H 'X-Channel-Signature: ...'`。
- multipart SSE 显示 `-F` 与 `-N`。
- path/query/body 组合正确。

验收：

- `toCurl` 输出只包含 `$API_KEY`，不包含 session key。

#### 执行闸门

文件：

- `capability-showcase-frontend/src/utils/gate.test.ts`

新增用例：

- `channel.messages.send` 在 `executableByDefault=false` 未确认时禁止执行。
- `workflow.data.purge` 在二次确认后允许执行，但仍需要 API Key。
- `flag-off` 的 `voice.chat` 或 `vision.caption` 在静态 state 下禁止执行并显示 feature flag。

验收：

- 所有风险规则由 `executionGate` 单点决定，不在专用视图里复制分叉逻辑。

### 组件测试

#### GenericModuleView

用例：

- 当 P1/P2 模块有能力时展示卡片，不显示“能力待补”。
- 选择 capability id 后渲染 `CapabilityRunner`。

#### WorkflowDeskView

计划新增测试：

- 渲染发起退款、任务列表、实例查询、危险清理区域。
- start 成功返回后，本地 tracked list 增加一条记录。字段名需实施时按真实 `StartResult` 确认；如果字段不存在，测试只验证 response viewer 展示成功。
- claim/complete 409 时展示错误，不吞掉。

#### MultimodalConsoleView

计划新增测试：

- 图像文件上传能力显示文件名和 mime。
- audio 文件能力使用字段 `audio`。
- stream 按钮调用 `streamCapability`，离开组件时 abort。

#### InteropEvalView

计划新增测试：

- MCP tools list 成功后可选择 toolName 查询详情。
- eval gate 收到 422 时作为业务结果展示，而不是前端崩溃。当前 `runCapability` 对非 2xx 抛 `ApiError`，实施时可在 eval gate 专用视图区分 422 并展示响应体。

#### ChannelConsoleView

计划新增测试：

- `/channel/messages` 默认锁定。
- 二次确认后才调用 `runCapability`。
- inbound 表单包含 header 签名字段。

### 集成/回归测试

命令：

```bash
cd capability-showcase-frontend
npm run gen:catalog
npm test
npm run type-check
npm run build
```

建议手工 smoke：

- 启动后端平台与前端 dev server。
- 用 `dev-key-acme` 测试：
  - `/m/workflow` 发起 refund start。
  - `/m/workflow` 拉 tasks，如果无任务也应返回空数组或 403 以外的合理结果。
  - `/m/multimodal` 调用 `/vision/caption`，若 flag-off 或服务未启用，UI 应展示禁执行或后端错误。
  - `/m/interop-eval` 调用 `/interop/mcp/tools`。
  - `/m/channel` 调用 `/channel/capabilities`，不直接发送真实消息。
- 用 `dev-key-globex` 测试 scope 不足：
  - RAG ingest 返回 403 并被 `humanizeError` 翻译。
  - Workflow approve 操作返回 403。

### 异常场景

- 后端端点未注册：flag-off 能力不允许执行；若用户通过 curl 手动请求 404，文档解释这是预期。
- JSON 字段非法：表单校验阻止提交。
- 文件为空或过大：后端 400，前端展示错误体或 `X-Error`。当前 `readBody` 不读取 `X-Error`，可考虑在 `ApiError` 展示中附带响应 header，待实施评估。
- SSE 中断：`onDone('abort')` 后 UI 显示已中断。
- Eval gate 422：作为门禁失败结果展示，不能只显示“HTTP 422”而丢掉响应体。
- Workflow 409：显示并建议刷新，不自动重试。
- Channel outbound 网络失败：显示错误，不自动重试。

### 最终验收标准

- `capabilities.yml` 中 P1/P2 四模块均至少有 3 个已核验能力；Workflow 与 Channel 必须覆盖危险能力但默认锁定。
- P0 高级能力至少覆盖：
  - Chat：auto、cascade、mcp、memory profile、chat/vision、cache clear。
  - RAG：graph query/entities、obsidian import、image ingest/search。
  - Agent：dag、plan-run、chain、vote、reflexive、analyst、process。
  - Analytics：保持 schema 与 nl2sql。
- 所有新增能力能打开表单页，无控制台异常。
- 测试命令全部通过。
- 文档列明所有待验证接口细节，不把未确认字段写成事实。
