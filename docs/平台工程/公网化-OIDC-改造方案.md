# 能力展示控制台公网化 OIDC 改造方案（前端 + 后端）

> 状态：**方案草案（待评审 / 待拍板，获批前不改任何代码）**
> 关联：[能力展示控制台](能力展示控制台.md)。本方案把「顶栏贴 `X-Api-Key`、仅存内存」的 direct-mode 鉴权升级为标准 OIDC（Authorization Code + PKCE），面向公网多用户。
> 结论先行的关键事实：**下游服务零改动**——`edge-gateway` 的 `ApiKeyToInternalTokenFilter` 是全平台唯一签发内部 JWT（`X-Internal-Token`）的地方；下游只信内部 JWT。OIDC 改造只需在**边缘替换「入站凭证」**：把「校验静态 `X-Api-Key`」换成「校验 IdP 的 Bearer access token」，之后**仍 mint 现有内部 JWT**，`EdgeRateLimitFilter` / `InternalTokenAuthFilter` / `TenantContext` / scope / 限流桶全部不变。

> **v2（已纳入独立评审修订）**：本稿已修正以下评审确认项——
> - **C1（CORS）**：撤回原「`allowedHeaders:"*"` 不覆盖 `Authorization`」的判断。Spring Cloud Gateway 对 `"*"` 会**反射回显**请求头，`Authorization` 与现有自定义业务头（`X-Async-Task-Id/-Status`、`X-Workflow-Instance-Id/-Status`、`X-Channel-Signature`）均已被覆盖；**保持 `"*"` 不动**，若收紧为显式白名单**必须**列全这些头，否则回归 async/workflow/channel 能力。降级为「跨域预检验证项」，非致命。
> - **C2（dual-accept 防伪，安全高危）**：`InternalTokenAuthFilter` 对伪造/失效内部 JWT **不 401、静默放行不绑 context**（`InternalTokenAuthFilter.java:40-62`），故边缘的 401 是**唯一**真实闸门。dual-accept 不能靠「`X-Internal-Token` 存在」判定——必须**无条件剥离入站 `X-Internal-Token`/`Authorization`**，Bearer 校验成功用 `exchange` 属性传信号。
> - **C3（存储）**：区分**瞬态流程态**（PKCE `code_verifier`/`state`/`nonce`，redirect 流必须落 `sessionStorage`、回调后自动清）与**令牌**（仅内存）。
> - **C4**：补 `curl.ts`（`oidc` 模式预览要出 `Authorization: Bearer`）与遗漏的文案改写点。
> - **S1/S3/S4**：BFF 提升为「公网敏感数据」的首选并设 go/no-go 闸；后端只引 `spring-security-oauth2-jose` 手搓 `NimbusReactiveJwtDecoder`（**不** `@EnableWebFluxSecurity`）；`audience` 在 `oidc` 模式实际必填，opaque token 需 introspection 兜底。

---

## 0. Goals / Non-Goals

**Goals**
- 面向公网多用户：用户经 IdP 登录（Authorization Code + PKCE，公有 SPA 客户端），浏览器以 `Authorization: Bearer <access_token>` 跨域调 `edge-gateway`，取代手工 `X-Api-Key`。
- 后端在边缘校验 IdP token（JWKS：issuer/audience/exp/签名），把 token claims 映射为 `TenantContext.Tenant(tenant, user, scopes)`，**继续 mint 现有内部 JWT**，下游零改动。
- 前端补齐登录/回调/登出/令牌生命周期/路由守卫/请求层 Bearer 注入（含 fetch-based SSE），并与现有 `session store` / `CapabilityRunner` / 执行闸门 `gate.ts` 平滑衔接。
- **可灰度、可秒回滚**：`VITE_AUTH_MODE = apikey | oidc | dual`，网关侧 Bearer 校验**增量叠加**在 `X-Api-Key` 之上（dual-accept），机器客户端/CI 继续用 API key。

**Non-Goals**
- 不改任何下游业务 controller / DTO / 表 / 消息结构。
- 不引入展示后端服务（除非选 BFF 变体，见决策 D1，本期不做）。
- 不做 IdP 选型采购（本方案以「标准 OIDC/OAuth2.1 兼容 IdP」为契约，Keycloak/Auth0/Entra ID/Cognito 皆可）。
- 不实现细粒度授权中心（scope 仍沿用现有 `chat/ingest/approve/agent/channel/eval/vision/voice`）。

---

## 1. 决策记录（Decision Record）

### D1. 在哪里校验 IdP token —— 网关校验 Bearer（推荐，本期） vs BFF（更安全，二期）

