import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { ApiError } from '../api/errors'
import type { TenantView } from '../types/admin'

const mocks = vi.hoisted(() => ({
  fetchTenants: vi.fn(),
  fetchTenant: vi.fn(),
  replaceTenantRoles: vi.fn(),
  clearTenantRoles: vi.fn(),
}))
vi.mock('../api/admin', () => mocks)

import { useAdminTenantsStore } from './adminTenants'

function tenant(name: string, over: Partial<TenantView> = {}): TenantView {
  return {
    tenant: name,
    baseRoles: ['viewer'],
    effectiveBaseScopes: ['chat'],
    memberCount: 3,
    version: 0,
    ...over,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  Object.values(mocks).forEach((m) => m.mockReset())
})

describe('adminTenants store', () => {
  it('保存基础角色成功就地失效', async () => {
    mocks.fetchTenants.mockResolvedValue([tenant('acme')])
    mocks.replaceTenantRoles.mockResolvedValue(tenant('acme', { baseRoles: ['editor'], version: 1 }))
    const store = useAdminTenantsStore()
    await store.load()
    const v = await store.saveTenantRoles('acme', ['editor'], 0)
    expect(v.baseRoles).toEqual(['editor'])
    expect(store.tenants[0].baseRoles).toEqual(['editor'])
    expect(mocks.fetchTenants).toHaveBeenCalledTimes(1) // 未整表重拉
  })

  it('首次绑定（version=-1）：applyLocal 追加新租户记录', async () => {
    mocks.fetchTenants.mockResolvedValue([])
    mocks.replaceTenantRoles.mockResolvedValue(tenant('newco', { baseRoles: ['viewer'], version: 0 }))
    const store = useAdminTenantsStore()
    await store.load()
    await store.saveTenantRoles('newco', ['viewer'], -1) // If-Match: -1
    expect(mocks.replaceTenantRoles).toHaveBeenCalledWith('newco', ['viewer'], -1)
    expect(store.tenants.find((t) => t.tenant === 'newco')).toBeTruthy()
  })

  it('version_conflict：服务端最新入 conflictLatest（不覆盖 selected），再重抛', async () => {
    mocks.fetchTenant.mockResolvedValueOnce(tenant('acme', { version: 0 }))
    const store = useAdminTenantsStore()
    await store.loadDetail('acme')
    mocks.replaceTenantRoles.mockRejectedValue(new ApiError(412, 'x', { error: 'precondition_failed', message: 'c' }))
    mocks.fetchTenant.mockResolvedValueOnce(tenant('acme', { version: 5 }))
    await expect(store.saveTenantRoles('acme', ['editor'], 0)).rejects.toMatchObject({ status: 412 })
    expect(store.conflictLatest?.version).toBe(5)
    expect(store.selected?.version).toBe(0)
    store.acceptConflictLatest()
    expect(store.selected?.version).toBe(5)
    expect(store.conflictLatest).toBeNull()
  })

  it('清空基础角色（DELETE）后局部移除', async () => {
    mocks.fetchTenants.mockResolvedValue([tenant('acme')])
    mocks.clearTenantRoles.mockResolvedValue(undefined)
    const store = useAdminTenantsStore()
    await store.load()
    await store.clearTenantRolesAction('acme', 0)
    expect(store.tenants.find((t) => t.tenant === 'acme')).toBeUndefined()
  })

  it('baseRolesByTenant 映射供预测使用', async () => {
    mocks.fetchTenants.mockResolvedValue([tenant('acme', { baseRoles: ['a', 'b'] })])
    const store = useAdminTenantsStore()
    await store.load()
    expect(store.baseRolesByTenant.get('acme')).toEqual(['a', 'b'])
  })
})
