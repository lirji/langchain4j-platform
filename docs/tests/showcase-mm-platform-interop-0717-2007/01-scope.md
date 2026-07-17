# 01 被测面与仓库事实

## 任务边界

- 只设计前端 Vue 3 + TypeScript + Pinia + Vitest + `@vue/test-utils`（jsdom）交互测试，不实施测试、不修改业务代码。
- 唯一规划中的新增测试文件是三个 `*.interaction.test.ts`；本次实际只把代码草案写入本文档集。
- 生产目录、现有测试、`public/catalog.json` 均只读。仓库当前存在与本任务无关的未提交改动，蓝图不覆盖、不归因这些改动。
- Java/Maven 测试铁律不适用于本被测面：这里没有 Java 测试类、Spring context、Mockito、H2 或 `TenantContext`。对应的前端等价约束是：新 Pinia 隔离、真实 catalog、`vi.stubGlobal('fetch', ...)`、禁用 `vi.mock` 模块替换、逐例清理 DOM/global/localStorage。

## 被测模块、组件与可观察入口

### `capability-showcase-frontend/src/modules/multimodal/MultimodalConsoleView.vue`

脚本内逻辑（未导出，必须经 DOM 交互覆盖）：

- `module`、`focusedCap`：模块/深链解析。
- `pick()`、`imageCaps`、`voiceCaps`：按固定能力 ID 从目录挑选。
- `selectedImageId`、`selectedVoiceId` 及两个 watcher：默认选择、目录变化。
- `selectedImageCap`、`selectedVoiceCap`：选择与首项回退。
- `focusedIsStream`：通过 `isStreamingKind()` 识别流式深链。
- chips → 单 `CapabilityRunner` 切换；图像和语音分区各维持一个独立选择。
- 真实运行链由 `CapabilityRunner` → `useCapabilityRun` → `executionGate` → `runCapability` / `streamCapability` 完成。

真实目录能力：

- 图像：`vision.caption.file`（multipart）、`vision.caption.json`（json）、`chat.vision`（multipart + query）、`rag.image.ingest`（multipart，`ingest` scope）、`rag.image.search`（json）。
- 语音：`voice.transcribe`、`voice.chat`（multipart + query）、`voice.chat.stream`（multipart-sse）。

### `capability-showcase-frontend/src/modules/interop/InteropEvalView.vue`

脚本内逻辑（未导出，必须经 DOM 交互覆盖）：

- 目录/深链：`module`、`focusedCap`、`pick()`、`agentCardCaps`、`evalCardCaps`、四个专用 capability computed、`focusedIsGate`。
- 探测辅助：`asStr()`、`numOf()`、`firstArray()`。
- 公共调用链：`callCap()` → `executionGate()` → `runCapability()` → `humanizeError()`。
- MCP：`parseTools()`、`toolsFallback`、`loadTools()`、`selectTool()`、`callTool()`，及 tools/detail/call 三组 busy/error/result 状态。
- Retrieval：`metricTone()`、`fmtMetric()`、`extractMetrics()`、`extractCaseRows()`、`retrievalFallback`、`retrievalGate`、`runRetrieval()`。
- 卡片：`interop.agent-card`、`interop.a2a.agent-card`、`interop.a2a.call`；`eval.capabilities`、`eval.run`、`eval.suite.run`、`eval.dual-run`、`eval.gate`。

### `capability-showcase-frontend/src/modules/channel/ChannelConsoleView.vue`

脚本内逻辑（未导出，必须经 DOM 交互覆盖）：

- 目录/深链：`module`、`focusedCap`、`discoveryCap`、`outboundCap`、`inboundCap`、`callbackCaps`、`focusedIsOutbound`。
- 探测辅助：`asStr()`、`firstArray()`、`parseChannels()`、`discoveryFallback`。
- 发现链：`discover()` → `executionGate()` → `runCapability()` → `humanizeError()`，及 busy/error/raw/channels/discovered 状态。
- `channel.messages.send`：`display-only` + `executableByDefault=false`，卡片深链后由通用 runner 二次确认。
- `channel.callbacks.async-task`、`channel.callbacks.workflow`：两个内联 runner，业务 header 由目录 `in:header` 参数构建。
- `channel.inbound`：卡片深链及可选 `X-Channel-Signature` header。

## 共享基建（本次做联动回归，不重复其全部纯函数单测）

- `src/utils/gate.ts`：`executionGate()`；优先级为 flag-off → 危险确认 → 凭证 → scope。
- `src/api/client.ts`：`runCapability()`、`assembleRequest()`、`buildFormData()`、`buildHeaderParams()`、`isStreamingKind()`。
- `src/api/sse.ts`：`streamCapability()`；multipart-sse 使用 `fetch` + `ReadableStream`。
- `src/api/errors.ts`：`humanizeError()`，含 400/401/403/404/422/429/5xx 与网络错误。
- `src/stores/session.ts`：`hasCredential`、`permissionContext()`、`runContext()`。
- `src/stores/catalog.ts`：`moduleById()`、`capabilityById()`、响应式 catalog 合并结果。
- `src/test/interactionHarness.ts`：`setupCatalog`、`capability`、`jsonResponse`、`sseResponse`、`deferred`、`buttonByText`、`cleanup`。
- `public/catalog.json`：真实生成目录；草案禁止手写完整 capability fixture。

## 现有测试与已覆盖基线

- `src/modules/multimodal/MultimodalConsoleView.test.ts`（63 行）：分区/提示、关键 chips、默认 runner、语音流式 chip、流式深链冒烟。
- `src/modules/interop/InteropEvalView.test.ts`（63 行）：三分区、422 文案、卡片集合、MCP/检索入口、gate 深链冒烟。
- `src/modules/channel/ChannelConsoleView.test.ts`（76 行）：四分区、出站锁定、回调 runner/header 元数据、发现初态、出站深链冒烟。
- 已有共享测试：`src/utils/gate.test.ts`、`src/api/client.test.ts`、`src/api/errors.test.ts`、`src/components/capability/CapabilityRunner.interaction.test.ts`、SSE 相关测试。
- 可参考 interaction 范式：chat/rag/agent/tasks/workflow/analytics 下的 `*.interaction.test.ts`。统一直接 stub `fetch`，不用 `vi.mock()`。

现有三个视图测试没有真实 fetch、请求体/header/FormData、错误恢复、busy、竞态、目录变化或租户隔离断言，因此不能替代本蓝图。

## 建议放置路径（仅供后续实现 Agent）

- `capability-showcase-frontend/src/modules/multimodal/MultimodalConsoleView.interaction.test.ts`
- `capability-showcase-frontend/src/modules/interop/InteropEvalView.interaction.test.ts`
- `capability-showcase-frontend/src/modules/channel/ChannelConsoleView.interaction.test.ts`

## 运行命令

```bash
cd capability-showcase-frontend
npx vitest run src/modules/multimodal/MultimodalConsoleView.interaction.test.ts
npx vitest run src/modules/interop/InteropEvalView.interaction.test.ts
npx vitest run src/modules/channel/ChannelConsoleView.interaction.test.ts
npx vitest run \
  src/modules/multimodal/MultimodalConsoleView.interaction.test.ts \
  src/modules/interop/InteropEvalView.interaction.test.ts \
  src/modules/channel/ChannelConsoleView.interaction.test.ts
npm run type-check
```

不使用 `mvn`；`INTERNAL_JWT_SECRET`、H2、Spring 测试注解均与本次前端 Vitest 路径无关。