| 方案 | 做法 | 优点 | 代价 / 风险 |
|---|---|---|---|
| **A. 网关校验 Bearer（公有 SPA）✅ 本期推荐** | SPA 持 access token（内存）；新增边缘 filter 校验 Bearer（JWKS）→ 映射 claims → **mint 现有内部 JWT** | 完全贴合现有无状态两层网关；**下游零改动**；增量叠加、可 dual-accept、可秒回滚；无新服务 | access token 落到浏览器（XSS 面）；刷新页面需静默续期；跨站 IdP 的 silent-iframe 受三方 cookie 淘汰影响（用 refresh-token 轮换规避） |
| **B. BFF（Backend-For-Frontend）** | 新增一个机密客户端服务，服务端持 token，浏览器只拿 httpOnly+SameSite 会话 cookie | token 不进浏览器（最稳，符合 OAuth for Browser-Based Apps BCP）；刷新不掉登录 | 需**新增有状态服务** + CSRF + `allowCredentials:true` + CORS 收紧为具体源；违背当前「无展示后端、无状态、无 cookie」设计；改动面大 |

**推荐（v2 修订，含 go/no-go 闸）**：先回答 **Q4「数据敏感度」**再定主方案——
- **公网承载真实租户敏感数据 → 直接上 B（BFF）为首选**。本应用把模型输出当 Markdown/HTML 渲染（`marked`+`dompurify`），是活的 XSS 面；把 access/refresh token 放浏览器正是 *OAuth 2.0 for Browser-Based Apps* BCP 明确劝退的反模式（一次 sanitizer 绕过 = 持久令牌被盗，而非仅当前标签页）。
- **仅低敏感度 demo/试用 → 走 A（公有 SPA）**，且**不在 JS 里放可轮换 refresh token**：用**短时 access token + 到期重登**，把浏览器内的凭证价值降到最低。

二者的**网关下游契约完全一致**（都产出同一枚内部 JWT），A→B 升级不影响任何下游；因此可先按 A 快速验证体验、把 B 作为「转正式公网」的硬化目标。本方案正文以 A 展开（前端契约对 B 也复用），**B 的差异点见 §9**。

### D2. 令牌存储 —— 令牌仅内存；流程态用 sessionStorage（v2 修订，纠正自相矛盾）
- **令牌（access/refresh/id token）仅内存**，绝不落 localStorage/sessionStorage/URL/日志（延续 `session.ts:7-11` 硬约束）。用自定义 in-memory `userStore`。
- **瞬态 OIDC 流程态另论**：Authorization Code **redirect 流**会把 SPA 整个卸载再从 IdP 跳回，PKCE 的 `code_verifier` / `state` / `nonce` **必须跨这次导航存活** → `oidc-client-ts` 默认放 `sessionStorage`（`stateStore`），回调 `signinCallback()` 后自动清除。这是标准、短寿、可接受的，**不要**把它也清零（否则 code 交换失败）。原 D2「绝不落 sessionStorage」是对令牌而言，不含流程态——本条已澄清二者分工。
- **续期**：A 方案下——低敏感度取「短时 access token + 到期交互重登」（不放 refresh token，最稳）；若确需静默续期，用 `refresh_token`（内存、轮换）。**注意（S2）**：硬刷新丢内存 token 后的「静默恢复」走 `prompt=none`，**仍依赖 IdP 会话 cookie**；若 IdP 与前端不同注册域，Safari ITP/Chrome 三方 cookie 淘汰会让静默失败 → 退化为**交互式重登**。要「刷新不掉登录 + 静默续期稳」→ 上 BFF（D1-B）或让 IdP 与前端同注册域（记入 Q1/Q6）。
- **不**为免重登把令牌写 localStorage —— 会把 XSS 从「偷当前标签页」放大为「偷持久令牌」（R4）。

### D3. OIDC 客户端库 —— `oidc-client-ts`（推荐）
- 框架无关、纯 ESM、活跃维护；内置 PKCE / state / nonce / discovery / JWKS / 静默续期 / refresh 轮换。与本仓 Vue 3.5 + vite 6 + `"type":"module"` + `moduleResolution:"Bundler"` 天然契合（无既有 auth 依赖，`crypto.subtle` 可用）。
- 备选：手搓 PKCE（代码多、CSRF/nonce 易错，否决）；`vue-oidc-client`/`@axa-fr` 等 Vue 包装（黑盒多、与自定义 fetch 注入耦合，否决）。
- 用一层**薄 Pinia `auth` store** 包 `UserManager`，不让库 API 渗进业务代码。

### D4. 灰度与回滚 —— `VITE_AUTH_MODE` 三态 + 网关 dual-accept
- 前端：`apikey`（默认，现状）｜`oidc`（Bearer）｜`dual`（都带，便于迁移期）。构建期 env，回滚=改 env 重构建（同 `VITE_EDGE_BASE_URL` 机制）。
- 后端：新增 Bearer 校验分支**叠加**在 `X-Api-Key` 之上（两者命中其一即放行并 mint 内部 JWT）。**先上后端 dual-accept，再把前端切 `oidc`**（顺序约束，见 §8）。

### D5. 闸门（gate）scope 模型 —— 维持「反应式 403」为准，claims 仅作提示
- 现状 `gate.ts` 明确注释「key 不透明、无法预判 scope、403 反应式处理」。OIDC 后 token 里能读到 scopes，但**客户端预判的 scope 可能与网关的 claim→scope 映射漂移**（R7）。
- 决策：**不**用客户端 claims 硬禁用能力；仅把「你可能缺 X scope」作为 `GateResult.hint`（advisory）。真授权判定仍以网关 403 为准。改动最小、无误禁/误放。

