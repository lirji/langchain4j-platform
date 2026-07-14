import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { ApiError } from '../api/errors'
import type { GroupView } from '../types/admin'

const mocks = vi.hoisted(() => ({
  fetchGroups: vi.fn(),
  fetchGroup: vi.fn(),
  createGroup: vi.fn(),
  updateGroup: vi.fn(),
  deleteGroup: vi.fn(),
  fetchGroupMembers: vi.fn(),
  replaceGroupMembers: vi.fn(),
}))
vi.mock('../api/admin', () => mocks)

import { useAdminGroupsStore } from './adminGroups'

function group(name: string, over: Partial<GroupView> = {}): GroupView {
  return {
    name,
    description: '',
    roles: ['viewer'],
    effectiveScopes: ['chat'],
    memberCount: 2,
    version: 0,
    ...over,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  Object.values(mocks).forEach((m) => m.mockReset())
})

describe('adminGroups store', () => {
  it('saveGroup（说明+角色）成功就地失效', async () => {
    mocks.fetchGroups.mockResolvedValue([group('ops')])
    mocks.updateGroup.mockResolvedValue(group('ops', { roles: ['editor'], version: 1 }))
    const store = useAdminGroupsStore()
    await store.load()
    const v = await store.saveGroup('ops', { description: 'x', roles: ['editor'] }, 0)
    expect(v.roles).toEqual(['editor'])
    expect(store.groups[0].roles).toEqual(['editor'])
  })

  it('saveMembers 成功：本地成员即为提交值 + version 前进', async () => {
    mocks.fetchGroups.mockResolvedValue([group('ops')])
    mocks.replaceGroupMembers.mockResolvedValue(group('ops', { memberCount: 3, version: 1 }))
    const store = useAdminGroupsStore()
    await store.load()
    await store.saveMembers('ops', ['alice', 'bob', 'carol'], 0)
    expect(store.members).toEqual(['alice', 'bob', 'carol'])
    expect(store.groups[0].memberCount).toBe(3)
  })

  it('成员写 version_conflict：抓 fetchGroup 到 conflictLatest 后重抛', async () => {
    mocks.fetchGroup.mockResolvedValueOnce(group('ops', { version: 0 }))
    const store = useAdminGroupsStore()
    await store.loadDetail('ops')
    mocks.replaceGroupMembers.mockRejectedValue(
      new ApiError(412, 'x', { error: 'precondition_failed', message: 'c' }),
    )
    mocks.fetchGroup.mockResolvedValueOnce(group('ops', { version: 7 }))
    await expect(store.saveMembers('ops', ['alice'], 0)).rejects.toMatchObject({ status: 412 })
    expect(store.conflictLatest?.version).toBe(7)
    expect(store.selected?.version).toBe(0)
  })

  it('createGroupAction 后整表重拉', async () => {
    mocks.fetchGroups.mockResolvedValue([])
    mocks.createGroup.mockResolvedValue(group('new-team'))
    const store = useAdminGroupsStore()
    await store.load()
    mocks.fetchGroups.mockClear()
    await store.createGroupAction({ name: 'new-team', description: '', roles: [] })
    expect(mocks.fetchGroups).toHaveBeenCalled()
  })

  it('deleteGroupAction 局部移除', async () => {
    mocks.fetchGroups.mockResolvedValue([group('ops')])
    mocks.deleteGroup.mockResolvedValue(undefined)
    const store = useAdminGroupsStore()
    await store.load()
    await store.deleteGroupAction('ops', 0)
    expect(store.groups.find((g) => g.name === 'ops')).toBeUndefined()
  })

  it('rolesByGroup 映射供预测使用', async () => {
    mocks.fetchGroups.mockResolvedValue([group('ops', { roles: ['a', 'b'] })])
    const store = useAdminGroupsStore()
    await store.load()
    expect(store.rolesByGroup.get('ops')).toEqual(['a', 'b'])
  })
})
