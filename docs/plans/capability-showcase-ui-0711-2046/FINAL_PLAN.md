# Final Plan

> **阶段二复核修正（Claude 跨模型核验，2026-07-11）**
>
> 已对照真实仓库逐条核验：Codex 计划中引用的网关路由表、`ApiKeyToInternalTokenFilter.isOpen(...)`、`EdgeRateLimitFilter.isOpen(...)`、各业务 controller 与端点、`DocumentController.requireIngest()`、`WorkflowController.requireApprove()` / `DELETE /workflow/data`、edge-gateway 为 `spring-cloud-starter-gateway`（WebFlux 响应式）、当前无任何前端/静态资源目录、8092 端口空闲——**均属实，无臆造**。
>
> **修正一（去过度设计）**：将 **同源 direct mode 定为默认且首期唯一交付路径**。SPA 经网关同源托管在 `/showcase`，浏览器带用户输入的 `X-Api-Key` 直接调用 `/chat`、`/rag/query` 等业务端点（同源无 CORS；网关照常做鉴权/scope/限流）。因此**首期删除 `CapabilityProxyController`（JSON/multipart/SSE 转发）**，它是原计划自评"最复杂/最高风险"的部分，且在同源部署下冗余。SSE 在浏览器用 `fetch()+ReadableStream+TextDecoder` 消费（EventSource 无法携带自定义 header）。代理层降级为**二期可选、默认关闭**。
>
> **修正二（收窄安全面）**：网关 `/showcase/**` 放行后仅暴露 **静态资源 + `GET /showcase/api/catalog`（静态 manifest）**，不再暴露"未鉴权内网代理"。live discovery（`/agent/capabilities` 等需鉴权端点）由浏览器带 API Key 直接拉取并在前端合并，BFF 不持有服务级密钥。
>
> **修正三（新增交付物）**：用户诉求含"看看哪些能力可拆成独立前端模块"，故除代码外**必须产出一份《能力→前端模块拆分建议》文档**（见阶段 5）。
>
> **前端专项规划（2026-07-11，产品/UI/前端架构 三 Agent 并行产出，详见 `07-frontend-design.md`）**：技术栈定 **Vue 3 + TS + Pinia + vue-router**；产品骨架为**能力状态五态徽章体系**（ready / ready-degraded / flag-off / scope-required / display-only，"诚实呈现"优先）；模块拆分 = App Shell(地基) + Chat/RAG/Agent/Async/Analytics(P0) + Workflow/Multimodal(P1) + Interop-Eval/Channel(P2)，**MVP=App Shell+5 个 P0 模块**；**catalog JSON schema 已定稿并驱动后端 Java record（见下方阶段 1 与第 4 节）**；SSE 帧格式已核验（`data:` token + 命名事件 done/error/blocked/grounding-warning）。新增两处后端配合项：① `/showcase/**` 非静态深链 forward 到 `index.html`（history 路由必需）；② frontend-maven-plugin 用属性激活 `!skipFrontend` 规避 `activeByDefault` 脚枪 + 提交产物作离线回退。
>
> **已决策（用户 2026-07-11 拍板）**：前端采用 **Vite + 构建产物**。用 `frontend-maven-plugin`（com.github.eirslett）在 `capability-showcase-ui` 模块内下载本地 node/npm 并跑 `npm ci && npm run build`，Vite `base:'/showcase/'`、`build.outDir` 指向 `../src/main/resources/static/showcase`。**取舍与缓解**：① 该 npm 构建只绑定在本模块，不影响其它模块离线构建；② 首次构建需联网下载 node 与依赖（本机网络易中断，见记忆），故将 npm 构建放入 Maven profile `frontend`（默认激活，可用 `-P!frontend` 跳过），并把最近一次构建产物提交进 `static/showcase/` 作为离线回退；③ 用户批准直接进入阶段四实施。

## 背景、目标与非目标

本项目目前是 Java 21 / Spring Boot 3.3.5 多模块微服务平台，没有现成前端工程、静态页面目录或 OpenAPI 自动文档依赖。用户希望新增一个前端页面，用于展示并试用项目能力，同时判断哪些能力可以拆成独立前端模块。

目标：

