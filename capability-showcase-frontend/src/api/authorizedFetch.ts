/**
 * 业务请求的鉴权包装：会话令牌过期时静默续期并重试一次。
 *
 * 逻辑：
 * 1. 正常发起请求；非 401 直接返回。
 * 2. 401 且本次用的是会话 Bearer（api-key 的 401 不属会话过期，不处理）→ 触发一次
 *    {@link useAuthStore().refresh}（store 内单飞，并发只刷一次）。
 * 3. 续期成功 → 用新令牌改写 Authorization 重试一次；失败 → 打开会话过期模态引导重登
 *    （不整页跳走、不丢当前 deep-link，DR-1），返回原 401。
 *
 * 注：/auth/refresh 自身不经此包装（它在 api/auth.ts 直连），故不会自递归。
 */
import { useAuthStore } from '../stores/auth'
import { useUiStore } from '../stores/ui'
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
    promptRelogin()
    return res
  }

  headers.set(AUTH_HEADER, `Bearer ${newToken}`)
  return fetch(url, { ...init, headers })
}

/** 续期失败：打开会话过期模态，记录当前 deep-link 供重登还原（已在登录页则不弹，避免打断登录流程）。 */
function promptRelogin(): void {
  const current = router.currentRoute.value
  if (current.name === 'login') return
  useUiStore().openAuthModal(current.fullPath)
}