### D6. claims → tenant/scope 映射（后端，可配置）
- `tenant` ← 可配置 claim（如 `org_id` / `tenant` / `groups[0]`）。
- `scopes` ← 可配置 claim（OAuth `scope` 空格串 / `scp` / 角色 claim `roles`），再经**平台 scope 映射表**归一到 `chat/ingest/approve/...`。
- `user` ← `sub`（或 `email`/`preferred_username`，可配）。
- 提供默认 demo 映射；无匹配 claim 时给一个「默认租户 + 最小 scope」兜底（或拒绝，可配）。

---

## 2. 路由与页面流（Routes & Page Flows）

现有路由（`src/router/index.ts`，`createWebHistory(BASE_URL)`，无守卫）：`/`、`/m/:moduleId`、`/m/:moduleId/:capId`、catch-all→`/`。

**新增/变更**
- 新增 `/auth/callback`：处理 IdP 重定向回调（消费 `code`+`state`，换 token，校验 `state`/`nonce`，还原 deep-link）。经 nginx `try_files ... /index.html` 静态兜底可达，无需额外 location。
- **执行的真实闸门在 `gate.ts`，不在路由（N2 澄清）**：「执行」是 `ModuleHost` 里的按钮点击、不是路由跳转，`router.beforeEach` 无法拦它——所以**不靠守卫挡执行**，沿用 `gate.ts` 的 `hasApiKey→isAuthenticated` 布尔在按钮层拦。匿名可浏览 `/`、`/m/*`（目录零后端、保持公开）；deep-link `/m/:moduleId/:capId` 未登录也能**打开查看**（表单/curl/示例），点「执行」才触发登录。`beforeEach` 仅用于「若配置为全站强制登录」这类可选策略；`/auth/callback` 就是个普通路由组件。
- 可选 `/auth/silent`（仅当退回 silent-iframe 方案时需要 `public/silent-renew.html`；用 refresh-token 轮换则**不需要**）。

**页面流**
1. 匿名进入 → 目录照常加载（`catalog.load()` 无需 token）→ 可浏览/看 curl/填示例。
2. 点「执行」（或登录按钮）→ `auth.login(returnTo=当前 fullPath)` → 跳 IdP → 回 `/auth/callback` → 换 token → **还原 returnTo deep-link** → 自动触发/解锁执行。
3. 令牌近失效 → 后台静默续期（refresh 轮换），用户无感。
4. 续期失败 / 401 → 顶栏转「已登出」，能力执行被闸门拦下并提示「请重新登录」。
5. 登出 → `auth.logout()` → 清内存 token + `history.clear()` → 可选跳 IdP end-session。

---

## 3. 组件树与状态（Component Tree & States）

**顶栏（`AppHeader.vue:82` 单一挂载点，全断点常显）**
- `apikey`/`dual` 模式：保留现 `ApiKeyInput.vue`。
- `oidc` 模式：替换为 `AuthControl.vue`（新）
  - 未登录 → `登录` 按钮
  - 已登录 → 用户身份 chip（`email`/`name`，复用 `.apikey__chip[data-set]` 视觉；身份**不脱敏**）+ `登出`
  - 续期中/回调中 → 复用 `EmptyState variant="loading"` 的语汇

**全局挂载（`App.vue:66-68` 同层）**
- `SessionExpiredDialog.vue`（新，可选）：会话过期/续期失败时的模态。**复用 `useFocusTrap` + `ShortcutsDialog`/`CommandPalette` 的 dialog 结构**，并纳入 `stores/ui.ts` 的互斥开关（新增 `authModalOpen`，遵循现有 open* 互斥，避免多层焦点陷阱）。

**新增交互态（今仓无）→ 复用现有语汇**

| 状态 | 复用/落点 |
|---|---|
| 未登录（默认） | 顶栏 `登录` 按钮；各模块 `!isAuthenticated` 提示（原 `!hasApiKey` 文案改写） |
| 跳转 IdP 中 | `EmptyState variant="loading"`「正在跳转登录…」 |
| 回调处理 `/auth/callback` | 整屏 `EmptyState variant="loading"`「正在完成登录…」 |
| 已登录（带身份） | 顶栏身份 chip |
| 静默续期 | 无感；失败才提示（无 toast 体系，走 InfoNote/模态） |
| 过期/续期失败 | `SessionExpiredDialog` + `humanizeError(401)` 改写文案 |
| scope 不足 403 | `humanizeError(403)` 改写（去掉「更换 Key」，改为「当前账号缺少 X 权限」） |
| 登出 | 顶栏 `登出` |

> 注意（copy 清单，全部 load-bearing）：`gate.ts:39` 的「请先在顶栏填写 API Key…」、`errors.ts:51-57` 的 401/403 文案、7 个模块视图硬编码的「请先在顶栏填写 API Key 才能…」（`AsyncMonitorView/InteropEvalView/RagWorkspaceView/ChannelConsoleView/AnalyticsLabView/WorkflowDeskView`）、`ChatConsoleView` 画像提示，**外加评审补漏（C4）**：`CapabilityHeader.vue:56`、`RagWorkspaceView.vue:495`、`WorkflowDeskView.vue:280`、`AnalyticsLabView.vue:379`、`ChannelConsoleView.vue:226-228`、`CapabilityRunner.vue:211`——`oidc` 模式**逐条改写为「请先登录」**语义。落地前应再 `grep -rn "API Key\|X-Api-Key\|scope"` 收一遍。建议抽 `authPromptText()` 按 `VITE_AUTH_MODE` 出文案，避免散落。