- 新增一个能力展示 UI，覆盖 conversation、knowledge、agent、analytics、workflow、async-task、channel、interop、eval、vision、voice 等服务能力。
- 通过页面直接试用现有接口，支持 JSON、multipart、SSE、异步任务、审批流等不同交互形态。
- 按业务域沉淀可拆分前端模块：Chat Console、RAG Workspace、Agent Lab、Async Monitor、Workflow Desk、Analytics Lab、Multimodal Console、Interop/Eval Tools。
- 采用可灰度、可回滚、少侵入的架构。

非目标：

- 不重构现有业务能力。
- 不新增业务数据库表。
- 不让前端绕过 `edge-gateway` 的 `X-Api-Key` 鉴权与租户传播。
- 不把首期做成完整生产运营后台。
- 不承诺默认关闭能力在未配置外部依赖时可成功执行。

## 已确认的业务规则

- 对外入口是 `edge-gateway`，路由来自 `edge-gateway/src/main/resources/application.yml`。
- `ApiKeyToInternalTokenFilter.filter(...)` 负责 `X-Api-Key` 到内部 JWT 的换发，并移除外部 API Key。
- 下游 `InternalTokenAuthFilter.doFilterInternal(...)` 负责绑定 `TenantContext`。
- 文档上传和图片入库要求 `ingest` scope：见 `DocumentController.requireIngest()`。
- workflow 审批与数据清理要求 `approve` scope：见 `WorkflowController.requireApprove()`。
- `DELETE /workflow/data` 是破坏性能力，展示页首期只展示，不默认开放执行。
- SSE 已存在于 `/chat/stream`、`/agent/tasks/{taskId}/stream`、`/async/tasks/{taskId}/stream`、`/agent/reflexive/stream`、`/voice/chat/stream`、A2A `message/stream`。
- 当前只有部分 live capability endpoint：`AgentCapabilitiesController.capabilities()`、`ChannelController.capabilities()`、`EvalController.capabilities()`、`InteropController.tools()`。

## 当前代码与调用链分析

### 网关与安全

- `edge-gateway/src/main/resources/application.yml`：已路由 `/chat`、`/rag`、`/agent`、`/async`、`/channel`、`/interop`、`/eval`、`/vision`、`/voice`。
- `ApiKeyToInternalTokenFilter.isOpen(...)`：仅放行 actuator、`/.well-known`、飞书/钉钉回调和 `/health`。
- `EdgeRateLimitFilter.familyOf(...)`：当前未把 `/rag/documents` 明确识别为 `ingest`，未来如要精确限流需另行评估。
- 若展示页经 `edge-gateway` 的 `/showcase/**` 打开，浏览器首屏导航无法附带 `X-Api-Key` header，因此必须明确把 `/showcase/**` 作为展示服务入口放行；业务 API 仍由页面/BFF 在后续调用中携带用户输入的 API Key，并由 BFF 白名单代理或直接经网关调用。

### 核心能力

- 对话：`ConversationController.chat(...)`、`StreamingConversationController.chatStream(...)`、`ChatAutoController.chatAuto(...)`、`CascadeController.cascade(...)`、`VisionConversationController.chatVision(...)`、`ChatMcpController.chatMcp(...)`、`ExtractController.extract(...)`、`MemoryProfileController.*(...)`。
- 知识库：`DocumentController.*(...)`、`KnowledgeQueryController.query(...)`、`MultimodalImageSearchController.*(...)`、`GraphController.*(...)`、`ObsidianController`。
- Agent：`AgentController.run(...)`、`AgentTaskController.*(...)`、`AgentDagController.*(...)`、`ChainController.chain(...)`、`VotingController.vote(...)`、`ReflexionController.*(...)`。
- 分析：`AnalyticsController.chatSql(...)`、`AnalyticsSchemaController.*(...)`。
- 工作流：`WorkflowController.start/tasks/claim/unclaim/complete/instance/purge(...)`。
- 异步任务：`AsyncTaskController.create/listMine/get/updateStatus/lease/cancel/stream/deadWebhookOutbox(...)`。
- 互操作与评测：`InteropController.*(...)`、`A2aController.handle(...)`、`EvalController.*(...)`。
- 多模态：`VisionController.caption/captionUpload(...)`、`VoiceController.chat/chatStream/transcribe(...)`。

