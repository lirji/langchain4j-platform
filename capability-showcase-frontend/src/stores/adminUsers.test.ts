import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { ApiError } from '../api/errors'
import type { UserAdminView } from '../types/admin'

const mocks = vi.hoisted(() => ({
  fetchUsers: vi.fn(),
  fetchUser: vi.fn(),
  patchUser: vi.fn(),
  replaceUserRoles: vi.fn(),
  createUser: vi.fn(),
  deleteUser: vi.fn(),
}))
vi.mock('../api/admin', () => mocks)

import { useAdminUsersStore } from './adminUsers'

function user(username: string, over: Partial<UserAdminView> = {}): UserAdminView {
  return {
    username,
    userId: username,
    tenant: 'acme',
    directScopes: [],
    roles: ['viewer'],
    effectiveScopes: ['chat'],
    enabled: true,
    version: 0,
    ...over,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  Object.values(mocks).forEach((m) => m.mockReset())
})

describe('adminUsers store', () => {
  it('load 后 patch 成功就地失效（不整表重拉）', async () => {
    mocks.fetchUsers.mockResolvedValue({ items: [user('dave')], total: 1 })
    mocks.patchUser.mockResolvedValue(user('dave', { tenant: 'acme2', version: 1 }))
    const store = useAdminUsersStore()
    await store.load()
    const v = await store.saveUserPatch('dave', { tenant: 'acme2' }, 0)
    expect(v.tenant).toBe('acme2')
    expect(store.items[0].tenant).toBe('acme2') // 就地替换
    expect(mocks.fetchUsers).toHaveBeenCalledTimes(1) // 未再整表重拉
  })

  it('version_conflict(412)：服务端最新入 conflictLatest（不覆盖 selected/草稿），再重抛（不吞）', async () => {
    mocks.patchUser.mockRejectedValue(new ApiError(412, 'x', { error: 'precondition_failed', message: 'c' }))
    mocks.fetchUser.mockResolvedValueOnce(user('dave', { version: 0 })) // loadDetail 基线
    const store = useAdminUsersStore()
    await store.loadDetail('dave')
    expect(store.selected?.version).toBe(0)
    mocks.fetchUser.mockResolvedValueOnce(user('dave', { version: 5 })) // 冲突后抓最新
    await expect(store.saveUserPatch('dave', { tenant: 'x' }, 0)).rejects.toMatchObject({ status: 412 })
    expect(store.conflictLatest?.version).toBe(5) // 最新入独立字段
    expect(store.selected?.version).toBe(0) // selected 未被覆盖（草稿不丢）
    // 采纳最新 → selected 前进到 5，conflictLatest 清空
    store.acceptConflictLatest()
    expect(store.selected?.version).toBe(5)
    expect(store.conflictLatest).toBeNull()
  })

  it('createUserAction 触发整页重拉（计数变化）', async () => {
    mocks.fetchUsers.mockResolvedValue({ items: [], total: 0 })
    mocks.createUser.mockResolvedValue(user('newbie'))
    const store = useAdminUsersStore()
    await store.load()
    mocks.fetchUsers.mockClear()
    await store.createUserAction({ username: 'newbie', password: 'pw', tenant: 'acme', roles: [], directScopes: [], enabled: true })
    expect(mocks.fetchUsers).toHaveBeenCalled()
  })

  it('deleteUserAction 局部移除 + 重拉', async () => {
    mocks.fetchUsers.mockResolvedValue({ items: [user('dave')], total: 1 })
    mocks.deleteUser.mockResolvedValue(undefined)
    const store = useAdminUsersStore()
    await store.load()
    await store.deleteUserAction('dave', 0)
    expect(store.items.find((u) => u.username === 'dave')).toBeUndefined()
  })
})
