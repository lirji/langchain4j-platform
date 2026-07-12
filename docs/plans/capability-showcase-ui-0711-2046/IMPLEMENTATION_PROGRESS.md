# 实施进度 —— capability-showcase-ui

分支：`feat/capability-showcase-ui`（基于 main）。方案见 `FINAL_PLAN.md`，前端设计见 `07-frontend-design.md`。

## ✅ 阶段 1：数据结构与领域模型（完成）

做了什么：
- 根 `pom.xml` 新增 `<module>capability-showcase-ui</module>`。
- 新模块 `capability-showcase-ui/`：`pom.xml`（web + actuator + jackson-dataformat-yaml）、`CapabilityShowcaseApplication`、`application.yml`（:8092）。
- catalog 领域模型（`catalog/` 包）：record `CapabilityCatalog / CapabilityModule / CapabilityEndpoint / ParamSpec` + 枚举 `RequestKind / ResponseKind / RiskLevel / ParamIn / ParamType / CapabilityState`，全部 `@JsonValue`/`@JsonCreator` 做 kebab/lower ↔ 常量名双向映射。
- `capabilities.yml` 静态清单：P0 五模块（chat/rag/agent/tasks/analytics）骨干能力（共 25 条），P1/P2 四模块占位（workflow/multimodal/interop-eval/channel）。
- `CapabilityShowcaseProperties`（`@ConfigurationProperties app.showcase`）。

测试结果：`CapabilityCatalogYamlTest` 3 项通过（YAML 反序列化、枚举 kebab 绑定、序列化为 lower-kebab JSON）。**捕获并修复**了 manifest 中一处 YAML flow-scalar 逗号未转义 bug（label 含 ASCII 逗号）。

完成标准自检：✅ 模型可 Jackson 序列化为设计 JSON 形态；✅ manifest 填全 P0 五模块；✅ 无新增数据库；✅ `mvn -pl capability-showcase-ui compile` 通过。

## ✅ 阶段 2：核心业务逻辑（后端部分完成）

做了什么：
- `CapabilityCatalogService`：从 `classpath:capabilities.yml` 懒加载 + 内存缓存 catalog，填充 version/generatedAt。
- （前端 direct-mode 客户端 + live discovery 合并属于 SPA，见阶段 3 前端待办。）

完成标准自检：✅ catalog 加载成功；discovery 失败回退等前端逻辑待 SPA 落地。

## ✅ 阶段 3：接口与适配层（后端 + 网关完成；前端 SPA 待办）

做了什么（后端/网关）：
- `CapabilityCatalogController`：`GET /showcase/api/catalog`、`GET /showcase/api/modules`。
- `ModuleSummary`（模块摘要，回答"可拆分前端模块清单"）。
- `ShowcaseSpaForwardController`：`/showcase`、`/showcase/`、`/showcase/m/**` forward 到 `index.html`（history 深链回退，不用 `/showcase/**` 通配以免遮蔽静态资源/接口）。
- `edge-gateway/application.yml`：新增 `showcase` 路由（`Path=/showcase,/showcase/**` → :8092）。
- `ApiKeyToInternalTokenFilter.isOpen` + `EdgeRateLimitFilter.isOpen`：各加 `path.startsWith("/showcase")` 放行（仅静态 + catalog，无代理）。
- 临时占位 `static/showcase/index.html`（拉 catalog 渲染模块列表；正式 Vue SPA 构建将整体替换本目录）。

测试结果：
- showcase 单测 5 项全通过（catalog YAML 3 + controller 2）。
- edge-gateway `-am package` 成功（filter 编辑编译通过）。
- **运行态 smoke（8092 直连）**：`/actuator/health` UP；`/showcase/api/catalog` 返回 version=1、9 模块（chat3/rag5/agent6/tasks8/analytics3 + 4 占位）、SSE 元数据完整；`/showcase/` 与 `/showcase/m/chat` forward 返回 HTTP 200 占位页。

改动面（git diff）：既有业务代码仅改 12 行（网关 2 filter + 路由 + 根 pom），其余为自包含新模块。**未改任何业务 controller / DTO / 表 / 消息结构。**

完成标准自检：✅ `/showcase` 可打开；✅ catalog/modules 接口就绪；✅ 网关放行面仅静态+catalog，无未鉴权代理；⏳ 页面 direct-mode 调 /chat 等（待正式 SPA）。

## ✅ 基建 + 阶段 5 文档（完成）

- `capability-showcase-ui/pom.xml`：新增 `frontend` profile（**按 `frontend/package.json` 存在激活**，避免 SPA 就绪前破坏构建；`-DskipFrontend=true` 离线跳过），frontend-maven-plugin 跑 `npm install && npm run build`，node 缓存进 target。
- `capability-showcase-ui/Dockerfile`（copy jar，:8092）。
- `deploy/docker-compose.yml`：新增 `capability-showcase-ui` 服务 + edge-gateway 加 `SHOWCASE_URI` 与 depends_on。
- 文档：`docs/平台工程/能力展示控制台.md`（使用/配置/回滚）、`docs/平台工程/能力前端模块拆分建议.md`（**核心交付：能力→模块拆分**）、`docs/README.md` 索引新增两条。

## ✅ 阶段 3 前端 SPA（完成）