---

## 4. API 契约（Frontend ↔ Gateway ↔ IdP）

### 4.1 浏览器 → 网关（业务能力）
- Header：`Authorization: Bearer <access_token>`（取代 `X-Api-Key`；`dual` 模式两者都带）。
- 其余不变（路径、body、SSE 走 fetch+ReadableStream）。
- 匿名接口：目录 `catalog.json`（静态、无鉴权）；live discovery（`/agent/capabilities` 等）登录后才带 token。

### 4.2 浏览器 ↔ IdP（OIDC）
- Authorization Code + PKCE，`response_type=code`，`code_challenge_method=S256`。
- `scope`：`openid profile email` + 平台业务 scope（若 IdP 以 scope 承载权限）。
- `redirect_uri = <origin><BASE_URL>auth/callback`；`post_logout_redirect_uri = <origin><BASE_URL>`。
- refresh_token 轮换（公有客户端）。

### 4.3 网关校验 Bearer → 内部 JWT（后端契约，**核心**）
- **前置（C2 安全）**：无条件剥离**入站** `X-Internal-Token`（外部不可自带内部令牌）。
- 校验：签名（IdP **JWKS，惰性 kid 刷新**，见 N4/S3）、`iss`、**`aud`（= 网关/API 资源标识，`oidc` 模式实际必填）**、`exp`/`nbf`（含 clock skew）。
- **S4（token 形态）**：本契约要求 access token 是 **JWT 且可经 JWKS 验签**。部分 IdP（如 Auth0）**不请求 audience 时下发 opaque token**，无法 JWKS 验签 → 那时必须请求 `audience`/`resource` 换 JWT，或退回 **RFC 7662 introspection**（另一套后端，作为兜底/开关）。Phase 1 验收要显式确认「拿到的是可验签 JWT」。
- 映射（D6）：claims → `Tenant(tenant, user, scopes)`。
- 产出：**沿用 `InternalToken.mint(Tenant)`**——`sub=tenantId`、`uid=userId`、`scopes=[...]`、`exp=now+ttl`，注入 `X-Internal-Token`，剥掉入站 `Authorization`（不外泄进内网，比照现在剥 `X-Api-Key`）。
- 之后 `EdgeRateLimitFilter`（从内部 JWT 还原 tenant 限流）、下游 `InternalTokenAuthFilter` **完全不变**。

---

## 5. 文件级改动（File-Level Changes）

### 5.1 后端（`edge-gateway` + 少量 `platform-security` 复用）
| 文件 | 改动 |
|---|---|
| `edge-gateway/.../OidcBearerAuthFilter.java`（新，`GlobalFilter`，order -110，**在 `ApiKeyToInternalTokenFilter(-100)` 之前**） | **① 无条件剥离入站 `X-Internal-Token`（防伪，C2 关键）**——外部绝不可自带内部令牌。② 若有 `Authorization: Bearer` → 校验 IdP JWT（JWKS）→ 映射 claims → mint 内部 JWT 注入 `X-Internal-Token` → 剥 `Authorization`（`dual` 模式再剥 `X-Api-Key`）→ `exchange.getAttributes().put("oidc.authenticated", true)` → 放行。③ **无 Bearer 则原样透传**（已剥内部令牌），交给 `ApiKeyToInternalTokenFilter` 走 `X-Api-Key`。校验失败（错 `iss/aud`、过期、验签失败）→ 401。|
| `edge-gateway/.../ApiKeyToInternalTokenFilter.java` | 微调：**判定依据是 `exchange` 属性 `oidc.authenticated==true`（不是 `X-Internal-Token` 是否存在——那可被伪造）**；为 true 则直接放行；否则维持现逻辑（要求合法 `X-Api-Key`，否则 401）。`isOpen()` 不变。⚠️ `InternalTokenAuthFilter.java:40-62` 对伪造内部 JWT 不 401、静默不绑 context，故边缘 401 是唯一闸门——绝不能因「有 `X-Internal-Token`」就放行。|
| `edge-gateway/.../OidcProperties.java`（新，`platform.security.oidc.*`） | `enabled`、`issuer-uri`、`jwks-uri`（可从 issuer discovery 推导）、`audience`、`clock-skew`、`claims.tenant/user/scope`、`scope-mapping`、`default-tenant`、`fallback-policy(reject\|default)`。|
| `edge-gateway/.../application.yml` | 1) `platform.security.oidc.*` 默认（`enabled:false`，保持现状可回滚）；2) **CORS：`allowedHeaders` 维持 `"*"`（v2/C1：Spring 会反射回显请求头，已覆盖 `Authorization` 与现有自定义业务头；若收紧为白名单必须列全 `Authorization,X-Api-Key,Content-Type,Accept,Last-Event-ID,X-Async-Task-Id,X-Async-Task-Status,X-Workflow-Instance-Id,X-Workflow-Status,X-Channel-Signature`，否则回归）**；3) `GATEWAY_CORS_ORIGINS` 追加公网前端源。|
| `edge-gateway` 依赖（**S3：不引 resource-server starter**） | 只加 `spring-security-oauth2-jose`（带 `NimbusReactiveJwtDecoder` + nimbus），在 `OidcBearerAuthFilter` 里**手工**构造 decoder：`NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri)` + issuer/audience `OAuth2TokenValidator`。**切勿** `@EnableWebFluxSecurity` / 引 `spring-boot-starter-oauth2-resource-server`——它会自动装配一条 `SecurityWebFilterChain`，接管路由/CORS/CSRF，和现有 `globalcors` 与 `isOpen()` 白名单（health、`/.well-known`、飞书/钉钉回调）打架。kid 级 JWKS 轮换由 decoder 惰性处理。|
| `platform-security`（复用，不改契约） | `InternalToken.mint(...)`、`TenantContext.Tenant` 直接复用（`InternalToken` 已支持 HS256/RS256）。|
| `deploy/docker-compose.yml` | edge-gateway 增 `PLATFORM_SECURITY_OIDC_*` env；`GATEWAY_CORS_ORIGINS` 增公网源；前端 service 增 OIDC `build.args`。|

