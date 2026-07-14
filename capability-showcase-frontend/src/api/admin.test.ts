import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// 捕获 authorizedFetch 的入参，断言 Bearer-only 与 If-Match。
const calls: { url: string; init: RequestInit }[] = []
let nextResponse: () => Response

vi.mock('./authorizedFetch', () => ({
  authorizedFetch: (url: string, init: RequestInit = {}) => {
    calls.push({ url, init })
    return Promise.resolve(nextResponse())
  },
}))

import { useAuthStore } from '../stores/auth'
import { useSessionStore } from '../stores/session'
import {
  fetchUsers,
  patchUser,
  replaceUserRoles,
  createRole,
  replaceTenantRoles,
  clearTenantRoles,
  replaceGroupMembers,
  replaceUserGroups,
  createGroup,
} from './admin'

function res(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(headers),
    text: async () => (body == null ? '' : JSON.stringify(body)),
  } as unknown as Response
}

function headerKeys(init: RequestInit): string[] {
  return Object.keys((init.headers ?? {}) as Record<string, string>).map((k) => k.toLowerCase())
}
function header(init: RequestInit, name: string): string | undefined {
  return ((init.headers ?? {}) as Record<string, string>)[name]
}

beforeEach(() => {
  calls.length = 0
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.accessToken = 'tok-admin' // 直接置内存会话令牌
})

describe('api/admin —— Bearer-only', () => {
  it('即便 sessionStore 填了 api-key，管理请求也只带 Bearer、绝不带 X-Api-Key', async () => {
    const session = useSessionStore()
    session.setApiKey('sk-should-not-leak')
    nextResponse = () => res(200, [], { 'X-Total-Count': '0' })
    await fetchUsers({ offset: 0, limit: 50 })
    const keys = headerKeys(calls[0].init)
    expect(header(calls[0].init, 'Authorization')).toBe('Bearer tok-admin')
    expect(keys).not.toContain('x-api-key')
  })

  it('fetchUsers 解析 X-Total-Count（缺失时回退 items.length）', async () => {
    nextResponse = () => res(200, [{ username: 'a' }, { username: 'b' }], { 'X-Total-Count': '57' })
    const paged = await fetchUsers({ offset: 0, limit: 50 })
    expect(paged.total).toBe(57)
    expect(paged.items).toHaveLength(2)

    nextResponse = () => res(200, [{ username: 'a' }]) // 无 X-Total-Count
    const paged2 = await fetchUsers({ offset: 0, limit: 50 })
    expect(paged2.total).toBe(1)
  })

  it('patchUser 发裸版本号 If-Match', async () => {
    nextResponse = () => res(200, { username: 'a', version: 3 })
    await patchUser('a', { tenant: 'acme2' }, 2)
    expect(header(calls[0].init, 'If-Match')).toBe('2')
    expect(calls[0].init.method).toBe('PATCH')
  })

  it('replaceUserRoles PUT + If-Match', async () => {
    nextResponse = () => res(200, { username: 'a' })
    await replaceUserRoles('a', ['editor'], 5)
    expect(calls[0].init.method).toBe('PUT')
    expect(header(calls[0].init, 'If-Match')).toBe('5')
    expect(calls[0].url).toContain('/auth/admin/users/a/roles')
  })

  it('业务冲突 409 抛 ApiError（body 保留判别码，如建角色重名 role_exists）', async () => {
    nextResponse = () => res(409, { error: 'role_exists', message: '角色已存在' })
    await expect(createRole({ name: 'x', scopes: [], description: '' })).rejects.toMatchObject({ status: 409 })
  })

  it('replaceTenantRoles 首次绑定用 If-Match: -1（裸版本号）', async () => {
    nextResponse = () => res(200, { tenant: 'newco', baseRoles: ['viewer'], version: 0 })
    await replaceTenantRoles('newco', ['viewer'], -1)
    expect(calls[0].init.method).toBe('PUT')
    expect(header(calls[0].init, 'If-Match')).toBe('-1')
    expect(calls[0].url).toContain('/auth/admin/tenants/newco/roles')
  })

  it('clearTenantRoles DELETE + If-Match', async () => {
    nextResponse = () => res(204, null)
    await clearTenantRoles('acme', 4)
    expect(calls[0].init.method).toBe('DELETE')
    expect(header(calls[0].init, 'If-Match')).toBe('4')
  })

  it('replaceGroupMembers PUT + If-Match，路径含 /members', async () => {
    nextResponse = () => res(200, { name: 'ops', version: 2 })
    await replaceGroupMembers('ops', ['alice'], 1)
    expect(calls[0].init.method).toBe('PUT')
    expect(header(calls[0].init, 'If-Match')).toBe('1')
    expect(calls[0].url).toContain('/auth/admin/groups/ops/members')
  })

  it('replaceUserGroups PUT + If-Match，路径含 /groups', async () => {
    nextResponse = () => res(200, { username: 'a' })
    await replaceUserGroups('a', ['ops'], 3)
    expect(calls[0].init.method).toBe('PUT')
    expect(header(calls[0].init, 'If-Match')).toBe('3')
    expect(calls[0].url).toContain('/auth/admin/users/a/groups')
  })

  it('createGroup 重名 409 group_exists 抛 ApiError', async () => {
    nextResponse = () => res(409, { error: 'group_exists', message: '组已存在' })
    await expect(createGroup({ name: 'ops', description: '', roles: [] })).rejects.toMatchObject({ status: 409 })
  })
})
