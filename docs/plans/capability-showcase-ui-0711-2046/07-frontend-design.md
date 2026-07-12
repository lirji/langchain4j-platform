# 07 前端设计（产品 / UI / 前端架构 三 Agent 综合）

> 本文件综合三个专业 Agent（Product Manager / UI Designer / Frontend Developer）的并行设计，作为 Vite SPA 实施的单一参考。三者均基于真实端点/DTO/SSE 帧格式核验，无臆造。

## 1. 技术选型（已定）

- **Vue 3 + TypeScript + Pinia + vue-router**，`<script setup>` SFC。理由：后端团队上手成本低、官方全家桶一条链、`v-model`+递归组件天然适配"catalog 驱动动态表单"、样板少。架构（统一 client 层 / SSE 解析 / catalog 驱动）与框架无关，若团队更熟 React 可平移。
- 构建 **Vite**，`base:'/showcase/'`，`build.outDir='../src/main/resources/static/showcase'`，`emptyOutDir:true`。路由 `createWebHistory('/showcase/')` 必须与 base 一致。
- 测试 **Vitest + @vue/test-utils + jsdom**，集成级用 MSW。

## 2. 能力状态五态徽章体系（产品骨架，最高优先级）

这是"诚实呈现"的核心，直接映射到 catalog 的 `state`/`riskLevel`/`executableByDefault`：

| 状态 | 含义 | UI |
|---|---|---|
| `ready` | 默认可执行（如 `POST /chat`） | 绿，可执行 |
| `ready-degraded` | 可执行但为内存/确定性降级（如 `/rag/query` 默认 HashEmbedding，非真实语义） | 绿带 ⚠︎ + tooltip |
| `flag-off` | 需开 feature flag 才注册（nl2sql/vision/voice/cascade/mcp/router/graph 等） | 灰，禁执行，给确切属性名 |
| `scope-required` | 需 `ingest`/`approve` scope，无则 403 | 黄，可执行但前置提示 |
| `display-only` | 危险写/删（如 `DELETE /workflow/data`），默认只展示不执行 | 红，执行禁用 |

> key 不透明，前端无法预判 scope，故 `scope-required` 为"提示 + 反应式 403 处理"。

## 3. 能力 → 前端模块拆分（回应用户核心诉求）

| 模块 | 服务 | 优先级 | 可独立拆出 | 主形态 |
|---|---|---|---|---|
| **App Shell**（地基，不可拆） | — | P0 | — | APIKey会话/catalog/JSON·multipart·SSE客户端/状态徽章/任务时间线组件 |
| **Chat Console** | conversation | P0 | 高 | JSON/SSE/multipart |
| **RAG Workspace** | knowledge | P0 | 高 | multipart/JSON |
| **Agent Lab** | agent | P0 | 高（跨域枢纽：analyst→analytics, process→workflow） | JSON/SSE/异步 |
| **Async Monitor** | async-task | P0 | 最高（横切，供给可复用任务时间线组件） | JSON/SSE |
| **Analytics Lab** | analytics | P0（降级：schema 浏览可用，NL2SQL 默认 flag-off） | 高 | JSON |
| **Workflow Desk** | workflow | P1 | 中（依赖 approve scope） | JSON |
| **Multimodal Console** | vision+voice+chat/vision+rag/image | P1 | 中（几乎全 flag-off + 音视频采集） | multipart/音频/SSE |
| **Interop & Eval Tools** | interop+eval | P2 | 高（协议向，Eval 可自证 RAG） | JSON-RPC/SSE |
| **Channel Console** | channel | P2 | 高（真实外部副作用，强二次确认） | JSON |

关键洞察：direct mode 下各模块只依赖 App Shell、彼此无运行时耦合 → **天然可拆成独立 micro-frontend**（这是"可独立拆出"普遍评高的根因）。代码按 `modules/<domain>/` 隔离。

**MVP（首期）= App Shell + Chat/RAG/Agent/Async/Analytics 五模块**，覆盖 5 大交互形态（JSON/SSE/multipart/异步状态机/只读浏览）。flag-off 高级能力首期"列徽章但不做交互表单"。Workflow/Multimodal 二期，Interop-Eval/Channel 三期。

## 4. Catalog JSON Schema（决定后端 Java record）

后端 `GET /showcase/api/catalog` 返回，枚举全部小写序列化（`@JsonValue`）。

```
CapabilityCatalog { version, generatedAt, modules[] }
CapabilityModule  { id, title, description, icon, order, capabilities[] }
CapabilityEndpoint {
  id, module, title, description,
  method, path,
  requestKind: json|multipart|sse|none,     // 前端分派键
  responseKind: json|sse|text,
  params: ParamSpec[],
  example,                                    // 原样请求体示例字符串
  requiredScopes: [ingest|approve|...],
  featureFlag, featureFlagDefault,           // 非空→"需开启 app.xxx.enabled"
  riskLevel: safe|caution|destructive,
  state: ready|ready-degraded|flag-off|scope-required|display-only,
  executableByDefault: boolean,              // false→只预览/复制 curl，禁执行
  sseEvents: [message,done,error,blocked,grounding-warning],
  docUrl, tags[]
}
ParamSpec { name, in(body|query|path|formData|header), type(string|text|number|integer|boolean|select|file|json|array|object),
            required, label, help, defaultValue, placeholder, enumValues[], min, max, maxLength, accept, example, group }
```

前端运行时另加 `source: manifest|live`（合并 live discovery 后标注来源），不在后端 record。

