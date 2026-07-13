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
 * - RBAC_CONSOLE_ENABLED / SHARED_KB_UI_ENABLED / DEMO_LOGIN_ENABLED：三个构建期 kill switch。
 *   语义为"只可强制关闭"——即便置 true，管理域/共享库是否真正可用仍由服务端运行时（scopes / rag config）决定；
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

/** RBAC 管理控制台入口开关（默认开；置 false 前端彻底隐藏 /admin 与相关入口）。真正可用仍需 role-admin scope。 */
export const RBAC_CONSOLE_ENABLED: boolean = env.VITE_RBAC_CONSOLE_ENABLED !== 'false'

/** 共享知识库 UI 开关（默认开；置 false 只展示租户库）。真正可用仍取决于服务端 /rag/config.publicEnabled。 */
export const SHARED_KB_UI_ENABLED: boolean = env.VITE_SHARED_KB_UI_ENABLED !== 'false'

/** 演示账号一键登录开关（默认开，便于内部试用；生产置 false 后登录页不内置 demo 密码/账号）。 */
export const DEMO_LOGIN_ENABLED: boolean = env.VITE_DEMO_LOGIN_ENABLED !== 'false'