## 候选方案对比与评分

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 静态 SPA + 静态 manifest | 3 | 5 | 5 | 2 | 2 | 4 | 5 | 26 |
| B 独立 Showcase BFF + SPA | 4 | 4 | 3 | 4 | 4 | 3 | 4 | 26 |
| C 网关内嵌 Console | 3 | 2 | 4 | 2 | 2 | 3 | 2 | 18 |
| D 全服务统一能力注册 | 5 | 1 | 1 | 5 | 5 | 2 | 1 | 20 |

最终不机械选择单一方案：采用 B 作为首期架构，吸收 A 的静态 manifest 快速起步，吸收 D 的 descriptor 思路作为后续演进，不采用 C 的网关内嵌 UI。

## 最终方案及选择原因

最终方案：新增独立 `capability-showcase-ui` 服务，Spring Boot（servlet-MVC）托管**静态 SPA** + 极薄 catalog 接口。首期 **只提供** `GET /showcase/api/catalog`（读取 `capabilities.yml` 静态 manifest）与 actuator 健康端点；**不实现业务代理**。SPA 从 catalog 渲染能力模块，并以**同源 direct mode**带用户输入的 `X-Api-Key` 直接调用经网关暴露的现有业务 API（`/chat`、`/rag/query`、`/agent/run` 等）。live discovery（`/agent/capabilities`、`/interop/mcp/tools`、`/channel/capabilities`、`/eval/capabilities`）由浏览器直接拉取并在前端与静态 manifest 合并。`CapabilityProxyController` 降级为**二期可选、默认关闭**，仅在需要跨源部署或统一脱敏时再启用。

选择原因：

- 不改现有业务 controller，降低回归风险。
- 能同源托管，避免静态页面 CORS 待验证问题。
- 能逐步演进：先静态 manifest，后续再推动服务级 `CapabilityDescriptor`。
- BFF 可以统一处理 API Key 脱敏、请求模板、响应展示、SSE/multipart 差异。
- 回滚清晰：移除网关 `/showcase/**` 路由和新增服务，不影响现有业务 API。

已知弱点：

- 初期 catalog 仍可能与真实 controller 漂移，需要测试和文档维护。
- BFF 代理 SSE 与 multipart 有实现复杂度；首期可对部分调用使用同源 direct mode，降低代理风险。
- 新增一个服务会增加部署配置和本地启动成本。
- 若未来要保存用户预设/运行历史，会引入数据库设计和隐私治理，本期不做。

## 精确修改清单

### 新增模块

- `pom.xml`
  - 新增 `<module>capability-showcase-ui</module>`。
- `capability-showcase-ui/pom.xml`
  - 依赖 `spring-boot-starter-web`、`spring-boot-starter-actuator`（`platform-security`/`platform-observability` 首期非必需，因不代理下游、不做内部 JWT 校验；如后续做 catalog 缓存/trace 再加）。
  - Maven profile `frontend`（默认激活）：`frontend-maven-plugin` 安装 node/npm + `npm ci` + `npm run build`（阶段三接入）。`-P!frontend` 可跳过并使用已提交的 `static/showcase/` 产物。

### 新增后端类

- `capability-showcase-ui/src/main/java/com/lrj/platform/showcase/CapabilityShowcaseApplication.java`
  - `main(String[] args)` 启动展示服务。
- `CapabilityCatalogController.java`
  - `GET /showcase/api/catalog`：返回静态能力目录（含每个能力所属前端模块标记，供前端渲染"可拆分模块"视图）。
  - `GET /showcase/api/modules`：返回可拆分前端模块清单（可由 catalog 派生）。
- `ShowcaseSpaForwardController.java`（**history 路由必需**）
  - 把 `/showcase/**` 中非静态资源、非 `/showcase/api/**` 的路径 forward 到 `/showcase/index.html`，支持前端深链刷新。
- **（二期可选）** `CapabilityHealthController.java`
  - `GET /showcase/api/health/services`：服务端探测需下游凭证或形成未鉴权探测，首期不做；健康信号由前端 live discovery 成败体现。
