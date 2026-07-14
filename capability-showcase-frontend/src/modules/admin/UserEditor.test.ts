import { describe, it, expect, beforeEach, vi } from 'vitest'
import { reactive } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { setActivePinia, createPinia } from 'pinia'
import UserEditor from './UserEditor.vue'
import ScopePicker from '../../components/admin/ScopePicker.vue'
import RolePicker from '../../components/admin/RolePicker.vue'
import GroupPicker from '../../components/admin/GroupPicker.vue'
import VersionConflictDialog from '../../components/admin/VersionConflictDialog.vue'
import { ApiError } from '../../api/errors'
import type { RoleView, UserAdminView } from '../../types/admin'

const h = vi.hoisted(() => ({
  users: null as unknown as Record<string, unknown>,
  roles: null as unknown as Record<string, unknown>,
  tenants: null as unknown as Record<string, unknown>,
  groups: null as unknown as Record<string, unknown>,
}))
vi.mock('../../stores/adminUsers', () => ({ useAdminUsersStore: () => h.users }))
vi.mock('../../stores/adminRoles', () => ({ useAdminRolesStore: () => h.roles }))
vi.mock('../../stores/adminTenants', () => ({ useAdminTenantsStore: () => h.tenants }))
vi.mock('../../stores/adminGroups', () => ({ useAdminGroupsStore: () => h.groups }))
// 归因区只读拉取：stub 为空结果，避免真实网络。
vi.mock('../../api/admin', () => ({
  fetchUserEffectivePermissions: vi.fn().mockResolvedValue({
    directScopes: [],
    personalRoleScopes: [],
    tenantScopes: [],
    groupScopes: [],
    effectiveScopes: [],
    sources: {},
  }),
}))

const SELECTED: UserAdminView = {
  username: 'alice',
  userId: 'id-1',
  tenant: 'acme',
  directScopes: ['ingest', 'read'],
  roles: ['ops'],
  groups: [],
  effectiveScopes: ['chat'],
  enabled: true,
  version: 3,
}
const ROLE_OPS: RoleView = {
  name: 'ops',
  scopes: ['chat', 'agent'],
  description: '运维',
  version: 0,
  assignedUserCount: 1,
  boundGroupCount: 0,
  boundTenantCount: 0,
}

function makeUsersStore() {
  return reactive({
    selected: { ...SELECTED } as UserAdminView,
    selectedStatus: 'ready',
    selectedError: null,
    loadDetail: vi.fn(),
    saveUserPatch: vi.fn().mockResolvedValue({ ...SELECTED, version: 4 }),
    saveUserRoles: vi.fn().mockResolvedValue({ ...SELECTED, version: 5 }),
    saveUserGroups: vi.fn().mockResolvedValue({ ...SELECTED, version: 6 }),
    createUserAction: vi.fn(),
    deleteUserAction: vi.fn(),
  })
}
function makeRolesStore() {
  return reactive({
    roles: [ROLE_OPS],
    roleNames: ['ops', 'viewer'],
    status: 'ready',
    load: vi.fn(),
  })
}
function makeTenantsStore() {
  return reactive({
    status: 'ready',
    baseRolesByTenant: new Map<string, string[]>(),
    load: vi.fn(),
  })
}
function makeGroupsStore() {
  return reactive({
    status: 'ready',
    groupNames: [] as string[],
    rolesByGroup: new Map<string, string[]>(),
    load: vi.fn(),
  })
}

let router: Router
async function mountEditor(username = 'alice') {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/users', name: 'admin-users', component: { template: '<div/>' } },
      { path: '/admin/users/:username', name: 'admin-user', component: { template: '<div/>' } },
    ],
  })
  // memoryHistory 需先 push 触发初始导航，否则 isReady() 永不 resolve（mount 在其后，形成死锁）。
  router.push(`/admin/users/${username}`)
  await router.isReady()
  return mount(UserEditor, {
    props: { username },
    global: { plugins: [router], stubs: { teleport: true } },
  })
}

