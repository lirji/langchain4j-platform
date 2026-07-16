/**
 * 运行期配置，全部来自构建期 Vite 环境变量（VITE_*）。前后端分离的关键解耦点。
 *
 * - EDGE_BASE_URL：业务能力经边缘网关的基址。同源部署留空（走相对路径/dev 代理）；
 *   跨域分离部署设为网关地址（如 https://api.example.com），配合网关 CORS。
 * - CATALOG_URL：能力目录来源。默认取打包进前端的静态 catalog.json（零后端依赖）；
 *   也可用 VITE_CATALOG_URL 指向后端动态目录接口。
 * - LIVE_DISCOVERY_ENABLED：是否启用 live discovery（默认启用）。
 * - AUTH_BASE_URL：登录端点（/auth/*）基址。默认空串（相对路径，走 vite 同源代理 / nginx 同源反代），
 *   使刷新令牌 httpOnly cookie 为第一方 SameSite=Lax，最省心；跨域场景可设为网关地址（需 SameSite=None;Secure）。
 * - REQUIRE_LOGIN：是否强制登录（路由守卫）。默认 true；置 false 退回"纯手输 API Key"老流程（回滚开关）。
 * - SHARED_KB_UI_ENABLED / DEMO_LOGIN_ENABLED：两个构建期 kill switch。
 *   语义为"只可强制关闭"——即便置 true，共享库是否真正可用仍由服务端运行时（rag config）决定；
 *   置 false 则前端彻底隐藏该入口（灰度/回滚）。DEMO_LOGIN_ENABLED 关闭后登录页不内置 demo 密码，避免生产泄露。
 */
const env = import.meta.env

export const EDGE_BASE_URL: string = (env.VITE_EDGE_BASE_URL ?? '').replace(/\/$/, '')

/** 登录端点基址。默认相对路径（同源代理），刷新 cookie 走第一方最稳。 */
export const AUTH_BASE_URL: string = (env.VITE_AUTH_BASE_URL ?? '').replace(/\/$/, '')

/** 是否强制登录（路由守卫）。默认 true；false 时守卫短路，退回纯 API Key 流程。 */
export const REQUIRE_LOGIN: boolean = env.VITE_REQUIRE_LOGIN !== 'false'

export const CATALOG_URL: string =
  env.VITE_CATALOG_URL && env.VITE_CATALOG_URL.length > 0
    ? env.VITE_CATALOG_URL
    : `${env.BASE_URL}catalog.json`

export const LIVE_DISCOVERY_ENABLED: boolean = env.VITE_LIVE_DISCOVERY !== 'false'

/** 共享知识库 UI 开关（默认开；置 false 只展示租户库）。真正可用仍取决于服务端 /rag/config.publicEnabled。 */
export const SHARED_KB_UI_ENABLED: boolean = env.VITE_SHARED_KB_UI_ENABLED !== 'false'

/** 演示账号一键登录开关（默认开，便于内部试用；生产置 false 后登录页不内置 demo 密码/账号）。 */
export const DEMO_LOGIN_ENABLED: boolean = env.VITE_DEMO_LOGIN_ENABLED !== 'false'

/**
 * 认证模式（灰度/回滚开关，构建期烘焙）：
 * - `apikey`（默认）：现状——顶栏手输 X-Api-Key / 账号密码会话 Bearer；不加载 Casdoor OIDC。
 * - `oidc`：Casdoor OIDC 登录（Authorization Code + PKCE），业务请求带 `Authorization: Bearer <casdoor-token>`。
 * - `dual`：迁移期两者都带（edge 侧 Casdoor 优先、api-key 兜底）。
 * 独立于 {@link REQUIRE_LOGIN}（后者是 legacy 守卫开关，不复用）。回滚＝改 env 重构建。
 */
export const AUTH_MODE: 'apikey' | 'oidc' | 'dual' =
  env.VITE_AUTH_MODE === 'oidc' || env.VITE_AUTH_MODE === 'dual' ? env.VITE_AUTH_MODE : 'apikey'

/** 是否启用 Casdoor OIDC（oidc 或 dual 模式）。apikey 模式下前端不初始化 UserManager。 */
export const OIDC_ENABLED: boolean = AUTH_MODE !== 'apikey'

/** Casdoor issuer（oidc-client-ts authority，自动 discovery）。dev 默认本地 docker Casdoor。 */
export const CASDOOR_ISSUER: string = (env.VITE_CASDOOR_ISSUER ?? 'http://localhost:8000').replace(/\/$/, '')

/**
 * Casdoor **Shared Application** 的 base client_id（方案C 多租户登录）。每租户实际用 `<base>-org-<org>`
 * 构造 UserManager（见 `auth/oidc.ts`）。须与 edge `edge.casdoor.audiences` 的 base 一致。
 */
export const CASDOOR_CLIENT_ID: string = env.VITE_CASDOOR_CLIENT_ID ?? 'ragshared0client00000001'

/** OIDC 请求 scope（标准 OIDC；业务 scope 由 Casdoor permissions claim 展开，不在此请求）。 */
export const CASDOOR_SCOPES: string = env.VITE_CASDOOR_SCOPES ?? 'openid profile email offline_access'

/**
 * SPA 内回调/登出/静默续期路径（相对 BASE_URL，无前导斜杠）；三者须在 Casdoor app redirectUris 精确登记。
 * silent 路径**刻意不带扩展名**（如 `oidc-silent`），使 dev(vite) 与 prod(nginx) 的 SPA history fallback
 * 都能把它兜到 index.html（`.html` 结尾会被当静态文件、fallback 不覆盖）→ main.ts 据此短路处理静默回调。
 */
export const CASDOOR_REDIRECT_PATH: string = env.VITE_CASDOOR_REDIRECT_PATH ?? 'callback'
export const CASDOOR_POST_LOGOUT_PATH: string = env.VITE_CASDOOR_POST_LOGOUT_PATH ?? 'login'
export const CASDOOR_SILENT_PATH: string = env.VITE_CASDOOR_SILENT_PATH ?? 'oidc-silent'

/**
 * 业务 scope allowlist —— 与 edge `edge.casdoor.scope-allowlist` **逐字同步**（11 项）。
 * 前端解 access_token 的 `permissions[].name` 后 ∩ 本表，保证前端预判与 edge 换发的内部 JWT scopes 零漂移。
 * 改动须与 `edge-gateway/application.yml` 的 scope-allowlist 同步。
 */
export const SCOPE_ALLOWLIST: readonly string[] = [
  'chat', 'ingest', 'approve', 'agent', 'channel', 'eval',
  'vision', 'voice', 'analytics', 'role-admin', 'public-ingest',
]
