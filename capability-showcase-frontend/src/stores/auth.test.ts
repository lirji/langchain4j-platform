import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from './auth'

function jsonRes(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(),
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response
}

const SESSION = {
  accessToken: 'tok-1',
  expiresInSeconds: 3600,
  user: { username: 'alice', tenant: 'acme', scopes: ['chat'] },
}

beforeEach(() => setActivePinia(createPinia()))
afterEach(() => vi.unstubAllGlobals())

describe('auth store', () => {
  it('login 成功写入内存会话，且不落 storage', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonRes(200, SESSION))))
    const auth = useAuthStore()
    await auth.login('alice', 'pw')
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.accessToken).toBe('tok-1')
    expect(auth.user?.tenant).toBe('acme')
    expect(auth.username).toBe('alice')
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('login 失败保持未登录并抛错', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonRes(401, { error: 'invalid_credentials', message: '用户名或密码错误' }))),
    )
    const auth = useAuthStore()
    await expect(auth.login('alice', 'bad')).rejects.toBeTruthy()
    expect(auth.isAuthenticated).toBe(false)
  })

  it('logout 清态（即便登出请求失败也本地清）', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonRes(200, SESSION))))
    const auth = useAuthStore()
    await auth.login('alice', 'pw')
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network'))))
    await auth.logout()
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.user).toBeNull()
  })

  it('refresh 成功替换令牌', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonRes(200, { ...SESSION, accessToken: 'tok-2' }))))
    const auth = useAuthStore()
    const token = await auth.refresh()
    expect(token).toBe('tok-2')
    expect(auth.accessToken).toBe('tok-2')
  })

  it('refresh 失败清态返回 null', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonRes(401, { error: 'expired' }))))
    const auth = useAuthStore()
    const token = await auth.refresh()
    expect(token).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
  })

  it('refresh 单飞：并发只打一次 /auth/refresh', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(jsonRes(200, { ...SESSION, accessToken: 'tok-sf' })))
    vi.stubGlobal('fetch', fetchMock)
    const auth = useAuthStore()
    const [a, b] = await Promise.all([auth.refresh(), auth.refresh()])
    expect(a).toBe('tok-sf')
    expect(b).toBe('tok-sf')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('bootstrap 失败静默不抛，并置 bootstrapDone', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonRes(401, {}))))
    const auth = useAuthStore()
    await auth.bootstrap()
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.bootstrapDone).toBe(true)
  })

  it('bootstrap 成功恢复登录态', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonRes(200, SESSION))))
    const auth = useAuthStore()
    await auth.bootstrap()
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.bootstrapDone).toBe(true)
  })

  it('hasScope / isAdmin 随 user 整体替换重算', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonRes(200, SESSION)))) // scopes=['chat']
    const auth = useAuthStore()
    await auth.login('alice', 'pw')
    expect(auth.hasScope('chat')).toBe(true)
    expect(auth.hasScope('role-admin')).toBe(false)
    expect(auth.isAdmin).toBe(false)
    // refresh 返回含 role-admin → isAdmin 重算为 true（scopeSet 依赖 user 整体替换）
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonRes(200, { ...SESSION, user: { ...SESSION.user, scopes: ['chat', 'role-admin'] } }))),
    )
    await auth.refresh()
    expect(auth.isAdmin).toBe(true)
    expect(auth.hasAllScopes(['chat', 'role-admin'])).toBe(true)
  })

  it('register 成功写内存会话、不落 storage', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonRes(200, SESSION))))
    const auth = useAuthStore()
    await auth.register('newuser', 'pw123456')
    expect(auth.isAuthenticated).toBe(true)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('loadPublicConfig 成功写 publicConfig；失败保持 null 且不抛', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonRes(200, { registrationEnabled: true, passwordMinLength: 6, passwordMaxLength: 128 }))),
    )
    const auth = useAuthStore()
    await auth.loadPublicConfig()
    expect(auth.publicConfig?.registrationEnabled).toBe(true)

    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network'))))
    const auth2 = useAuthStore()
    await expect(auth2.loadPublicConfig()).resolves.toBeUndefined()
    expect(auth2.publicConfig?.registrationEnabled).toBe(true) // 失败不覆盖既有；新 store 则为 null
  })
})
