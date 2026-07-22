import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// OIDC 驱动 mock：共享句柄供提升后的工厂引用（vi.hoisted）。
const oidc = vi.hoisted(() => ({
  silentRecover: vi.fn(),
  completeLoginCallback: vi.fn(),
  signOutRedirect: vi.fn(),
  getStoredUser: vi.fn(),
  startOidcLogin: vi.fn(),
  clearStoredUser: vi.fn(),
  addUserLoaded: vi.fn(),
  addUserUnloaded: vi.fn(),
}))

// 把 store 逼进 oidc 驱动：其余 config 导出保留真实值。
vi.mock('../config', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../config')>()),
  OIDC_ENABLED: true,
  AUTH_MODE: 'oidc',
}))

vi.mock('../auth/oidc', () => ({
  getUserManager: () => ({
    events: { addUserLoaded: oidc.addUserLoaded, addUserUnloaded: oidc.addUserUnloaded },
  }),
  silentRecover: oidc.silentRecover,
  completeLoginCallback: oidc.completeLoginCallback,
  signOutRedirect: oidc.signOutRedirect,
  getStoredUser: oidc.getStoredUser,
  startOidcLogin: oidc.startOidcLogin,
  clearStoredUser: oidc.clearStoredUser,
  // 解 access_token → 身份视图；admin token 带 role-admin；坏 token（含 'bad'）解出空身份（#9）。
  userFromAccessToken: (t: string) =>
    t.includes('bad')
      ? { username: '', tenant: '', scopes: [] }
      : {
          username: 'admin',
          tenant: 'built-in',
          scopes: t.includes('admin') ? ['chat', 'role-admin'] : ['chat'],
        },
}))

import { useAuthStore } from './auth'

const casUser = (token: string) => ({ access_token: token, expired: false, state: null })

beforeEach(() => {
  setActivePinia(createPinia())
  Object.values(oidc).forEach((f) => f.mockReset())
})