- `CapabilityCatalogService.java`
  - `catalog()`：读取 `capabilities.yml` 静态 manifest 并返回。（live discovery 首期放在浏览器端合并，BFF 不代拉。）
- `CapabilityCatalogProperties.java`
  - 绑定 `app.showcase.*` 与 `app.showcase.capabilities`。
- **（二期可选，默认关闭）** `CapabilityProxyController.java` + `CapabilityDiscoveryClient.java` + `ShowcaseHttpClientConfig.java`
  - 仅当需要跨源部署或服务端统一脱敏时才实现：按 catalog 白名单代理 JSON/multipart/SSE，并复用租户/trace 传播拦截器。首期**不实现**，以避免过度设计与扩大网关放行面。

### 新增前端文件

- `capability-showcase-ui/src/main/resources/static/showcase/index.html`
- `capability-showcase-ui/src/main/resources/static/showcase/assets/*`
- 若选择源码构建：
  - `capability-showcase-ui/frontend/package.json`
  - `capability-showcase-ui/frontend/src/App.*`
  - `capability-showcase-ui/frontend/src/modules/chat/*`
  - `capability-showcase-ui/frontend/src/modules/rag/*`
  - `capability-showcase-ui/frontend/src/modules/agent/*`
  - `capability-showcase-ui/frontend/src/modules/tasks/*`
  - `capability-showcase-ui/frontend/src/modules/workflow/*`
  - `capability-showcase-ui/frontend/src/modules/analytics/*`
  - `capability-showcase-ui/frontend/src/modules/multimodal/*`
  - `capability-showcase-ui/frontend/src/modules/interop-eval/*`

### 新增配置

- `capability-showcase-ui/src/main/resources/application.yml`
  - `server.port: 8092`
  - `app.showcase.edge-base-url`（前端在 direct mode 下的业务 API base，默认同源空串）
  - `app.showcase.proxy-enabled: false`（二期可选代理开关，默认关闭）
  - 静态 capabilities manifest（`capabilities.yml` 或内联）。
- `capability-showcase-ui/Dockerfile`
  - 新增服务需自带 Dockerfile（compose build 需要；参考现有服务 Dockerfile，注意 config-server 因无 Dockerfile 被排除的先例，勿沿用）。
- `edge-gateway/src/main/resources/application.yml`
  - 新增 route：
    - id: `showcase`
    - uri: `${SHOWCASE_URI:http://localhost:8092}`
    - predicates: `Path=/showcase,/showcase/**`
- `edge-gateway/src/main/java/com/lrj/platform/edge/ApiKeyToInternalTokenFilter.java`
  - 修改 `isOpen(String path)`：新增 `path.startsWith("/showcase")`，仅用于展示页和 BFF 入口免网关 API Key；真实业务调用仍需 BFF/前端提供 API Key。
- `edge-gateway/src/main/java/com/lrj/platform/edge/EdgeRateLimitFilter.java`
  - 修改 `isOpen(String path)`：同步新增 `path.startsWith("/showcase")`，避免静态资源和 catalog 被业务限流桶拦截。
- `deploy/docker-compose.yml`
  - 新增 `capability-showcase-ui` 服务。
  - `edge-gateway` 增加 `SHOWCASE_URI=http://capability-showcase-ui:8092`。

### 测试文件

- `capability-showcase-ui/src/test/java/com/lrj/platform/showcase/CapabilityCatalogServiceTest.java`
- `CapabilityCatalogControllerTest.java`
- `edge-gateway` 路由/放行测试：`/showcase/**` 命中 showcase 且不被 API Key 过滤器拦截、且不被限流桶拦截（`ApiKeyToInternalTokenFilter` / `EdgeRateLimitFilter` 的 `isOpen` 新分支）。
- **（二期，若启用代理）** `CapabilityDiscoveryClientTest.java`、`CapabilityProxyControllerTest.java`、`ShowcaseSecurityTest.java`。
- 如引入前端测试：`frontend/src/**/*.test.*`。

## 数据库、接口、配置、消息结构变更

