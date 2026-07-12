import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// 隔离真实 router，避免测试触发真实导航；断言会话失效时的跳转。
// vi.mock 工厂会被提升到文件顶部，故用 vi.hoisted 创建 mock 变量供工厂安全引用。
const { replaceMock } = vi.hoisted(() => ({ replaceMock: vi.fn() }))
vi.mock('../router', () => ({
  router: {
    currentRoute: { value: { name: 'overview', fullPath: '/m/agent' } },
    replace: replaceMock,
  },
}))

import { authorizedFetch } from './authorizedFetch'

function res(status: number, body: unknown = {}): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(),
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response
}

function bearerOf(init?: RequestInit): string | null {
  return new Headers(init?.headers as HeadersInit | undefined).get('Authorization')
}

const REFRESH_OK = {
  accessToken: 'tok-new',
  expiresInSeconds: 3600,
  user: { username: 'alice', tenant: 'acme', scopes: [] },
}

beforeEach(() => {
  setActivePinia(createPinia())
  replaceMock.mockClear()
})
afterEach(() => vi.unstubAllGlobals())

describe('authorizedFetch', () => {
  it('非 401 直接返回，不刷新', async () => {
    const f = vi.fn(() => Promise.resolve(res(200)))
    vi.stubGlobal('fetch', f)
    const r = await authorizedFetch('/chat', { headers: { Authorization: 'Bearer old' } })
    expect(r.status).toBe(200)
    expect(f).toHaveBeenCalledTimes(1)
  })

  it('api-key 的 401 不触发刷新（无 Bearer）', async () => {
    const f = vi.fn(() => Promise.resolve(res(401)))
    vi.stubGlobal('fetch', f)
    const r = await authorizedFetch('/chat', { headers: { 'X-Api-Key': 'k' } })
    expect(r.status).toBe(401)
    expect(f).toHaveBeenCalledTimes(1)
  })

  it('Bearer 的 401 → 刷新 → 用新令牌重试一次成功', async () => {
    const f = vi.fn((url: unknown, init?: RequestInit) => {
      if (String(url).includes('/auth/refresh')) return Promise.resolve(res(200, REFRESH_OK))
      return Promise.resolve(res(bearerOf(init) === 'Bearer tok-new' ? 200 : 401))
    })
    vi.stubGlobal('fetch', f)
    const r = await authorizedFetch('/chat', { headers: { Authorization: 'Bearer old' } })
    expect(r.status).toBe(200)
    expect(f).toHaveBeenCalledTimes(3) // 业务首次 + refresh + 业务重试
  })

  it('刷新后业务仍 401：不再无限重试', async () => {
    const f = vi.fn((url: unknown) =>
      String(url).includes('/auth/refresh') ? Promise.resolve(res(200, REFRESH_OK)) : Promise.resolve(res(401)),
    )
    vi.stubGlobal('fetch', f)
    const r = await authorizedFetch('/chat', { headers: { Authorization: 'Bearer old' } })
    expect(r.status).toBe(401)
    expect(f).toHaveBeenCalledTimes(3) // 首次 + refresh + 重试，止步
  })

  it('并发多个 Bearer 401 只刷新一次（单飞）', async () => {
    let refreshCount = 0
    const f = vi.fn((url: unknown, init?: RequestInit) => {
      if (String(url).includes('/auth/refresh')) {
        refreshCount++
        return Promise.resolve(res(200, REFRESH_OK))
      }
      return Promise.resolve(res(bearerOf(init) === 'Bearer tok-new' ? 200 : 401))
    })
    vi.stubGlobal('fetch', f)
    const results = await Promise.all([
      authorizedFetch('/chat', { headers: { Authorization: 'Bearer old' } }),
      authorizedFetch('/rag', { headers: { Authorization: 'Bearer old' } }),
      authorizedFetch('/agent', { headers: { Authorization: 'Bearer old' } }),
    ])
    expect(results.every((r) => r.status === 200)).toBe(true)
    expect(refreshCount).toBe(1)
  })

  it('刷新失败 → 返回原 401 并跳转登录（带 redirect）', async () => {
    const f = vi.fn((url: unknown) =>
      String(url).includes('/auth/refresh') ? Promise.resolve(res(401)) : Promise.resolve(res(401)),
    )
    vi.stubGlobal('fetch', f)
    const r = await authorizedFetch('/chat', { headers: { Authorization: 'Bearer old' } })
    expect(r.status).toBe(401)
    expect(replaceMock).toHaveBeenCalledWith({ name: 'login', query: { redirect: '/m/agent' } })
  })
})
