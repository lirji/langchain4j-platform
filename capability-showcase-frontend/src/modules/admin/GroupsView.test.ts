import { describe, it, expect, beforeEach, vi } from 'vitest'
import { reactive } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import GroupsView from './GroupsView.vue'
import type { GroupView } from '../../types/admin'

const h = vi.hoisted(() => ({ store: null as unknown as Record<string, unknown> }))
vi.mock('../../stores/adminGroups', () => ({ useAdminGroupsStore: () => h.store }))

function group(over: Partial<GroupView>): GroupView {
  return {
    name: 'ops',
    description: '',
    roles: ['viewer'],
    effectiveScopes: ['chat'],
    memberCount: 2,
    version: 0,
    ...over,
  }
}

function makeStore(groups: GroupView[], status = 'ready') {
  return reactive({ groups, status, error: null, load: vi.fn() })
}

let router: Router
async function mountView() {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/groups', name: 'admin-groups', component: { template: '<div/>' } },
      { path: '/admin/groups/:name', name: 'admin-group', component: { template: '<div/>' } },
    ],
  })
  router.push('/admin/groups')
  await router.isReady()
  return mount(GroupsView, { global: { plugins: [router] } })
}

describe('GroupsView', () => {
  beforeEach(() => {
    h.store = makeStore([
      group({ name: 'ops-team', description: '运维组', roles: ['ops', 'viewer'], memberCount: 4 }),
      group({ name: 'sales', description: '', roles: [], memberCount: 0 }),
    ]) as unknown as Record<string, unknown>
  })

  it('渲染用户组行', async () => {
    const wrapper = await mountView()
    expect(wrapper.findAll('.gv__row').length).toBe(2)
    expect(wrapper.text()).toContain('ops-team')
    expect(wrapper.text()).toContain('sales')
    expect(wrapper.text()).toContain('运维组')
  })

  it('全空 → 展示空态与新建入口', async () => {
    h.store = makeStore([]) as unknown as Record<string, unknown>
    const wrapper = await mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无用户组')
    expect(wrapper.findAll('.gv__row').length).toBe(0)
  })
})