**真实请求 DTO（决定 params，已核验）**：
- `/chat` → `{message, category?}`
- `/rag/query` → `KnowledgeQueryRequest{query, topK:Integer, minScore:Double, category}`
- `/agent/run` → `AgentRunRequest{goal, webhookUrl}`
- `/async/tasks` → `AsyncTaskCreateRequest{taskId?, kind(必填), input:Map, webhookUrl}`（input→raw JSON 字段）
- `/chat/sql` → `AnalyticsSqlRequest{question}`
- `/vision/caption` → `VisionCaptionRequest{imageBase64, mimeType, instruction}` 或 multipart `file+instruction`
- `/interop/mcp/call` → `McpToolCallRequest{tool, arguments:Map}`

## 5. SSE（已核验帧格式）

`StreamingConversationController` 帧格式：默认 `data:<token>` + 命名事件 `done`/`error`/`blocked`/`grounding-warning`。
- **必须用 `fetch()+ReadableStream+TextDecoder`**（EventSource 无法带 `X-Api-Key` header）。
- 解析要点：`\n\n` 为帧边界、剥 `data:` 前导空格、忽略 `:` 心跳行、跨 read 半帧拼接、CRLF 归一、末帧无空行补发、`AbortError` 走 onDone。
- `/async/tasks/{id}/stream`、`/agent/tasks/{id}/stream` 支持 `Last-Event-ID` 断点续订；`/chat/stream` 仅"重试"。

## 6. 布局与设计系统（UI）

- **顶栏（全局态 + API Key 脱敏输入）+ 左侧两级导航（模块→能力）+ 主工作区（能力头 + 请求/响应并排）**。四种交互形态共用外壳、只换右侧查看器（JSON viewer / SSE console / 异步任务时间线）。
- 响应式：`xl≥1280` 三区并排；`md 768–1023` 左栏抽屉化 + 请求/响应上下堆叠；`sm<768` 单列尽力可用。
- 设计基调：克制技术蓝 + 冷调 slate，**浅/深双主题对等**（WCAG AA），流式态独立青色（区别于成功绿）。完整 CSS 变量清单见 UI Agent 输出（颜色 hex/字号/间距/圆角/阴影/焦点环/断点），实施时落到 `src/styles/tokens.css`。
- 核心组件：CapabilityCard（方法/形态/scope/flag/risk/来源徽章）、DynamicForm+字段族、ResponseViewer（JSON 高亮折叠）、SseConsole（拼接视图+事件流双视图）、AsyncTaskTimeline、FileDropzone、ApiKeyInput（脱敏+不落库提示）、StatusBadge、空/加载/错误三态。
- 关键交互态：无 Key / flag-off（给确切属性名）/ 403 scope 翻译成人话 / 危险端点锁定（purge 完全不可执行）/ SSE 进行-完成-中断 / 失败重试与 409 冲突区分。
- 可访问性：AA 对比度、徽章不单靠颜色、纯键盘可达（`⌘/Ctrl+Enter` 发送、`⌘/Ctrl+K` 筛选、`Esc` 停止）、`:focus-visible` 焦点环、`aria-live` 流区、reduced-motion 降级。

## 7. 前端工程结构（前端架构）

```
capability-showcase-ui/frontend/
  package.json tsconfig.json vite.config.ts vitest.config.ts index.html
  src/
    main.ts App.vue
    router/index.ts                 # / → Overview; /m/:moduleId; /m/:moduleId/:capId
    stores/  session.ts(apiKey仅内存,绝不持久化) catalog.ts ui.ts
    api/     client.ts sse.ts catalog.ts errors.ts
    composables/ useCapabilityRun.ts useAbortable.ts
    components/ layout/ form/(DynamicForm+fields) capability/(Card/Runner/ResponseViewer/SseConsole/badges)
    modules/ OverviewView GenericModuleView(数据驱动覆盖~80%)
             tasks/AsyncMonitorView workflow/WorkflowDeskView chat/ChatConsoleView(三个有状态专用视图)
    types/ catalog.ts api.ts   utils/ redact.ts
```

策略：**通用优先 + 逃生舱**——GenericModuleView+CapabilityRunner+DynamicForm 从 catalog 覆盖绝大多数能力；仅 Async Monitor（任务态机+SSE）、Workflow Desk（审批环）、Chat（流式转录）写专用视图。

## 8. 后端配合项（非前端代码，实施须落）

1. **深链 forward**：`/showcase/**` 中非静态资源路径需 forward 到 `index.html`（history 模式必需），用一个 `@Controller` forward 实现。
2. **catalog record/enum**：按第 4 节，枚举 `@JsonValue` 小写；`capabilities.yml` 覆盖 8 模块（首期至少填全 P0 五模块骨干能力）。
3. **frontend-maven-plugin 两个陷阱**：
   - `activeByDefault` 脚枪——只要显式启用其它 profile，它就被关掉。改用**属性激活** `<property><name>!skipFrontend</name></property>`（除非 `-DskipFrontend` 否则激活）。
   - 离线：首次 node/npm 下载需联网（本机网络易断），故**提交一份 `static/showcase/` 构建产物作离线回退**，断网用 `mvn -DskipFrontend package`。产物入源码树会造 git diff 噪音——用独立 commit（如 `chore(showcase): rebuild frontend assets`）隔离。
4. **不要手动设 multipart Content-Type**（浏览器需自动带 boundary）；`X-Api-Key` 只在 client 注入处写入，绝不入 URL/localStorage/日志。

## 9. 待实施时对齐（标注为假设，未臆造）
- `X-Trace-Id` 是否对浏览器 JS 可见（同源应可读，实测确认）。
- `/chat/vision`、`/chat/memory`、chat auto/mcp 的确切 feature-flag 属性名，以 conversation-service `application.yml` 为准。
- `AnalyticsSqlRequest`/`AgentRunRequest` 精确字段名以 `platform-protocol` DTO 源码为准。
