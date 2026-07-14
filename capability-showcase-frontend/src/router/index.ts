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
import { REQUIRE_LOGIN, RBAC_CONSOLE_ENABLED } from '../config'
import { useAuthStore } from '../stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    /** 既有：公开全屏页（登录/注册），不套 header/侧栏/目录门禁。 */
    public?: boolean
    /** 进入该路由所需的 Bearer 有效 scopes（管理域 = ['role-admin']）。 */
    requiredScopes?: string[]
    /** 该路由不依赖能力目录加载（admin/register/forbidden 用），catalog 失败也能打开。 */
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
  { path: '/', name: 'overview', component: OverviewView },
  { path: '/m/:moduleId', name: 'module', component: ModuleHost, props: true },
  {
    path: '/m/:moduleId/:capId',
    name: 'capability',
    component: ModuleHost,
    props: true,
  },
  {
    path: '/forbidden',
    name: 'forbidden',
    component: () => import('../modules/admin/ForbiddenView.vue'),
    meta: { bypassCatalog: true },
  },
  // 管理域受构建开关控制"是否注册"：关时连路由都不存在，命中通配 → '/'（最硬的 kill switch）。
  ...(RBAC_CONSOLE_ENABLED
    ? [
        {
          path: '/admin',
          component: () => import('../modules/admin/AdminLayout.vue'),
          meta: { requiredScopes: ['role-admin'], bypassCatalog: true },
          children: [
            { path: '', redirect: '/admin/users' },
            { path: 'users', name: 'admin-users', component: () => import('../modules/admin/UsersView.vue') },
            {
              path: 'users/:username',
              name: 'admin-user',
              props: true,
              component: () => import('../modules/admin/UserEditor.vue'),
            },
            { path: 'roles', name: 'admin-roles', component: () => import('../modules/admin/RolesView.vue') },
            {
              path: 'roles/:name',
              name: 'admin-role',
              props: true,
              component: () => import('../modules/admin/RoleEditor.vue'),
            },
            { path: 'tenants', name: 'admin-tenants', component: () => import('../modules/admin/TenantsView.vue') },
            {
              path: 'tenants/:tenant',
              name: 'admin-tenant',
              props: true,
              component: () => import('../modules/admin/TenantEditor.vue'),
            },
            { path: 'groups', name: 'admin-groups', component: () => import('../modules/admin/GroupsView.vue') },
            {
              path: 'groups/:name',
              name: 'admin-group',
              props: true,
              component: () => import('../modules/admin/GroupEditor.vue'),
            },
          ],
        } as RouteRecordRaw,
      ]
    : []),
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

/** 校验 redirect 只能是站内绝对路径，挡开放重定向（`//evil.com`、`http://…`）。 */
export function sanitizeRedirect(raw: unknown): string | null {
  if (typeof raw !== 'string' || !raw) return null
  if (!raw.startsWith('/') || raw.startsWith('//')) return null
  return raw
}

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
  /** Bearer 身份的有效 scopes（auth.user?.scopes；api-key 不参与管理域裁决）。 */
  effectiveScopes: string[]
  /** null=未探测 → 注册 fail-closed。 */
  registrationEnabled: boolean | null
  consoleEnabled: boolean
}

/** 读取路由 meta.requiredScopes（对非数组健壮返回 []）。 */
function requiredScopesOf(meta: RouteLocationNormalized['meta']): string[] {
  const s = (meta as { requiredScopes?: unknown }).requiredScopes
  return Array.isArray(s) ? (s as string[]) : []
}

/**
 * 组合守卫裁决（纯函数，便于单测）：先跑登录层（复用 resolveAuthNavigation，保留 redirect 清洗），
 * 再叠加 register 门禁与 admin scope 门禁（Bearer-only，不读 api-key）。
 * - /register：已登录→'/'；registrationEnabled!==true（关闭或未探测）→回 /login（fail-closed）。
 * - requiredScopes 路由：consoleEnabled=false→'/'；未登录→/login 带 redirect；缺任一 scope→/forbidden。
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

  const need = requiredScopesOf(to.meta)
  if (need.length) {
    if (!ctx.consoleEnabled) return { path: '/' }
    if (!ctx.isAuthenticated) return { name: 'login', query: { redirect: to.fullPath } }
    if (!need.every((s) => ctx.effectiveScopes.includes(s))) return { name: 'forbidden' }
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
    effectiveScopes: auth.user?.scopes ?? [],
    registrationEnabled: auth.publicConfig?.registrationEnabled ?? null,
    consoleEnabled: RBAC_CONSOLE_ENABLED,
  })
})
