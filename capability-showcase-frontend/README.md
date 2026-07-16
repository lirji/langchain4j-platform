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

## 登录

除"手输 API Key 直连"外，控制台支持**账号密码登录**（auth-service）：登录得会话 JWT（仅内存，Bearer）+ httpOnly 刷新 cookie 静默续期；边缘用会话 Bearer 换发内部 JWT，下游零改动。API Key 双模保留——**顶栏填 Key 时覆盖登录会话**（顶栏高对比警告提示，账号权限预判暂停，以服务端鉴权为准）。

- **自助注册**：运行时遵从 `/auth/public-config`（注册开关 + 密码策略）；用户不选租户/角色。
- **RAG 双视图**：当前租户库 / 共享知识库（`__public__`）tab（受 `SHARED_KB_UI_ENABLED` + 运行时 `/rag/config.publicEnabled` 双控）；查询命中打"租户/共享"来源 badge（服务端 `visibility` 权威）；共享图片本期禁用。

> 平台管理模块（`/admin` 用户/角色/租户/用户组管理）已从本控制台移除：身份与授权统一由外部 IAM（Casdoor SSO + auth-platform）承担，本前端只消费登录会话与 scopes。

### Casdoor OIDC 单点登录（阶段④，构建期灰度）

除上述账号密码/API Key 外，控制台可接 **Casdoor OIDC**（Authorization Code + PKCE）：登录后业务请求以 `Authorization: Bearer <casdoor-token>` 走边缘网关，网关验签换发形状不变的内部 JWT，使 `Casdoor sub = 内部 JWT uid = TenantContext.userId = SpiceDB user id` 四者一致。由构建期 `VITE_AUTH_MODE` 三态灰度、可秒回滚：

- `apikey`（默认）：现状——API Key / 账号密码会话；**不加载** Casdoor，与接入前逐字一致。
- `oidc`：Casdoor 为唯一登录入口（隐藏账密表单与 API Key 直连入口），身份从 access token 的 `permissions[].name ∩ allowlist` 解出。
- `dual`：迁移期两者并存——边缘 Casdoor 优先、API Key 兜底。

实现要点：`oidc-client-ts` 薄封装（token 存 `sessionStorage`，硬刷新秒恢复、过期靠 refresh_token 轮换）；`/callback` 回调页；近失效静默续期 + 续期失败弹会话过期模态（不丢 deep-link）；单点登出经 Casdoor `end_session` + `BroadcastChannel` 多标签同步；SSE 中途断流带 `Last-Event-ID` 续订。设计与决策记录见 [`docs/平台工程/公网化-OIDC-改造方案.md`](../docs/平台工程/公网化-OIDC-改造方案.md)。

**多租户登录（方案C：Casdoor Shared Application + 选组织）**：`oidc`/`dual` 模式下登录页先要求输入**租户 / 组织名（Casdoor org）**，`getUserManager(tenant)` 据此以 `<base>-org-<tenant>`（base = `VITE_CASDOOR_CLIENT_ID`）构造客户端发起登录（见 `src/auth/oidc.ts`）。每租户 access token 的 `aud=<base>-org-<org>`、`owner=该 org`；活跃租户写 `sessionStorage`，回调/续期/硬刷新据此恢复对应 UserManager。

**灰度硬约束**：① 切前端 `oidc`/`dual` 前须先开边缘 `EDGE_CASDOOR_ENABLED=true`（否则 Casdoor Bearer 落到会话 filter 全 401）；② 前端 `VITE_CASDOOR_CLIENT_ID` 是 Shared Application 的 **base**，须与边缘 `edge.casdoor.audiences` 的 base 一致——**不再要求逐字相等**：方案C 下每租户 token 的 `aud=<base>-org-<org>`，边缘按 `<base>-org-*` audience 家族 + `(owner,aud)` 绑定（`owner==org`）放行；前端 scope allowlist 仍与边缘 `edge.casdoor.scope-allowlist` 逐字同步；③ 回调/登出/静默续期三个 SPA 路径须在 Casdoor 应用 redirectUris 精确登记，并放行该源 CORS。回滚＝改 `VITE_AUTH_MODE=apikey` 重构建。

## 左侧导航与快捷键

左侧菜单按「语义分组 → 模块 → 能力」三级组织，分组**彩色编码**（对话与检索=蓝 / 智能体与编排=紫 / 多模态=琥珀 / 平台工程与互操作=青），模块以图标色块 chip 标识；能力行显示 HTTP 方法与**五态状态点**（颜色 + 形状双编码：就绪 / 就绪·降级 / 需授权 / 未启用 / 已锁定，底部附文字图例，不单靠颜色）。当前所在模块/能力有唯一高亮，其祖先分组与模块强制可见。

- **筛选**：顶部筛选框按标题 / id / 路径 / 描述 / 标签子串匹配；`/` 聚焦筛选框（侧栏若收起会先展开再聚焦），无结果显示空态与清除入口。命令面板（`⌘K`）用独立的模糊匹配，与侧栏筛选状态互不影响。
- **折叠 / 展开**：点组头折叠分组、点模块 caret 展开其能力（搜索时全部展开）；分组折叠态持久化（`showcase.navGroups`），模块展开为内存态。
- **桌面折叠整条侧栏**：顶栏 `☰`（桌面亦常驻，折叠后随时可恢复），持久化（`showcase.navCollapsed`）。
- **移动抽屉**：顶栏 `☰` 开合；`Esc` / 点遮罩 / 点导航项关闭，关闭后焦点归还；收起或隐藏的侧栏设 `inert`——不可 Tab、不被读屏。
- **收藏**：能力可收藏，置顶「收藏」虚拟分组（`showcase.favorites`，仅存能力 id）。
- **密度**：底部「紧凑 / 舒适」切换（`showcase.density`）。

