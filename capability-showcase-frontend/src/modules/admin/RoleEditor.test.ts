import { describe, it, expect, beforeEach, vi } from 'vitest'
import { reactive } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import RoleEditor from './RoleEditor.vue'
import DangerConfirmDialog from '../../components/admin/DangerConfirmDialog.vue'
import { ApiError } from '../../api/errors'
import type { RoleView } from '../../types/admin'

const h = vi.hoisted(() => ({ roles: null as unknown as Record<string, unknown> }))
vi.mock('../../stores/adminRoles', () => ({ useAdminRolesStore: () => h.roles }))

function makeRolesStore(assignedUserCount: number) {
  const selected: RoleView = {
    name: 'ops',
    scopes: ['chat'],
    description: '运维',
    version: 2,
    assignedUserCount,
    boundGroupCount: 0,
    boundTenantCount: 0,
  }
  return reactive({
    selected,
    selectedStatus: 'ready',
    selectedError: null,
    roles: [selected],
    roleNames: ['ops'],
    status: 'ready',
    load: vi.fn(),
    loadDetail: vi.fn(),
    saveRole: vi.fn().mockResolvedValue({ ...selected, version: 3 }),
    createRoleAction: vi.fn(),
    deleteRoleAction: vi.fn(),
  })
}

let router: Router
async function mountEditor(name = 'ops') {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/roles', name: 'admin-roles', component: { template: '<div/>' } },
      { path: '/admin/roles/:name', name: 'admin-role', component: { template: '<div/>' } },
      { path: '/admin/users', name: 'admin-users', component: { template: '<div/>' } },
    ],
  })
  // memoryHistory 需先 push 触发初始导航，否则 isReady() 永不 resolve（mount 在其后，形成死锁）。
  router.push(`/admin/roles/${name}`)
  await router.isReady()
  return mount(RoleEditor, {
    props: { name },
    global: { plugins: [router], stubs: { teleport: true } },
  })
}

describe('RoleEditor', () => {
  it('后端 409 role_in_use → 就地提示并链接到该角色的绑定用户筛选', async () => {
    h.roles = makeRolesStore(0) as unknown as Record<string, unknown>
    const rolesStore = h.roles as unknown as { deleteRoleAction: ReturnType<typeof vi.fn> }
    rolesStore.deleteRoleAction = vi.fn().mockRejectedValue(
      new ApiError(409, 'HTTP 409', { error: 'role_in_use', message: '该角色被引用' }),
    )
    const wrapper = await mountEditor()
    await flushPromises()

    // 触发删除确认（绕过键入门槛直接 emit confirm）
    wrapper.findComponent(DangerConfirmDialog).vm.$emit('confirm')
    await flushPromises()

    expect(rolesStore.deleteRoleAction).toHaveBeenCalledWith('ops', 2)
    expect(wrapper.text()).toContain('无法删除')
    // 引导链接到 /admin/users?role=ops
    expect(wrapper.find('a[href*="role=ops"]').exists()).toBe(true)
  })

  it('前端软拦：assignedUserCount>0 时删除禁用 + 引导解绑链接', async () => {
    h.roles = makeRolesStore(5) as unknown as Record<string, unknown>
    const wrapper = await mountEditor()
    await flushPromises()
    const delBtn = wrapper.find('.re__del')
    expect(delBtn.attributes('disabled')).toBeDefined()
    expect(wrapper.find('a[href*="role=ops"]').exists()).toBe(true)
  })
})