### 5.2 前端（`capability-showcase-frontend`）
| 文件 | 改动 |
|---|---|
| `package.json` | 加 `oidc-client-ts`（生产依赖）。|
| `src/config.ts` + `env.d.ts` + `.env.example` + `Dockerfile` + compose build.args | 新 `VITE_AUTH_MODE`、`VITE_OIDC_ISSUER`、`VITE_OIDC_CLIENT_ID`、`VITE_OIDC_SCOPES`、`VITE_OIDC_AUDIENCE`（可选）、`VITE_OIDC_REDIRECT_PATH`（默认 `auth/callback`）。构建期烘焙（同 `VITE_EDGE_BASE_URL`）。|
| `src/stores/auth.ts`（新，setup-store） | 包 `UserManager`；state：`user/accessToken/expiresAt/status`；`isAuthenticated`、`login(returnTo)`、`handleCallback()`、`logout()`、`renew()`、`getToken()`。|
| `src/stores/session.ts` | `runContext()` 增 `accessToken`（`RunContext` 加字段）；`hasApiKey` 语义在 `oidc` 模式由 `auth.isAuthenticated` 提供（保持布尔契约，减小爆炸半径）。|
| `src/types/api.ts` | `RunContext` 加 `accessToken?: string`。|
| `src/api/client.ts` | 注入点（`:108-111`）按 `VITE_AUTH_MODE`：`oidc`→`Authorization: Bearer`；`apikey`→`X-Api-Key`；`dual`→都带。`buildHeaderParams` 的「禁止用户 header 参数覆盖」保护**从 `X-Api-Key` 扩到 `Authorization`**。|
| `src/api/sse.ts` | 同注入点（经 `assembleRequest`，天然覆盖）；流启动前主动续期；中途 401-close → 携 `Last-Event-ID` 用新 token 重订。|
| `src/api/catalog.ts` | **第二注入点**：`discoverLive`（`:108`）手搓 header 也要改成经统一 `authorizedFetch`/注入 Bearer（易漏，R11）。`fetchCatalog` 维持匿名。|
| `src/api/authorizedFetch.ts`（新，**唯一 fetch 收口**） | 包三处 `fetch`（client/sse/catalog-discovery）：注入 auth header；401→单飞刷新（去重，防 stampede R2）→重试一次；刷新失败→登出/引导重登；尊重 `AbortSignal`。**N3**：`discoverLive` 是 best-effort，其 401 **不得触发** refresh→logout 级联（匿名/早加载期尤甚），保持静默降级到 manifest。用 `authorizedFetch(url, { silent: true })` 之类开关区分。|
| `src/router/index.ts` | 加 `/auth/callback` 路由 + `beforeEach`（只守执行，不守浏览）；deep-link returnTo 还原。|
| `src/main.ts` / `src/App.vue` | 启动时 `auth.init()`（尝试静默恢复 + 处理回调）；非阻塞，目录照常先渲染。|
| `src/utils/gate.ts` | `GateContext` 加可选 `scopes?: string[]`；`scope-required` 时若 claims 明确缺失 → 追加 advisory `hint`（不改 `allowed`，D5）。`!hasApiKey` 文案改写。|
| `src/utils/errors.ts`（`api/errors.ts`） | 401/403 文案按 `VITE_AUTH_MODE` 改写（「重新登录」/「账号缺少 X 权限」）。|
| `src/utils/curl.ts`（**C4，原稿遗漏**） | `oidc` 模式预览要出 `-H 'Authorization: Bearer $ACCESS_TOKEN'`（占位符，绝不用真令牌）；`apikey`/`dual` 维持 `X-Api-Key: $API_KEY`。否则展示工具的可复制 curl 与实际请求头不一致。|
| `src/components/layout/AuthControl.vue`（新） | 顶栏登录/身份/登出；`oidc` 模式替换 `ApiKeyInput`。|
| `src/components/common/SessionExpiredDialog.vue`（新，可选） | 过期模态，复用 `useFocusTrap` + `ui.ts` 互斥。|
| 7 个模块视图 + `CapabilityRunner.vue` | 硬编码「填写 API Key」文案改走 `authPromptText()`；逻辑仍读布尔 `hasApiKey/isAuthenticated`，改动极小。|

