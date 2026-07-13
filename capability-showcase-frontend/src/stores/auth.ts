import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { AuthUser, AuthPublicConfig } from '../api/auth'
import {
  fetchPublicAuthConfig,
  loginRequest,
  logoutRequest,
  refreshRequest,
  registerRequest,
} from '../api/auth'

/**
 * 认证 store：持有登录后的会话访问令牌与用户视图 —— 仅内存。
 *
 * 硬约束（对齐 [[session]] store）：accessToken/user 绝不写入 localStorage / sessionStorage / URL / 日志。
 * 刷新页面即清空；靠 httpOnly 刷新 cookie 在 {@link bootstrap} 里静默续期恢复登录态。
 *
 * refresh 单飞（single-flight）：并发的多个业务 401 只触发一次 /auth/refresh，共享同一 Promise，
 * 避免惊群与刷新令牌轮转下的连锁失效。
 */
export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref('')
  const user = ref<AuthUser | null>(null)
  /** bootstrap（启动静默续期）是否已完成，供路由守卫等待，避免首帧误判未登录。 */
  const bootstrapDone = ref(false)
  /** 公开配置（注册开关/密码长度）；null=未探测，注册入口 fail-closed。 */
  const publicConfig = ref<AuthPublicConfig | null>(null)

  const isAuthenticated = computed(() => accessToken.value.length > 0)
  const username = computed(() => user.value?.username ?? '')

  /**
   * 有效 scopes 集合。依赖 user 整体替换而重算（setSession/refresh 均整体替换 user）——
   * 保持反应性的硬约束：**绝不原地 mutate user.value**，否则 scopeSet 不重算。
   */
  const scopeSet = computed(() => new Set(user.value?.scopes ?? []))
  /** 是否具备某 scope（精确匹配；后端 scope 无通配符）。 */
  function hasScope(scope: string): boolean {
    return scopeSet.value.has(scope)
  }
  function hasAllScopes(scopes: string[]): boolean {
    return scopes.every((s) => scopeSet.value.has(s))
  }
  /** 是否平台管理员（持 role-admin 有效 scope）——管理入口可见性与守卫的单一判据。 */
  const isAdmin = computed(() => scopeSet.value.has('role-admin'))

  /** 单飞句柄：非 null 时表示有一次 refresh 正在进行，并发者复用之。 */
  let refreshing: Promise<string | null> | null = null

  function setSession(token: string, u: AuthUser): void {
    accessToken.value = token
    user.value = u
  }

  function clear(): void {
    accessToken.value = ''
    user.value = null
  }

  /** 账号密码登录。成功写入内存会话；失败抛 ApiError 由调用方处理。 */
  async function login(usernameInput: string, password: string): Promise<void> {
    const session = await loginRequest(usernameInput.trim(), password)
    setSession(session.accessToken, session.user)
  }

  /** 自助注册：成功即写入内存会话（与 login 同构）；失败抛 ApiError 由调用方处理。 */
  async function register(usernameInput: string, password: string): Promise<void> {
    const session = await registerRequest(usernameInput.trim(), password)
    setSession(session.accessToken, session.user)
  }

  /** best-effort 拉取公开配置；失败静默（保持 null，注册入口 fail-closed，绝不阻断登录）。 */
  async function loadPublicConfig(): Promise<void> {
    try {
      publicConfig.value = await fetchPublicAuthConfig()
    } catch {
      // 保持 null：守卫层对 registrationEnabled!==true 一律 fail-closed。
    }
  }

  /** 登出：撤销服务端会话（失败也本地清态）。 */
  async function logout(): Promise<void> {
    try {
      await logoutRequest()
    } finally {
      clear()
    }
  }

  /**
   * 用刷新 cookie 换新会话。单飞：并发调用共享同一进行中的 Promise。
   * 成功返回新 accessToken；失败清态并返回 null（调用方决定是否跳转登录）。
   */
  function refresh(): Promise<string | null> {
    if (refreshing) return refreshing
    refreshing = (async () => {
      try {
        const session = await refreshRequest()
        setSession(session.accessToken, session.user)
        return session.accessToken
      } catch {
        clear()
        return null
      }
    })().finally(() => {
      refreshing = null
    })
    return refreshing
  }

  /** 启动静默续期：尝试用刷新 cookie 恢复登录态。绝不抛异常、绝不阻塞（refreshRequest 自带超时）。 */
  async function bootstrap(): Promise<void> {
    try {
      await refresh()
    } finally {
      bootstrapDone.value = true
    }
  }

  return {
    accessToken,
    user,
    bootstrapDone,
    publicConfig,
    isAuthenticated,
    username,
    isAdmin,
    hasScope,
    hasAllScopes,
    login,
    register,
    logout,
    refresh,
    bootstrap,
    loadPublicConfig,
    clear,
  }
})
