# 05 Solution C

## architecture-designer 视角：插件化能力渲染器

### 核心思路

把 `CapabilityRunner` 拆成可插拔渲染器：每个 capability 可以声明 `uiKind` 或通过 `requestKind/responseKind/tags` 映射到不同 runner。P1/P2 与 P0 高级能力不直接绑定模块专用大视图，而是由能力级插件决定表单、提交、响应和结果摘要。

这是一条偏平台化的路径：目标是建立一个可长期扩展的“能力渲染系统”。

### 架构

- Catalog Schema 扩展：
  - 计划新增字段：`uiKind`、`examples`、`dangerPrompt`、`resultHints`、`postRunActions`。
  - 当前类型不存在这些字段，实施前必须扩展 `src/types/catalog.ts`。
- Runner Registry：
  - `default-json`
  - `multipart`
  - `sse-json`
  - `multipart-sse`
  - `workflow-action`
  - `eval-runner`
  - `channel-danger`
- Module Views：
  - 模块页依旧主要是能力卡片和 runner 容器。
  - 复杂体验由 runner 插件承接。

### 模块职责

- `CapabilityRunnerHost.vue`（计划新增）
  - 根据 capability 选择具体 runner。
- `runnerRegistry.ts`（计划新增）
  - 维护 capability id / uiKind 到组件的映射。
- `DefaultCapabilityRunner.vue`
  - 当前 `CapabilityRunner.vue` 的职责迁移或重命名。
- `MultipartSseRunner.vue`
  - 专门处理 `/voice/chat/stream` 等 multipart SSE。
- `DangerousActionRunner.vue`
  - 专门处理 `/channel/messages`、`DELETE /workflow/data` 等副作用能力。
- `JsonTemplateRunner.vue`
  - 给 Eval、Agent DAG、MCP call 等复杂 JSON 请求提供模板按钮。

### 核心流程

1. `CapabilityCard` 跳转到 capability 详情。
2. `CapabilityRunnerHost` 根据 `cap.uiKind` 或 fallback 规则选择 runner。
3. runner 仍复用 `runCapability`、`streamCapability`，但拥有自己的表单布局和结果摘要。
4. `GenericModuleView` 不需要知道具体领域。

### 改动范围

- `types/catalog.ts` schema 扩展最大。
- `CapabilityRunner.vue` 拆分风险较高。
- 新增 registry、多个 runner 组件和测试。
- `capabilities.yml` 需要为复杂能力声明 `uiKind` 与示例。

### 扩展性

最高。长期看适合大量异构能力。

### 实施成本

高。

- 优点：架构干净，能力级体验可组合，模块不容易膨胀。
- 成本点：当前项目还没有足够多的重复 runner 需求，首期可能过度设计。

### 已知弱点

- 重构 `CapabilityRunner` 会影响所有已可用 P0 骨干能力，回归面大。
- 如果 `uiKind` 设计不稳，后续会形成一套新的内部 DSL 负担。
- 对 Workflow 这类跨能力串联的场景，单能力 runner 仍不如领域工作台自然。
