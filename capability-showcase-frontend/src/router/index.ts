import {
  createRouter,
  createWebHistory,
  type RouteLocationNormalized,
  type RouteLocationRaw,
  type RouteRecordRaw,
} from 'vue-router'
import OverviewView from '../modules/OverviewView.vue'
import ModuleHost from '../modules/ModuleHost.vue'
import LoginView from '../modules/auth/LoginView.vue'
import { REQUIRE_LOGIN, CASDOOR_REDIRECT_PATH } from '../config'
import { useAuthStore } from '../stores/auth'
import { sanitizeInternalPath } from '../auth/redirect'

declare module 'vue-router' {
  interface RouteMeta {
    /** 既有：公开全屏页（登录/注册），不套 header/侧栏/目录门禁。 */
    public?: boolean
    /** 该路由不依赖能力目录加载（register/callback 用），catalog 失败也能打开。 */
    bypassCatalog?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
  {
    path: '/register',
    name: 'register',
    component: () => import('../modules/auth/RegisterView.vue'),
    meta: { public: true, bypassCatalog: true },
  },
  {
    // Casdoor OIDC 顶层重定向回调（阶段④）。路径取自 CASDOOR_REDIRECT_PATH（与 oidc.ts 的 redirect_uri
    // 同源，改 env 二者一起移动，不写死 /callback）。public+bypassCatalog：豁免登录守卫、不依赖目录，
    // 否则未登录访问回调页会被守卫跳 /login 造成死循环。
    path: `/${CASDOOR_REDIRECT_PATH}`,
    name: 'callback',
    component: () => import('../modules/auth/CallbackView.vue'),
    meta: { public: true, bypassCatalog: true },
  },
  { path: '/', name: 'overview', component: OverviewView },
  { path: '/m/:moduleId', name: 'module', component: ModuleHost, props: true },
  {
    path: '/m/:moduleId/:capId',
    name: 'capability',
    component: ModuleHost,
    props: true,
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

/** 校验 redirect 只能是站内绝对路径，挡开放重定向（`//evil.com`、`http://…`）。 */
export const sanitizeRedirect = sanitizeInternalPath

/**
 * 纯函数守卫裁决（便于单测，不依赖真实 router）：
 * - 未强制登录 → 一律放行（回滚/纯 api-key 模式）。
 * - 已登录访问 /login → 回原深链或首页。
 * - 未登录访问受保护路由 → 跳 /login 并带 redirect。
 */
export function resolveAuthNavigation(
  to: Pick<RouteLocationNormalized, 'name' | 'fullPath' | 'meta' | 'query'>,
  ctx: { isAuthenticated: boolean; requireLogin: boolean },
): true | RouteLocationRaw {
  if (!ctx.requireLogin) return true
  const isPublic = to.meta?.public === true
  if (ctx.isAuthenticated) {
    if (to.name === 'login') {
      return sanitizeRedirect(to.query.redirect) ?? { path: '/' }
    }
    return true
  }
  if (isPublic) return true
  return { name: 'login', query: { redirect: to.fullPath } }
}

export interface RouteAccessContext {
  isAuthenticated: boolean
  requireLogin: boolean
  /** null=未探测 → 注册 fail-closed。 */
  registrationEnabled: boolean | null
}

/**
 * 组合守卫裁决（纯函数，便于单测）：先跑登录层（复用 resolveAuthNavigation，保留 redirect 清洗），
 * 再叠加 register 门禁。
 * - /register：已登录→'/'；registrationEnabled!==true（关闭或未探测）→回 /login（fail-closed）。
 */
export function resolveRouteAccess(
  to: Pick<RouteLocationNormalized, 'name' | 'fullPath' | 'meta' | 'query'>,
  ctx: RouteAccessContext,
): true | RouteLocationRaw {
  const authDecision = resolveAuthNavigation(to, ctx)
  if (authDecision !== true) return authDecision

  if (to.name === 'register') {
    if (ctx.isAuthenticated) return { path: '/' }
    if (ctx.registrationEnabled !== true) return { name: 'login' }
    return true
  }
  return true
}

export const router = createRouter({
  // 与 vite base（VITE_BASE，默认 /）一致；静态站点 SPA 回退由 nginx try_files 处理。
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  return resolveRouteAccess(to, {
    isAuthenticated: auth.isAuthenticated,
    requireLogin: REQUIRE_LOGIN,
    registrationEnabled: auth.publicConfig?.registrationEnabled ?? null,
  })
})