- 数据库：首期不新增表，不做数据迁移。
- 业务接口：不修改现有业务接口；新增 `/showcase/**` 展示服务接口，并在网关 open path 中放行该前缀。
- 配置：
  - 新增 `app.showcase.*`。
  - 网关新增 `SHOWCASE_URI` 与 `/showcase/**` route。
  - docker compose 新增服务环境变量。
- 消息结构：不修改 Kafka topic、event DTO 或 outbox 结构。
- `platform-protocol`：首期不修改；二期如统一 capability DTO，再单独设计兼容方案。
- 网关鉴权白名单：新增 `/showcase/**`。这是展示入口所需的安全边界变更，必须配套 BFF proxy 白名单、API Key 脱敏和危险端点不可执行策略。

## 分阶段实施步骤及依赖关系

### 阶段 1：数据结构与领域模型

任务：

- 新增 `capability-showcase-ui` 模块和 `CapabilityShowcaseApplication`。
- 定义 catalog 领域模型（record + 枚举 `@JsonValue` 小写，见 `07-frontend-design.md` 第 4 节）：`CapabilityCatalog`、`CapabilityModule`、`CapabilityEndpoint`、`ParamSpec`，枚举 `RequestKind{json,multipart,sse,none}`、`ResponseKind{json,sse,text}`、`RiskLevel{safe,caution,destructive}`、`ParamIn{body,query,path,formData,header}`、`ParamType{string,text,number,integer,boolean,select,file,json,array,object}`、`CapabilityState{ready,ready-degraded,flag-off,scope-required,display-only}`。
- 编写 `capabilities.yml`，首期覆盖 App Shell 之外的 **P0 五模块**（Chat/RAG/Agent/Async/Analytics）骨干能力 + 默认开关/scope/风险/五态标注；其余模块可先占位。
- `CapabilityCatalogProperties` 用 `@ConfigurationProperties(app.showcase)` 绑定 manifest。

完成标准：

- 领域模型可被 Jackson 直接序列化为 `07-frontend-design.md` 第 4 节示例 JSON 形态（枚举小写）。
- `capabilities.yml` 至少填全 P0 五模块骨干能力，字段完整（method/path/requestKind/params/scope/flag/risk/state）。
- 没有新增数据库；模块 `mvn -pl capability-showcase-ui -am compile` 通过。

### 阶段 2：核心业务逻辑

任务：

- 实现 `CapabilityCatalogService.catalog()`，返回静态 manifest。
- 前端 direct-mode 客户端：统一封装 JSON / multipart / SSE(`fetch`+`ReadableStream`) 三种调用，统一注入用户 `X-Api-Key`。
- 前端合并 live discovery（浏览器直接拉 `/agent/capabilities`、`/interop/mcp/tools`、`/channel/capabilities`、`/eval/capabilities`），失败静默回退静态 manifest。

完成标准：

- catalog 接口返回完整 JSON。
- discovery 失败不影响页面打开。
- 未启用能力显示为 disabled 或 unknown，不显示为 ready。

### 阶段 3：接口与适配层

任务：

- 实现 `GET /showcase/api/catalog`。
- 新增网关 `/showcase,/showcase/**` route（predicate 逗号形式，与现有路由风格一致）。
- 修改 `ApiKeyToInternalTokenFilter.isOpen(...)` 与 `EdgeRateLimitFilter.isOpen(...)` 放行 `/showcase/**`（仅静态资源 + catalog，不含代理）。
- 处理 `/showcase` 与 `/showcase/` → `index.html` 的欢迎页/深链回退。
- 新增 SPA 页面与各能力模块。

完成标准：

- `http://localhost:8080/showcase` 可打开。
- 页面能以 direct mode 调用 `/chat`、`/rag/query`、`/agent/run`、`/async/tasks`、`/interop/mcp/tools`。
- API Key 仅存于内存态会话，不写入 URL、localStorage、日志。
- 网关 `/showcase/**` 放行面仅含静态资源与 catalog；不存在未鉴权内网代理。

### 阶段 4：测试

任务：

- 增加 BFF 单元测试和 controller 测试。
- 执行现有相关模块测试。
- 做 docker compose 本地 smoke。

完成标准：

- `mvn -pl capability-showcase-ui test` 通过。
- `mvn -pl edge-gateway test` 通过。
- 至少完成 Chat、RAG、Agent、Async、Workflow、Analytics 六类手工/自动 smoke。

