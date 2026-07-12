# 实施进度 —— showcase-frontend-completion

方案见 `FINAL_PLAN.md`（全量版 B：共享层增强 + catalog 补齐 + 4 个专用视图）。工程：`capability-showcase-frontend/`。

## ✅ 阶段 1：数据结构 + catalog 补齐（完成）

- `src/types/catalog.ts`：`RequestKind` 增加 `'multipart-sse'`。
- `capabilities.yml`：补齐全部能力，端点/入参对照真实 controller 与 protocol record 核验。
  - P0 高级：chat（auto/cascade/mcp/memory/memory.profile.get/clear/cache.clear）、rag（documents.get/obsidian.import/graph.query/graph.entities）、agent（dag×4/chain/vote/reflexive/reflexive.stream/analyst×2/process×2）。
  - P1/P2：workflow(7)、multimodal(8, 含 voice.chat.stream=multipart-sse)、interop-eval(12)、channel(5)。
  - 复杂/嵌套体（eval cases、dag tasks、mcp arguments、a2a params）用 `type:json` 原样 JSON 字段承载。
  - flag 名全部核验：`app.conversation.router/mcp/memory.profile/vision.enabled`、`app.chat.cascade.enabled`、`app.rag.graph/multimodal-embedding.enabled`、`app.vision.enabled`、`app.voice.enabled`、`app.agent.workflow.enabled`、`app.nl2sql.enabled`。
- **结果**：`gen:catalog` 通过；9 模块 **80 能力**，字段零缺失。

## ✅ 阶段 2：共享请求层（完成）

- `src/api/client.ts`：新增 `buildHeaderParams`（in:header 注入，**禁止覆盖 X-Api-Key**，大小写不敏感）、`isStreamingKind`、`isMultipartBody`；`assembleRequest` 支持 header 注入 + `multipart-sse`（FormData + `Accept: text/event-stream`）。
- `src/composables/useCapabilityRun.ts`：`isSse` 改用 `isStreamingKind`（覆盖 sse + multipart-sse）。
- `src/api/sse.ts`：`streamCapability` 经 `assembleRequest`，multipart-sse 自动生效（无需改动）。
- `src/components/capability/CapabilityRunner.vue`：流式按钮判定改用 `run.isSse`。
- `src/utils/curl.ts`：输出 header 参数；multipart-sse 用 `-F` + `-N`。
- `src/api/errors.ts`：新增 `422` 分支（业务门禁结果，如 /eval/gate 回归），不当通用错误。
- **结果**：`type-check` 干净；既有 45 项 vitest 全通过（无回归）。

## ✅ 阶段 3：4 个专用视图（完成，委派 Frontend Agent）

- 新增 `src/modules/workflow/WorkflowDeskView.vue`（发起→待办→认领/完成串联，taskId 一键回填；purge 危险区保持锁定）、`multimodal/MultimodalConsoleView.vue`（图像/语音分区，voice.chat.stream 走流式；全 flag-off 提示）、`interop/InteropEvalView.vue`（MCP/agent-card/Eval 分区；gate 422 业务说明）、`channel/ChannelConsoleView.vue`（capabilities/出站锁定/回调 header/入站签名）。
- 新增共享 `src/modules/_shared/WorkbenchSection.vue`、`InfoNote.vue`；测试夹具 `src/test/fixtures.ts`（加载真实 catalog.json 防漂移）。
- `ModuleHost.vue` 的 `SPECIALIZED` 注册 workflow/multimodal/interop-eval/channel。
- 全部通过 `catalog.capabilityById/moduleById` 取能力、复用 `CapabilityRunner`/`executionGate`，不手写路径、不绕过闸门。

## ✅ 阶段 4：测试（完成）

- Agent 产出 4 个视图组件测试（15 项）。
- 我补齐共享层单测：`src/api/client.test.ts`（header 注入 + **禁止覆盖 X-Api-Key** + multipart-sse 生成 FormData+Accept + isStreamingKind）、`src/utils/curl.test.ts`（header 输出 + multipart-sse -F/-N + key 占位）、`src/api/errors.test.ts`（422 业务门禁 + 403 scope）。
- **结果**：`npm test` 12 文件 **71 项全通过**；`type-check` 干净；`npm run build` 成功。
- **运行态 smoke**：dev server(:5173) 供 80-cap catalog；`/interop/mcp/tools`、`/eval/capabilities`、`/channel/capabilities` 经网关(:18080, dev-key-acme) 均 200。

## ✅ 阶段 5：文档（完成）

- 更新 `capability-showcase-frontend/README.md`（能力覆盖）、`docs/平台工程/能力展示控制台.md`（能力覆盖段）、`docs/平台工程/能力前端模块拆分建议.md`（实现状态）。
- **git 校验**：本次改动仅 `capability-showcase-frontend/` + docs；**零 `.java` 业务后端改动**。

## 全部阶段完成 ✅（catalog+type → 共享层 → 4 专用视图 → 测试 → 文档）；9 模块 80 能力，71 测试通过。