- Frontend Developer Agent 实现 Vue3+TS+Vite 全量 P0 SPA，共 **66 个文件**（`capability-showcase-ui/frontend/`）：App Shell（顶栏 API Key 脱敏 + 两级导航）、Chat（流式转录）、RAG、Agent、Async Monitor（任务时间线）、Analytics；`GenericModuleView`+`CapabilityRunner`+`DynamicForm`+`ResponseViewer` 数据驱动；`api/client.ts`（JSON/multipart/none 分派）、`api/sse.ts`（fetch+ReadableStream 帧解析）；五态执行闸门 `utils/gate.ts`；session store 仅内存。
- **契约保真**：Agent 读了真实 Java record，`types/catalog.ts` 完全对齐（含 `in:'form-data'`、五态、catalog 走绝对路径 `/showcase/api/catalog`）。

## ✅ 阶段 4 测试（完成）

- **vitest 45 项全通过**（sse 解析 13 / redact 8 / DynamicForm 11 / catalog store 7 / gate 6）。
- 修复 1 个健壮性 bug：`FileField.vue` 的 `model instanceof File` 在非 DOM 环境抛错 → 改为 `typeof File !== 'undefined'` 守卫的 computed。
- 修复 tsconfig 项目引用缺陷（TS6310：composite + noEmit 冲突；vite/vitest 双实例类型冲突）→ `vue-tsc --noEmit` **type-check 干净通过**。
- Java 测试 5 项通过；edge-gateway 构建通过。

## ✅ 构建与运行态验证（完成）

- `mvn -pl capability-showcase-ui package` 触发 `frontend` profile：下载 node v20.18.0 → `npm install` → `vite build`，产物入 `src/main/resources/static/showcase/`（`base=/showcase/`），BUILD SUCCESS。
- **运行态 smoke（8092 直连）**：SPA index.html 服务正常并引用构建资产；JS/CSS 资产 HTTP 200 且 content-type 正确；深链 `/showcase/m/chat`、`/showcase/m/tasks/async.create` forward 200；`/showcase/api/{catalog,modules}` 200。
- gitignore 已覆盖 `node_modules/`、`target/`（0 误跟踪）；构建产物在 `src/main/resources/static/showcase/`（作离线回退，`-DskipFrontend=true` 时使用）。

## 最终改动面

既有文件仅改 6 个：`pom.xml`（+1 module）、`edge-gateway` 两个 `isOpen`（各 +行放行 /showcase）、`edge-gateway/application.yml`（+showcase 路由）、`deploy/docker-compose.yml`（+服务 +SHOWCASE_URI）、`docs/README.md`（+2 索引）。其余为自包含新模块 `capability-showcase-ui/`（后端 + frontend/ 源码 + 构建产物）与 docs。**零业务 controller/DTO/表/消息改动。**

## 全部阶段完成 ✅（1 数据模型 → 2 服务 → 3 接口+网关+SPA → 4 测试 → 5 文档）

## ✅ 追加：前后端分离重构（用户要求"把前端整个迁出、不挂靠后端"）

把同源托管改造为**彻底的前后端分离**：

- **前端迁出为独立工程** `capability-showcase-frontend/`（仓库根，不属于 Maven 构建）。可独立部署到任意静态托管。
- **catalog 静态化**：`capabilities.yml` 迁入前端，`scripts/gen-catalog.mjs`(js-yaml) 在 `prebuild`/`predev` 生成 `public/catalog.json`。**前端零后端依赖**。
- **全参数化**：`src/config.ts` 读 `VITE_*`——`VITE_EDGE_BASE_URL`(跨域网关基址)、`VITE_BASE`(部署子路径)、`VITE_CATALOG_URL`；router 用 `import.meta.env.BASE_URL`；vite base 默认 `/`，`outDir=dist`。
- **网关 CORS**：`edge-gateway/application.yml` 加 `spring.cloud.gateway.globalcors`（`GATEWAY_CORS_ORIGINS` 白名单 + 允许 `X-Api-Key` + 暴露 `X-Trace-Id`）。这是**唯一的功能性后端改动（11 行）**。
- **删除后端展示模块**：`capability-showcase-ui/`(Spring 服务) 整个删除；根 pom module、网关 `/showcase` 路由、两处 `isOpen` 放行全部还原为原状（**净零改动**）。
- **部署资产**：前端 `Dockerfile`(node build→nginx) + `nginx.conf`(SPA fallback)；docker-compose 用 `capability-showcase-frontend`(:8093) 替换旧后端服务。

**验证**：
- vitest 45 项全通过（修 catalog store 测试的 URL 断言）；vue-tsc type-check 干净。
- `VITE_EDGE_BASE_URL=… npm run build` → dist（base=/、内置 catalog.json）；preview smoke：根页/`catalog.json`(200)/深链 fallback(200)。
- **网关 CORS 实测**：允许来源(:8093) 预检 200 + `Access-Control-Allow-Origin` + 允许 `x-api-key` + 暴露 `X-Trace-Id`；未授权来源 **403**。
- edge-gateway 打包通过；根 reactor 无残留模块引用。

**最终净改动面（既有文件）**：`edge-gateway/application.yml`(+CORS 11 行)、`deploy/docker-compose.yml`、`docs/README.md`。其余为独立前端工程 `capability-showcase-frontend/` + docs。**零业务 controller/DTO/表/消息改动；两处网关 filter 与根 pom 净零。**
