# 04 Solution B

## architecture-designer 视角：Catalog + 领域专用工作台

### 核心思路

以 manifest 补齐为基础，但为 P1/P2 和部分 P0 高级能力增加专用模块视图。通用运行器继续承接单次请求，专用视图负责“串联动作、状态追踪、风险控制、结果摘要”。

这是更符合“能力展示前端”的产品化路径：不是只把 REST 端点表单化，而是给关键业务域一个可理解的工作台。

### 架构

- Catalog 层：补全所有能力，继续作为能力和路由事实源。
- Shared Runtime 层：增强 header、multipart SSE、curl、validation。
- Domain Views 层：
  - `WorkflowDeskView`
  - `MultimodalConsoleView`
  - `InteropEvalView`
  - `ChannelConsoleView`
  - 可选增强 `ChatConsoleView`、`AsyncMonitorView`
- ModuleHost 层：把这些模块注册到 `SPECIALIZED`。

### 模块职责

- `WorkflowDeskView.vue`
  - 左侧或顶部展示 workflow 能力卡片。
  - 快捷表单发起退款：`POST /workflow/refund/start`。
  - 记录返回的 `instanceId`（字段名来自 `WorkflowService.StartResult`，需实施时读取 record 确认，本文标待验证）。
  - 支持查询待审任务、认领、取消认领、完成审批、查询实例。
  - `DELETE /workflow/data` 独立放在危险区，二次确认。
- `MultimodalConsoleView.vue`
  - 图像：vision caption JSON/base64 与 multipart 上传；chat/vision；rag/image ingest；rag/image-search。
  - 语音：audio 文件上传 transcribe/chat；stream 使用 multipart SSE。
  - 文件选择后显示文件名、大小、mime，避免误传。
- `InteropEvalView.vue`
  - Interop tab：agent-card、MCP tools、tool detail、MCP call。
  - A2A tab：agent card 和 JSON-RPC 调用；`message/stream` 用 SSE 控制台。具体 JSON-RPC 示例需实施时继续核验 `MessageSendParams`。
  - Eval tab：retrieval/run/suite/dual-run/gate，突出 gate 422 是预期失败信号。
- `ChannelConsoleView.vue`
  - capabilities 只读。
  - messages/callbacks/inbound 分区。
  - `/channel/messages` 默认锁定，必须确认“会触发真实外部投递”。
  - header 签名参数仅在通用 header 支持完成后开放 `/channel/inbound` 执行。

### 核心流程

1. 进入 P1/P2 模块时，`ModuleHost` 渲染专用视图。
2. 专用视图从 catalog 中按 id 找能力，仍调用 `runCapability` / `streamCapability`，不重复实现 HTTP 细节。
3. 执行结果回流到模块本地状态，例如 workflow tracked instances、channel sent messages、eval reports。
4. 对通用能力详情页仍可进入 `CapabilityRunner`，保持一致的表单与 curl 预览。

### 改动范围

- Solution A 的所有共享运行器改动。
- 新增 4 个专用模块目录/视图。
- 修改 `ModuleHost.vue` 注册专用视图。
- 可能新增小型共享组件：
  - `RunHistoryPanel.vue`
  - `DangerConfirm.vue`
  - `FileSummary.vue`
  - `JsonTemplateButton.vue`
  - 是否新增取决于实施时局部重复程度，不预先过度抽象。

### 扩展性

- 高。复杂模块可以持续演进为独立前端模块。
- 仍保留 manifest 驱动，对新增端点友好。
- 适合后续做 micro-frontend 拆分，因为模块边界已经清晰。

### 实施成本

中到高。

- 优点：用户体验和业务可解释性明显更好，降低误操作风险。
- 成本点：视图和测试更多，需要更严格的验收。

### 已知弱点

- 比纯 manifest 多写不少前端代码。
- 专用视图如果过早做复杂状态机，可能与后端响应字段漂移。实施时必须只依赖已确认字段，未确认字段以“待验证”处理。
- Multimodal 的浏览器录音体验如纳入首期，会引入权限、MediaRecorder、格式兼容问题；建议首期先做音频文件上传。