describe('UserEditor', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    h.users = makeUsersStore() as unknown as Record<string, unknown>
    h.roles = makeRolesStore() as unknown as Record<string, unknown>
    h.tenants = makeTenantsStore() as unknown as Record<string, unknown>
    h.groups = makeGroupsStore() as unknown as Record<string, unknown>
  })

  it('directScopes 以只读 ScopePicker 展示', async () => {
    const wrapper = await mountEditor()
    await flushPromises()
    const pickers = wrapper.findAllComponents(ScopePicker)
    const directPicker = pickers.find(
      (c) => JSON.stringify(c.props('modelValue')) === JSON.stringify(['ingest', 'read']),
    )
    expect(directPicker).toBeTruthy()
    expect(directPicker!.props('readonly')).toBe(true)
  })

  it('profile 与 roles 均变更时各写一次，且空密码不发送 password 字段', async () => {
    const wrapper = await mountEditor()
    await flushPromises()
    // 使两侧都变脏（脏检查后才会各写一次）
    await wrapper.find('#ue-tenant').setValue('acme2')
    wrapper.findComponent(RolePicker).vm.$emit('update:modelValue', ['ops', 'viewer'])
    await flushPromises()
    await wrapper.find('.btn--primary').trigger('click')
    await flushPromises()

    const usersStore = h.users as unknown as { saveUserPatch: ReturnType<typeof vi.fn>; saveUserRoles: ReturnType<typeof vi.fn> }
    expect(usersStore.saveUserPatch).toHaveBeenCalledTimes(1)
    expect(usersStore.saveUserRoles).toHaveBeenCalledTimes(1)

    // 密码留空 → 请求体不含 password 字段
    const patchReq = usersStore.saveUserPatch.mock.calls[0][1] as Record<string, unknown>
    expect(patchReq).not.toHaveProperty('password')
    // 带 selected.version
    expect(usersStore.saveUserPatch.mock.calls[0][2]).toBe(3)
  })

  it('脏检查：无改动时不写任何端点', async () => {
    const wrapper = await mountEditor()
    await flushPromises()
    await wrapper.find('.btn--primary').trigger('click')
    await flushPromises()
    const usersStore = h.users as unknown as { saveUserPatch: ReturnType<typeof vi.fn>; saveUserRoles: ReturnType<typeof vi.fn> }
    expect(usersStore.saveUserPatch).not.toHaveBeenCalled()
    expect(usersStore.saveUserRoles).not.toHaveBeenCalled()
  })

  it('非空密码 → 请求体带 password', async () => {
    const wrapper = await mountEditor()
    await flushPromises()
    await wrapper.find('#ue-pw').setValue('newsecret1')
    await wrapper.find('.btn--primary').trigger('click')
    await flushPromises()
    const usersStore = h.users as unknown as { saveUserPatch: ReturnType<typeof vi.fn> }
    const patchReq = usersStore.saveUserPatch.mock.calls[0][1] as Record<string, unknown>
    expect(patchReq.password).toBe('newsecret1')
  })

  it('仅 groups 变更 → 只调 saveUserGroups（第三步独立写，用当前 version）', async () => {
    const wrapper = await mountEditor()
    await flushPromises()
    wrapper.findComponent(GroupPicker).vm.$emit('update:modelValue', ['ops-team'])
    await flushPromises()
    await wrapper.find('.btn--primary').trigger('click')
    await flushPromises()
    const usersStore = h.users as unknown as {
      saveUserPatch: ReturnType<typeof vi.fn>
      saveUserRoles: ReturnType<typeof vi.fn>
      saveUserGroups: ReturnType<typeof vi.fn>
    }
    expect(usersStore.saveUserPatch).not.toHaveBeenCalled()
    expect(usersStore.saveUserRoles).not.toHaveBeenCalled()
    expect(usersStore.saveUserGroups).toHaveBeenCalledTimes(1)
    expect(usersStore.saveUserGroups.mock.calls[0][1]).toEqual(['ops-team'])
    expect(usersStore.saveUserGroups.mock.calls[0][2]).toBe(3) // 未经 profile/roles bump，用 base.version
  })

  it('version_conflict → 打开 VersionConflictDialog（不覆盖）', async () => {
    const usersStore = h.users as unknown as { saveUserPatch: ReturnType<typeof vi.fn> }
    usersStore.saveUserPatch = vi.fn().mockRejectedValue(
      new ApiError(412, 'HTTP 412', { error: 'precondition_failed', message: '内容已被他人更新' }),
    )
    const wrapper = await mountEditor()
    await flushPromises()
    await wrapper.find('#ue-tenant').setValue('acme2') // 使 profile 变脏，保存才会触发写
    await flushPromises()
    expect(wrapper.findComponent(VersionConflictDialog).props('open')).toBe(false)
    await wrapper.find('.btn--primary').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(VersionConflictDialog).props('open')).toBe(true)
  })
})