---

## 6. 依赖顺序的实施步骤（Dependency-Ordered Steps）

> 原则：**后端先具备 dual-accept，前端才敢切 `oidc`**（否则 Bearer 请求被网关 401）。全程 `apikey` 保持可用。

**阶段 0｜准备（无行为变化）**
1. 选定并接好 IdP（建 SPA 公有客户端、redirect_uri、scope、refresh 轮换）。产出 issuer/JWKS/client_id/allowed origins。
2. 网关 CORS 修正：`allowedHeaders` 显式列 `Authorization`（R1），追加公网源。**用真实跨域预检验证**（dev vite 代理会掩盖 CORS 问题）。

**阶段 1｜后端 dual-accept（默认关，增量）**
3. 加 `OidcBearerAuthFilter` + `OidcProperties`，`platform.security.oidc.enabled=false` 默认。
4. 打开 `enabled=true` 于预发：**先确认拿到的是可 JWKS 验签的 JWT**（否则需请求 audience 或改 introspection，S4）；用 IdP 真 token curl 网关，断言换发内部 JWT、下游/限流正常；**伪造 `X-Internal-Token` 直连必须被 401（C2）**；无 Bearer 时 `X-Api-Key` 照旧。补网关侧单测（JWKS 校验、claim 映射、dual-accept、伪造内部令牌拒绝、失效/错 aud/过期）。

**阶段 2｜前端 OIDC（`VITE_AUTH_MODE=oidc`，仅预发）**
5. 加 `oidc-client-ts` + `auth` store + `authorizedFetch` 收口（先不接 UI）。
6. 请求层三处注入 Bearer + 401 单飞刷新；`RunContext` 加 `accessToken`。
7. 路由 `/auth/callback` + 守卫 + deep-link returnTo；`main.ts` `auth.init()`。
8. 顶栏 `AuthControl` + 文案改写 + `SessionExpiredDialog`。
9. SSE 中途过期重订；`logout` 清 `history`。

**阶段 3｜联调 / 灰度 / 上线**
10. 预发端到端：登录→执行→续期→过期→重登→登出→deep-link 还原；scope 不足 403 文案；匿名浏览仍可用。
11. 生产灰度：前端 `dual` 观察 → 切 `oidc`；后端保留 `X-Api-Key`（CI/机器客户端）。
12. 文档更新（`能力展示控制台.md` 的鉴权/回滚章节、README、`.env.example`）。

---

## 7. 测试策略（Test Strategy，对齐现有 Vitest 套路）

**复用现有套路**：`vi.stubGlobal('fetch', vi.fn())` 返 `Response`-like（`catalog.test.ts:76-92`）；`setActivePinia(createPinia())` + 直接 set store；视图 `mount` + `loadCatalog()` 断言闸门文案；SSE 用 `ReadableStream`+`TextEncoder`（`sse.test.ts`）；纯函数（gate/assembleRequest）直测。

**新增单测**
- `auth` store：`isAuthenticated` 迁移；用**未签名假 JWT fixture**（仅 base64url payload，SPA 不验签）算 `exp`/skew；`vi.useFakeTimers()` 断言续期调度。
- 令牌注入：`authorizedFetch`/`assembleRequest` 按 `VITE_AUTH_MODE` 出 `Authorization`/`X-Api-Key`/两者；**扩展「用户 header 参数不得覆盖 auth header」不变量**到 `Authorization`（现有 `X-Api-Key` 不变量在 `client.ts:79-89`）。
- 401 单飞刷新：首请求 401→刷新→重试带新 token；**并发 N 路 401 只刷新一次**（stampede，最高价值用例）；刷新失败不无限重试、触发登出；`AbortSignal` 期间不重试。
- SSE 中途过期：命名 `error` 事件 → `onDone('error')` → 可用新 token + `Last-Event-ID` 重订（`AsyncMonitorView` 的 `lastEventId/subscribes` 已有支点）。
- 路由守卫：匿名可浏览目录/模块/能力详情；未登录点执行→存 returnTo→登录→回调还原**同一 deep-link**；回调 `state` 不匹配即拒（CSRF）。
- 闸门/文案：`gate.ts` claims 提示（advisory，不改 `allowed`）；`errors.test.ts` 401/403 新文案。
- 登出完整性：清 token **且** `history.clear()`；favorites（仅 id）保留。
- 回归护栏：`VITE_AUTH_MODE=apikey` 下所有既有测试**保持绿**（108 个）。
- 环境坑：PKCE 用 `crypto.subtle`，jsdom 可能缺 → 相关测试文件加 `// @vitest-environment node` 或 webcrypto polyfill（**待验证**，随库定）。

