# 能力展示与试用控制台（独立前端）

langchain4j-platform 各服务能力的展示与试用控制台。**前后端分离**：本工程是纯静态 SPA（Vue3 + TS + Vite），不属于后端 Maven 构建，可独立部署到任意静态托管（nginx / Netlify / CDN / 对象存储）。

浏览器以 **direct mode** 带用户输入的 `X-Api-Key` 跨域直调 `edge-gateway` 暴露的业务能力（`/chat`、`/rag/query`、`/agent/run` …）。能力目录默认打包为静态 `catalog.json`（由 `capabilities.yml` 生成），**零后端依赖**。

## 与后端的关系

- 唯一运行期依赖：`edge-gateway`（业务 API）。跨域时网关需 CORS 放行本前端来源并允许 `X-Api-Key` 头（见网关 `GATEWAY_CORS_ORIGINS`）。
- 能力目录：默认静态内置；也可 `VITE_CATALOG_URL` 指向后端动态目录。
- API Key 仅存在浏览器内存，绝不落 URL/localStorage/日志。

## 本地开发

```bash
npm install
npm run dev        # http://localhost:5173 —— 业务请求经 vite 代理到 http://localhost:8080 网关
```

dev 模式 `VITE_EDGE_BASE_URL` 留空即可（走同源代理，无跨域）。

## 构建

```bash
npm run build      # 先由 capabilities.yml 生成 public/catalog.json，再 vite build 到 dist/
npm run preview    # 本地预览 dist（:4173）
```

分离部署（跨域）时在构建期烘焙网关地址：

```bash
VITE_EDGE_BASE_URL=https://api.example.com npm run build
```

## Docker（独立 nginx 静态站）

```bash
docker build --build-arg VITE_EDGE_BASE_URL=http://localhost:8080 -t showcase-frontend .
docker run -p 8093:80 showcase-frontend      # http://localhost:8093
```

deploy/docker-compose.yml 已含 `capability-showcase-frontend` 服务（:8093），并给 edge-gateway 配好 `GATEWAY_CORS_ORIGINS`。

## 配置项（VITE_*，见 .env.example）

| 变量 | 默认 | 说明 |
|---|---|---|
| `VITE_EDGE_BASE_URL` | 空(同源/代理) | 业务网关基址；跨域分离部署必填 |
| `VITE_BASE` | `/` | SPA 部署子路径 |
| `VITE_CATALOG_URL` | 内置 `catalog.json` | 改为后端动态目录接口 |
| `VITE_LIVE_DISCOVERY` | `true` | 是否启用 live discovery |

## 能力目录

`capabilities.yml` 是目录的**唯一事实源**（能力的方法/路径/参数/scope/feature-flag/风险/五态）。`npm run gen:catalog`（dev/build 前自动执行）将其转为 `public/catalog.json`。新增/调整能力改这个文件即可。

**覆盖范围**：9 个模块、80 条能力，端点/入参对照真实 controller 与 protocol record 核验。
- P0：Chat（含 auto/cascade/mcp/memory 等高级）、RAG（含 graph/obsidian/image）、Agent（含 dag/chain/vote/reflexive/analyst/process）、Async、Analytics。
- P1/P2：Workflow（审批串联）、Multimodal（图像/语音，含 `multipart-sse` 的 `/voice/chat/stream`）、Interop & Eval（MCP/A2A + 评测，gate 的 422 作为业务门禁结果展示）、Channel（出站默认锁定 + 回调 header 注入）。

模块以 `GenericModuleView` 数据驱动为底；`workflow/multimodal/interop-eval/channel` 有领域专用工作台视图（`ModuleHost.SPECIALIZED`），复用共享 `CapabilityRunner`/执行闸门，不绕过安全裁决。复杂/嵌套请求体（eval cases、dag tasks、mcp arguments、a2a params）以 `type:json` 原样 JSON 字段承载。

## 测试

```bash
npm test           # vitest：SSE 解析 / API Key 脱敏不泄露 / 动态表单校验 / catalog 合并回退 / 执行闸门
npm run type-check # vue-tsc
```

能力→前端模块拆分说明见仓库 `docs/平台工程/能力前端模块拆分建议.md`。
