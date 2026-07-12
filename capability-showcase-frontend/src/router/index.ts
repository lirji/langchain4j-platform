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
import { REQUIRE_LOGIN } from '../config'
import { useAuthStore } from '../stores/auth'

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
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
  return resolveAuthNavigation(to, {
    isAuthenticated: auth.isAuthenticated,
    requireLogin: REQUIRE_LOGIN,
  })
})