其它快捷键：`⌘K` 命令面板、`⌘J` 请求历史、`⌘/` 快捷键帮助、`⌘⇧L` 主题、`⌘⇧D` 密度。

## 配置项（VITE_*，见 .env.example）

| 变量 | 默认 | 说明 |
|---|---|---|
| `VITE_EDGE_BASE_URL` | 空(同源/代理) | 业务网关基址；跨域分离部署必填 |
| `VITE_AUTH_BASE_URL` | 空(同源/代理) | 登录端点 `/auth/*` 基址；跨域时需 `SameSite=None;Secure` |
| `VITE_BASE` | `/` | SPA 部署子路径 |
| `VITE_CATALOG_URL` | 内置 `catalog.json` | 改为后端动态目录接口 |
| `VITE_LIVE_DISCOVERY` | `true` | 是否启用 live discovery |
| `VITE_REQUIRE_LOGIN` | `true` | 强制登录守卫；`false` 退回纯 API Key 老流程（回滚开关） |
| `VITE_SHARED_KB_UI_ENABLED` | `true` | 共享库双视图；`false` 只留租户库。仍取决于 `/rag/config.publicEnabled` |
| `VITE_DEMO_LOGIN_ENABLED` | `true` | 演示账号一键登录卡；生产置 `false` |
| `VITE_DEMO_PASSWORD` | 空 | demo 一键登录口令（仅内部/本地注入）；**生产绝不设置**——为空则产物不含任何明文口令 |
| `VITE_AUTH_MODE` | `apikey` | 认证模式：`apikey`(默认) / `oidc` / `dual`（Casdoor OIDC 灰度开关，见上节） |
| `VITE_CASDOOR_ISSUER` | `http://localhost:8000` | Casdoor issuer（oidc-client-ts authority，自动 discovery） |
| `VITE_CASDOOR_CLIENT_ID` | `ragshared0client00000001` | Casdoor **Shared Application** 的 base client_id（方案C）；每租户实际以 `<base>-org-<tenant>` 登录。须与边缘 `edge.casdoor.audiences` 的 **base 一致**（边缘按 `<base>-org-*` audience 家族 + `(owner,aud)` 绑定放行，非逐字相等） |
| `VITE_CASDOOR_SCOPES` | `openid profile email offline_access` | OIDC 请求 scope（业务 scope 由 Casdoor permissions claim 展开，不在此请求） |
| `VITE_CASDOOR_REDIRECT_PATH` | `callback` | 回调 SPA 路径（须在 Casdoor redirectUris 登记） |
| `VITE_CASDOOR_POST_LOGOUT_PATH` | `login` | 登出后回跳 SPA 路径 |
| `VITE_CASDOOR_SILENT_PATH` | `oidc-silent` | 静默续期隐藏 iframe 回调路径（刻意无扩展名以走 SPA fallback） |

> kill switch 语义为"只可强制关闭"：即便置 true，能力是否真正可用仍由服务端 scope / 运行时配置决定。

## 能力目录

`capabilities.yml` 是目录的**唯一事实源**（能力的方法/路径/参数/scope/feature-flag/风险/五态）。`npm run gen:catalog`（dev/build 前自动执行）将其转为 `public/catalog.json`。新增/调整能力改这个文件即可。

**覆盖范围**：9 个模块、82 条能力（以 `public/catalog.json` 生成结果为准），端点/入参对照真实 controller 与 protocol record 核验。
- P0：Chat（含 auto/cascade/mcp/memory 等高级）、RAG（含 graph/obsidian/image）、Agent（含 dag/chain/vote/reflexive/analyst/process）、Async、Analytics。
- P1/P2：Workflow（审批串联）、Multimodal（图像/语音，含 `multipart-sse` 的 `/voice/chat/stream`）、Interop & Eval（MCP/A2A + 评测，gate 的 422 作为业务门禁结果展示）、Channel（出站默认锁定 + 回调 header 注入）。

模块以 `GenericModuleView` 数据驱动为底；`workflow/multimodal/interop-eval/channel` 有领域专用工作台视图（`ModuleHost.SPECIALIZED`），复用共享 `CapabilityRunner`/执行闸门，不绕过安全裁决。复杂/嵌套请求体（eval cases、dag tasks、mcp arguments、a2a params）以 `type:json` 原样 JSON 字段承载。

## 测试

```bash
npm test           # vitest：SSE 解析 / API Key 脱敏 / 动态表单校验 / catalog 回退 / 执行闸门 /
                   #        登录守卫 / 权限裁决
npm run type-check # vue-tsc
npm run build      # 生产构建（不设 VITE_DEMO_PASSWORD 时产物不含任何明文口令）
```

能力→前端模块拆分说明见仓库 `docs/平台工程/能力前端模块拆分建议.md`。