### 阶段 5：文档与最终检查

任务：

- 新增展示页使用文档（`docs/平台工程/` 下），标明默认 API Key、scope、能力开关、危险端点默认不可执行策略。
- **产出《能力 → 可拆分独立前端模块》建议文档**（回应用户核心诉求）：列出 Chat Console / RAG Workspace / Agent Lab / Async Monitor / Workflow Desk / Analytics Lab / Multimodal Console / Interop-Eval Tools 各模块的能力边界、对应端点、是否可独立部署、拆分优先级。
- 更新 `docs/README.md` 或 `docs/scenarios.md` 入口索引。

完成标准：

- 另一个开发者按文档可启动展示页。
- 模块拆分建议文档可独立指导后续前端拆分。
- 回滚步骤明确；没有业务接口行为变更。

## 测试方案

详见 `test-plan.md`。最低测试集：

- Catalog 单元测试（manifest 加载、模块映射完整）。
- 网关 `/showcase/**` 放行 + 路由测试（两个 `isOpen` 新分支）。
- direct-mode 手工/自动 smoke：`POST /chat`、`POST /rag/query`、`POST /agent/run`、`POST /async/tasks`、`GET /interop/mcp/tools`。
- 403 scope 测试：`/rag/documents` 使用无 `ingest` key、`/workflow/tasks` 使用无 `approve` key。
- SSE 测试（浏览器 `fetch` 流式）：`/chat/stream` 或 `/async/tasks/{taskId}/stream` 至少一项。
- 前端不落库断言：API Key 不进 URL / localStorage / 日志。
- （二期若启用代理）Proxy 白名单与脱敏测试。

## 风险、监控、灰度与回滚方案

### 风险

- Catalog 漂移：用测试覆盖 endpoint path 与请求模板，并在 UI 标记“基于 manifest / live discovered”。
- SSE 代理资源占用：限制并发、设置超时，首期允许 direct mode。
- multipart 代理内存风险：实现时必须流式处理，限制大小；若无法确认，首期 direct upload。
- API Key 泄露：不落库、不进 URL、不写 localStorage、不写日志。
- `/showcase/**` 网关放行扩大公开面：BFF 必须把 catalog、静态资源与 proxy 分层；proxy 必须要求用户 API Key 并做端点白名单。
- 默认关闭能力误导：UI 显示 feature flag 和配置前置条件。
- 危险能力误触：删除/清理类端点默认不可执行。

### 监控

- `capability-showcase-ui` 暴露 actuator `health/info/prometheus`。
- BFF 记录 catalog 加载失败、discovery 失败、proxy 状态码分布、SSE 打开数、请求耗时。
- 业务调用 trace 依赖现有 `platform-observability` 的 traceId 透传；浏览器展示响应头待验证。

### 灰度

- 首先本地端口 `8092` 直连验证。
- 再通过 `edge-gateway` `/showcase/**` 暴露给内部开发。
- 默认 `app.showcase.proxy-enabled=false` 或仅开启低风险端点；逐步放开 multipart/SSE。
- 通过配置隐藏危险能力和默认关闭能力的执行按钮。

### 回滚

- 从 `edge-gateway` 移除 `/showcase/**` route 或将 `SHOWCASE_URI` 指向空服务。
- 停止/删除 `capability-showcase-ui` compose 服务。
- 若根 `pom.xml` 已加入模块，回滚该模块和 pom 变更。
- 因不改业务接口、不改数据库、不改消息结构，业务服务无需数据回滚。

## 最终验收清单

- [ ] 新增展示页可以打开。
- [ ] 能力目录覆盖已确认核心端点。
- [ ] 至少 6 个前端模块可独立识别和后续拆分。
- [ ] API Key 仅会话内使用，不持久化。
- [ ] 未启用能力显示配置前置条件。
- [ ] 危险端点默认不可执行。
- [ ] SSE、multipart、JSON 调用至少各验证一类。
- [ ] `ingest`、`approve` scope 错误能正确展示。
- [ ] 现有业务模块测试未受影响。
- [ ] 回滚不需要数据库或消息迁移。
