/**
 * 业务请求的鉴权包装：会话令牌过期时静默续期并重试一次。
 *
 * 逻辑：
 * 1. 正常发起请求；非 401 直接返回。
 * 2. 401 且本次用的是会话 Bearer（api-key 的 401 不属会话过期，不处理）→ 触发一次
 *    {@link useAuthStore().refresh}（store 内单飞，并发只刷一次）。
 * 3. 续期成功 → 用新令牌改写 Authorization 重试一次；失败 → 清态并跳登录，返回原 401。
 *
 * 注：/auth/refresh 自身不经此包装（它在 api/auth.ts 直连），故不会自递归。
 */
import { useAuthStore } from '../stores/auth'
import { router } from '../router'

const AUTH_HEADER = 'Authorization'

export async function authorizedFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const res = await fetch(url, init)
  if (res.status !== 401) return res

  const headers = new Headers(init.headers as HeadersInit | undefined)
  const usedBearer = (headers.get(AUTH_HEADER) ?? '').startsWith('Bearer ')
  if (!usedBearer) return res // api-key 或匿名的 401：反应式处理，不续期

  const auth = useAuthStore()
  const newToken = await auth.refresh() // 单飞
  if (!newToken) {
    redirectToLogin()
    return res
  }

  headers.set(AUTH_HEADER, `Bearer ${newToken}`)
  return fetch(url, { ...init, headers })
}

/** 会话失效：跳登录并带回原路由（已在登录页则不动，避免重复导航）。 */
function redirectToLogin(): void {
  const current = router.currentRoute.value
  if (current.name === 'login') return
  void router.replace({ name: 'login', query: { redirect: current.fullPath } })
}