**后端测**：JWKS 校验、`iss/aud/exp/skew`、claim→tenant/scope 映射、dual-accept（有/无 Bearer）、失效/错 aud/过期 → 401；换发内部 JWT 后 `EdgeRateLimitFilter` tenant 还原正常。

---

## 8. 验收标准（Acceptance Criteria）

1. `apikey` 模式：现状与全部既有测试**零回归**。
2. `oidc` 模式：未登录可浏览目录与能力详情、看 curl/示例；点执行触发登录。
3. 登录成功后可执行同步/SSE 能力；access token 近失效自动静默续期，用户无感。
4. deep-link `/m/:moduleId/:capId` 未登录打开 → 登录 → **回到同一 deep-link**。
5. 令牌过期/续期失败 → 明确「请重新登录」，无静默失败、无无限刷新；并发 401 只刷新一次。
6. scope 不足 → 403 文案准确（不再提「更换 Key」）；闸门对已知缺失 scope 给 advisory 提示但不误禁。
7. 登出清内存 token 与 `history`；可选跳 IdP end-session。
8. 网关：Bearer 与 `X-Api-Key` 皆可换发**同一枚内部 JWT**；下游与限流零改动、行为不变。
9. 跨域真实预检通过（`Authorization` 在 `allowedHeaders`）；token 绝不落 localStorage/URL/日志（含 curl 预览/history 脱敏）。
10. 回滚：改 `VITE_AUTH_MODE=apikey` 重构建即恢复；后端 OIDC filter `enabled=false` 即停用，无数据迁移。

---

## 9. 风险登记与回滚（Risk Register & Rollback）

| # | 风险 | 概率/影响 | 缓解 |
|---|---|---|---|
| R1 | ~~CORS `"*"` 不覆盖 `Authorization`~~ **（v2/C1 已撤回：Spring 反射回显请求头，`Authorization` 与自定义业务头均已覆盖）**。真实风险反而是**误把 `"*"` 收紧为不全的白名单**，漏掉 `X-Async-Task-Id` 等 5 个自定义头 → async/workflow/channel 跨域回归 | 低/中 | **保持 `"*"`**；如需白名单必须列全（见 §5.1）；用真实跨域预检验证（dev 代理掩盖 CORS） |
| R1b | **dual-accept 被伪造内部令牌绕过**：`InternalTokenAuthFilter` 对伪造 JWT 不 401 静默放行（`InternalTokenAuthFilter.java:40-62`），若边缘按「`X-Internal-Token` 存在」放行则可被直连伪造头绕过鉴权 | 中/致命 | `OidcBearerAuthFilter` **无条件剥入站 `X-Internal-Token`**；dual-accept 用 `exchange` 属性传信号，非 header 存在（C2，§5.1） |
| R2 | 并发能力执行触发**刷新踩踏**（多路 401 各自刷新→IdP 限流/refresh 轮换竞态） | 高/高 | `authorizedFetch` 单飞刷新（共享 in-flight promise）+ 队列重试；stampede 用例覆盖 |
| R3 | **内存令牌 → 刷新页即掉登录**；跨站 silent-iframe 受三方 cookie 淘汰 | 高/高 | refresh-token 轮换静默续期（非 iframe）；接受硬刷新触发一次静默重登；要「刷新不掉」则上 BFF（D1-B） |
| R4 | XSS → 令牌被盗（应用渲染模型输出的 Markdown/HTML，`marked`+`dompurify`） | 中/致命 | 令牌**不入** localStorage/sessionStorage；发版前复审 DOMPurify 配置；`redact()` 也脱敏 bearer（curl/history） |
| R5 | **SSE 长流中途 access token 过期**（`sse.ts:171` 启动时快照）被服务端断开 | 中高/高 | 起流前主动续期；断开→携 `Last-Event-ID` 用新 token 自动重订（`AsyncMonitorView` 已有支点）；封顶重连次数 |
| R6 | 登录后**丢 deep-link**（无守卫、catch-all 回 `/`） | 中/中 | **优先用 `oidc-client-ts` 的 request `state`**（随 IdP 往返、回调时取回）承载 returnTo，回调 `router.replace(returnTo)`；避免自建 `sessionStorage`（N1，且不与 D2 冲突） |
| R7 | 闸门 scope 预判与网关映射**漂移**→误禁/误放 | 中/中 | claims 仅 advisory hint，不改 `allowed`；403 为准（D5） |
| R8 | 登出**残留内存 history**（含 prompt/PII） | 中/中 | `logout()` 调 `history.clear()`；favorites（仅 id）保留 |
| R9 | **多标签页登录/登出不同步**（无 storage 监听） | 中/低中 | 广播「登出信号」（不广播 token 本身）经 `storage` 事件，各页重置 auth |
| R10 | 过宽守卫**破坏匿名浏览**（目录零后端、browse-first 承诺） | 中/中 | 守卫只守执行、绝不守目录/路由；IdP 不可达时断言目录仍可加载 |
| R11 | `discoverLive` **第二注入点**被漏改→ live discovery 静默退回 manifest 掩盖 bug | 中/低 | 收口到 `authorizedFetch`；断言 discovery 带 Bearer |
| R12 | **时钟偏移**导致续期过晚/空转 | 低中/中 | `exp−skew`（30–60s）续期 + 401 重试兜底；fake timer 测 |
| R13 | jsdom 缺 `crypto.subtle` 致 PKCE 测试报错 | 低/低（仅测试） | 相关测试 `@vitest-environment node` 或 webcrypto polyfill |
| R14 | `state`/`nonce` CSRF/重放 | 低（用库）/高 | 用 `oidc-client-ts`；断言 `state` 匹配 + `nonce` 校验 |