describe('auth store —— OIDC 驱动', () => {
  it('refresh 走 signinSilent、回写 token、返回 access_token 字符串', async () => {
    oidc.silentRecover.mockResolvedValue(casUser('cas-tok-admin'))
    const auth = useAuthStore()
    const token = await auth.refresh()
    expect(oidc.silentRecover).toHaveBeenCalledTimes(1)
    expect(token).toBe('cas-tok-admin')
    expect(auth.accessToken).toBe('cas-tok-admin')
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.hasScope('role-admin')).toBe(true)
    expect(auth.user?.tenant).toBe('built-in')
    // 令牌绝不落 localStorage 不变量（DR-5 下 oidc 真相源在 sessionStorage，故此处只断言 localStorage；
    // 本套件 oidc 模块整体被 mock、真实 userStore 不运行，断言 sessionStorage 为空只是 mock 假象、无意义，故删）。
    expect(localStorage.length).toBe(0)
  })

  it('refresh 单飞：并发只调一次 silentRecover', async () => {
    oidc.silentRecover.mockResolvedValue(casUser('cas-sf'))
    const auth = useAuthStore()
    const [a, b] = await Promise.all([auth.refresh(), auth.refresh()])
    expect(a).toBe('cas-sf')
    expect(b).toBe('cas-sf')
    expect(oidc.silentRecover).toHaveBeenCalledTimes(1)
  })

  it('refresh 静默返回 null → 清态 + purge sessionStorage User（#6）、返回 null', async () => {
    oidc.silentRecover.mockResolvedValue(null)
    const auth = useAuthStore()
    expect(await auth.refresh()).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
    // #6：续期失败必须 removeUser，否则硬刷新读回失效 User 而"恢复"失效会话。
    expect(oidc.clearStoredUser).toHaveBeenCalledTimes(1)
  })

  it('refresh 抛错 → 清态 + purge sessionStorage User（#6）、返回 null（不外抛）', async () => {
    oidc.silentRecover.mockRejectedValue(new Error('silent renew failed'))
    const auth = useAuthStore()
    expect(await auth.refresh()).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
    expect(oidc.clearStoredUser).toHaveBeenCalledTimes(1)
  })

  it('logout 走 Casdoor 单点登出（signoutRedirect）', async () => {
    oidc.signOutRedirect.mockResolvedValue(undefined)
    const auth = useAuthStore()
    await auth.logout()
    expect(oidc.signOutRedirect).toHaveBeenCalledTimes(1)
  })

  it('clearFromRemoteLogout（其它标签登出）→ 本标签清态 + purge sessionStorage、不重定向', async () => {
    oidc.completeLoginCallback.mockResolvedValue({ access_token: 'cas-tok-admin', state: null })
    const auth = useAuthStore()
    await auth.handleOidcCallback()
    expect(auth.isAuthenticated).toBe(true)
    auth.clearFromRemoteLogout()
    expect(auth.isAuthenticated).toBe(false)
    // oidc 下须 purge 本标签自己的 sessionStorage User（clear 镜像 + removeUser）。
    expect(oidc.clearStoredUser).toHaveBeenCalled()
    // 不发起单点登出（那是当前标签主动登出才做，收到远端信号只清本地）。
    expect(oidc.signOutRedirect).not.toHaveBeenCalled()
  })

  it('startOidcLogin 透传 tenant + returnTo 给重定向登录', async () => {
    oidc.startOidcLogin.mockResolvedValue(undefined)
    const auth = useAuthStore()
    await auth.startOidcLogin('acme', '/m/agent/run')
    expect(oidc.startOidcLogin).toHaveBeenCalledWith('acme', '/m/agent/run')
  })

  it('startOidcLogin 对未知租户 fail-closed，不调用底层 OIDC', async () => {
    const auth = useAuthStore()
    await expect(auth.startOidcLogin('not-exists', '/')).rejects.toThrow('租户 not-exists 不存在或未开放')
    expect(oidc.startOidcLogin).not.toHaveBeenCalled()
  })

  it('handleOidcCallback 建立会话并返回 returnTo（原 state）', async () => {
    oidc.completeLoginCallback.mockResolvedValue({ access_token: 'cas-tok-admin', state: '/m/x' })
    const auth = useAuthStore()
    const returnTo = await auth.handleOidcCallback()
    expect(returnTo).toBe('/m/x')
    expect(auth.accessToken).toBe('cas-tok-admin')
    expect(auth.isAuthenticated).toBe(true)
    // 订阅了 UserManager 事件（token 回写）
    expect(oidc.addUserLoaded).toHaveBeenCalled()
  })

  it('addUserLoaded 事件触发 → 新 token 回写 store（M3 轮换后不用旧 token）', async () => {
    oidc.completeLoginCallback.mockResolvedValue({ access_token: 'cas-tok-admin', state: null })
    const auth = useAuthStore()
    await auth.handleOidcCallback()
    expect(auth.accessToken).toBe('cas-tok-admin')
    // 取订阅时传入的回调，模拟 refresh_token 轮换后 UserManager 派发新 User。
    const onLoaded = oidc.addUserLoaded.mock.calls[0][0] as (u: unknown) => void
    onLoaded(casUser('cas-tok-rotated'))
    expect(auth.accessToken).toBe('cas-tok-rotated')
    expect(auth.isAuthenticated).toBe(true)
  })

  it('坏 token 解析成空身份 → 视为未登录（#9），不建立会话', async () => {
    oidc.completeLoginCallback.mockResolvedValue({ access_token: 'bad-token', state: null })
    const auth = useAuthStore()
    await auth.handleOidcCallback()
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.username).toBe('')
  })

  it('bootstrap：内存有未过期 user → 直接恢复；置 bootstrapDone', async () => {
    oidc.getStoredUser.mockResolvedValue(casUser('cas-tok-admin'))
    const auth = useAuthStore()
    await auth.bootstrap()
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.bootstrapDone).toBe(true)
    expect(oidc.silentRecover).not.toHaveBeenCalled()
  })

  it('bootstrap：无存储 user → 不触发 silentRecover（不做 prompt=none）、判未登录', async () => {
    oidc.getStoredUser.mockResolvedValue(null)
    const auth = useAuthStore()
    await auth.bootstrap()
    expect(oidc.silentRecover).not.toHaveBeenCalled()
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.bootstrapDone).toBe(true)
  })

  it('bootstrap：存储 user 已过期 → refresh_token 续期（silentRecover）', async () => {
    oidc.getStoredUser.mockResolvedValue({ access_token: 'old', expired: true, state: null })
    oidc.silentRecover.mockResolvedValue(casUser('cas-tok-admin'))
    const auth = useAuthStore()
    await auth.bootstrap()
    expect(oidc.silentRecover).toHaveBeenCalledTimes(1)
    expect(auth.accessToken).toBe('cas-tok-admin')
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.bootstrapDone).toBe(true)
  })
})
