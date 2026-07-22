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
import { CASDOOR_TENANTS, OIDC_ENABLED } from '../config'
import { useSessionStore } from './session'
import { useHistoryStore } from './history'
import { broadcastLogout } from '../utils/authChannel'
import {
  clearStoredUser,
  completeLoginCallback,
  getStoredUser,
  getUserManager,
  signOutRedirect,
  silentRecover,
  startOidcLogin as oidcStartLogin,
  userFromAccessToken,
  type User,
} from '../auth/oidc'
import { validateTenantSelection } from '../auth/tenantSelection'

/**
 * 认证 store（双驱动，DR-9）：持有登录后的会话访问令牌与用户视图。存储介质按驱动而异：
 * - **legacy 驱动**（apikey 模式）：accessToken/user 仅内存，绝不落 storage/URL/日志；刷新即清空，
 *   靠 httpOnly 刷新 cookie 在 {@link bootstrap} 里静默续期恢复登录态。
 * - **oidc 驱动**（oidc/dual 模式）：真相源是 oidc-client-ts 的 **sessionStorage userStore**（DR-5，含 token），
 *   store 的 accessToken/user 只是其内存镜像；硬刷新靠 sessionStorage 秒恢复，过期用 refresh_token 轮换。
 *
 * refresh 单飞（single-flight）：并发的多个业务 401 只触发一次续期，共享同一 Promise，
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

  // ── Casdoor OIDC 驱动（DR-9 双驱动；仅 OIDC_ENABLED 时激活，apikey 模式下这些分支永不执行）──

  /** 从 OIDC User 建立/清空内存会话：token=access_token、user=解 access_token（owner/name/permissions∩allowlist）。 */
  function setSessionFromOidcUser(u: User | null): void {
    if (!u || !u.access_token) {
      clear()
      return
    }
    const parsed = userFromAccessToken(u.access_token)
    // #9：坏/无法解析的 token 解出空身份（无 tenant 且无 username）——视为登录失败，不建立会话。
    // 否则 accessToken 非空使 isAuthenticated 为真，却无身份/无 scope（满屏可点却步步 401/403）。
    if (!parsed.tenant && !parsed.username) {
      clear()
      return
    }
    setSession(u.access_token, parsed)
  }

  /**
   * 清空 OIDC 会话：内存镜像 + purge sessionStorage 里的 User（#6）。best-effort，removeUser 失败不外抛。
   * 续期/单点登出失败时调用，避免失效 User 残留、硬刷新被读回而"恢复"失效会话。
   */
  async function clearOidcSession(): Promise<void> {
    clear()
    try {
      await clearStoredUser()
    } catch {
      // best-effort：removeUser 失败不影响已清的内存镜像（isAuthenticated 已为 false）。
    }
  }

  /**
   * 响应其它标签的登出信号（BroadcastChannel）：本标签清态、不重定向（下次请求 401 → 会话过期模态）。
   * oidc 还需 purge 本标签自己的 sessionStorage User（sessionStorage 是标签级、不跨标签共享）。
   */
  function clearFromRemoteLogout(): void {
    if (OIDC_ENABLED) void clearOidcSession()
    else clear()
  }

  let oidcEventsWired = false
  /** 订阅 UserManager 事件：轮换后新 token 回写 store（M3，UserManager 为真相源、store 为镜像）。幂等。 */
  function ensureOidcEvents(): void {
    if (oidcEventsWired || !OIDC_ENABLED) return
    oidcEventsWired = true
    const mgr = getUserManager()
    mgr.events.addUserLoaded((u) => setSessionFromOidcUser(u))
    mgr.events.addUserUnloaded(() => clear())
    // 静默续期失败不在此强制登出，交由后续 401/守卫处理，避免打断当前操作。
  }

  /** 发起 Casdoor 重定向登录（方案C：按 tenant 用 shared app 的 <base>-org-<tenant> 客户端）。整页跳转。 */
  async function startOidcLogin(tenant: string, returnTo?: string): Promise<void> {
    const allowedTenant = validateTenantSelection(tenant, CASDOOR_TENANTS)
    await oidcStartLogin(allowedTenant, returnTo)
  }

  /** /callback 顶层回调：换 token、建立内存会话、订阅事件；返回 returnTo（原 state）供路由还原。 */
  async function handleOidcCallback(): Promise<string | null> {
    const u = await completeLoginCallback()
    setSessionFromOidcUser(u)
    ensureOidcEvents()
    return typeof u.state === 'string' ? u.state : null
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

  /**
   * 登出：撤销会话（失败也本地清态）。本地敏感态清理在此统一收口（#12），使所有登出入口
   * 行为一致，不遗漏：
   * - `session.clearApiKey()`：清 api-key 覆盖，避免残留继续可执行。
   * - `history.clear()`：清请求历史（含 prompt/PII）。
   * 只清 api-key/history，**不**在此清 OIDC User——signOutRedirect 内部先取 id_token 作 hint 再 removeUser（DR-3/L5）。
   * - legacy：POST /auth/logout 撤刷新 cookie。
   * - oidc：RP-initiated 单点登出——跳 Casdoor end_session（库自动带当前 user 的 id_token_hint）。
   *   成功则整页跳转、不回此处；失败兜底本地清态 + purge stored user。
   */
  async function logout(): Promise<void> {
    useSessionStore().clearApiKey()
    useHistoryStore().clear()
    broadcastLogout() // 通知其它标签同步登出（不广播 token；本标签不会收到自己的信号）
    if (OIDC_ENABLED) {
      try {
        await signOutRedirect()
      } catch {
        await clearOidcSession()
      }
      return
    }
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
        if (OIDC_ENABLED) {
          // 会话期内 refresh_token 轮换 / 跨硬刷新 prompt=none iframe（依赖 Casdoor 会话 cookie）。
          const u = await silentRecover()
          if (u && u.access_token) {
            setSessionFromOidcUser(u)
            return u.access_token
          }
          // #6：续期失败必须 purge sessionStorage 里的旧 User，否则硬刷新会读回并"恢复"已失效会话。
          await clearOidcSession()
          return null
        }
        const session = await refreshRequest()
        setSession(session.accessToken, session.user)
        return session.accessToken
      } catch {
        if (OIDC_ENABLED) await clearOidcSession()
        else clear()
        return null
      }
    })().finally(() => {
      refreshing = null
    })
    return refreshing
  }

  /**
   * 启动恢复登录态：绝不抛异常、绝不阻塞。
   * - legacy：用刷新 cookie 打 /auth/refresh。
   * - oidc：从 sessionStorage 读回 User——未过期直接恢复（硬刷新秒回）；已过期用 refresh_token 轮换续期（无 iframe）；
   *   无存储 User 则判未登录、**不**触发 prompt=none iframe（避免登录页/首访无谓 Casdoor 往返与超时卡顿）。
   */
  async function bootstrap(): Promise<void> {
    try {
      if (OIDC_ENABLED) {
        ensureOidcEvents()
        const existing = await getStoredUser()
        if (existing && !existing.expired) {
          setSessionFromOidcUser(existing) // 硬刷新秒恢复
        } else if (existing) {
          await refresh() // 有存储但过期 → refresh_token 续期
        }
        // 无 existing → 未登录：不做任何静默尝试
      } else {
        await refresh()
      }
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
    hasScope,
    hasAllScopes,
    login,
    register,
    logout,
    refresh,
    bootstrap,
    loadPublicConfig,
    clear,
    clearFromRemoteLogout,
    // Casdoor OIDC（oidc/dual 模式）
    startOidcLogin,
    handleOidcCallback,
  }
})