**回滚**：前端 `VITE_AUTH_MODE=apikey` 重构建即回现状（构建期 env，同 `VITE_EDGE_BASE_URL`）；后端 `platform.security.oidc.enabled=false` 停用 Bearer 分支，`X-Api-Key` 路径始终在。**因 OIDC 相关状态全不持久化、下游零改动，无任何数据/消息迁移**。二期若上 BFF，因下游契约（内部 JWT）不变，同样不触碰业务服务。

### 9.1 BFF（D1-B）变体差异（S1，供公网敏感数据场景直接采用）
- **新增一个机密客户端服务**（可并入 edge-gateway 或独立），承载 OIDC 机密流：服务端持 access/refresh token（服务端存储/加密），浏览器只拿 **httpOnly + `SameSite=Lax/Strict` 会话 cookie**。
- **令牌不进浏览器** → 消除 R3/R4（XSS 偷令牌）主要风险；刷新页面不掉登录（会话在服务端）。
- 差异点：`allowCredentials:true` + CORS 源收紧为**具体源**（不能 `"*"` 配合 credentials）；需 **CSRF 防护**（同源或双提交 token）；前端请求带 `credentials:'include'`，注入点从 `Authorization` 头改为「靠 cookie」（`client.ts`/`sse.ts` 改 `credentials`，无需带令牌头）。
- **下游契约不变**：BFF 拿服务端 token 校验后，仍 mint 同一枚内部 JWT 转发——`§4.3` 之后的一切照旧。故 A→B 迁移是「换边缘凭证承载方式」，业务服务零改动。
- 代价：引入有状态服务 + cookie/CSRF/会话存储，违背当前「无展示后端、无状态」，改动面显著大于 A。

---

## 10. 假设与待澄清（Assumptions & Open Questions）

**假设**（未确认，按最合理默认推进，需 owner 确认）
- A1：本期走公有 SPA（D1-A），非 BFF。
- A2：令牌内存 + refresh 轮换（D2），接受硬刷新触发静默重登。
- A3：网关 Bearer 校验**增量叠加**在 `X-Api-Key` 之上（dual-accept），先后端后前端。
- A4：匿名保留目录浏览 + curl 预览；仅「执行 / live discovery」需登录。
- A5：闸门维持反应式 403，claims 仅提示（D5）。

**待澄清**（阻塞点，需拍板）
- Q1：**IdP 选型**？issuer / JWKS / client_id / 允许的 redirect_uri 与 origin。
- Q2：**哪个 claim 承载 tenant**？哪个承载 scope（`scope`/`scp`/`roles`）？其取值与平台 `chat/ingest/approve/...` 的映射表？无匹配时兜底=默认租户还是拒绝？
- Q3：`audience`（网关/API 资源标识）如何定？IdP 是否给公有客户端签发**可轮换 refresh token**？
- Q4：是否要「刷新页面不掉登录」？若强需求 → 直接上 BFF（D1-B），本方案前端契约不变、后端换实现。
- Q5：登出是否需要 IdP 全局 end-session（单点登出），还是仅清本地会话？
- Q6：公网前端**部署域名/子路径**（定 `VITE_BASE`、redirect_uri、CORS 源、CSP `connect-src/frame-src`）。
- Q7：是否需要为公网 SPA 增加 **CSP**（现 `nginx.conf` 无任何安全头）？建议同期补 `connect-src`（IdP+JWKS+网关）、`frame-src`（IdP，若用 iframe）、`frame-ancestors 'self'`。

---

## 11. 附：为何「下游零改动」成立（代码依据）

- `ApiKeyToInternalTokenFilter`（order -100）是**唯一**把入站凭证换成内部 JWT 的地方；`isOpen()` 放行 `/actuator`、`/.well-known`、`/health`、飞书/钉钉回调，其余必须有凭证。
- `EdgeRateLimitFilter`（order -90）从 `X-Internal-Token` 还原 tenant 限流——只要仍 mint 内部 JWT，限流不变。
- `InternalToken.mint(Tenant)`：`sub=tenantId`、`uid=userId`、`scopes`、`exp`；HS256（默认共享密钥）或 **RS256（已支持，`platform.security.jwt.*`）**。
- 下游 `InternalTokenAuthFilter` 只验内部 JWT → 还原 `TenantContext.Tenant(tenant,user,scopes)`。
- ⇒ OIDC 只是在边缘**多接一种入站凭证来源**，产出物（内部 JWT）与下游契约不变。
