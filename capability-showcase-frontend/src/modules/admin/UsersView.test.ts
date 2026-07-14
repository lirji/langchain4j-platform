import { describe, it, expect, beforeEach, vi } from 'vitest'
import { reactive } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import UsersView from './UsersView.vue'
import type { UserAdminView } from '../../types/admin'

// 用 vi.hoisted 持有可变的 mock store（工厂闭包读取，mount 时已由 beforeEach 赋值）。
const h = vi.hoisted(() => ({ store: null as unknown as Record<string, unknown> }))
vi.mock('../../stores/adminUsers', () => ({ useAdminUsersStore: () => h.store }))

function user(over: Partial<UserAdminView>): UserAdminView {
  return {
    username: 'u',
    userId: 'id-u',
    tenant: 'acme',
    directScopes: [],
    roles: [],
    groups: [],
    effectiveScopes: [],
    enabled: true,
    version: 0,
    ...over,
  }
}

function makeStore(items: UserAdminView[], status = 'ready') {
  return reactive({
    items,
    total: items.length,
    status,
    error: null,
    hasNext: false,
    hasPrev: false,
    offset: 0,
    pageSize: 50,
    load: vi.fn(),
    reload: vi.fn(),
    nextPage: vi.fn(),
    prevPage: vi.fn(),
  })
}

let router: Router
async function mountView() {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'overview', component: { template: '<div/>' } },
      { path: '/admin/users', name: 'admin-users', component: { template: '<div/>' } },
      { path: '/admin/users/:username', name: 'admin-user', component: { template: '<div/>' } },
    ],
  })
  // memoryHistory 需先 push 触发初始导航，否则 isReady() 永不 resolve（mount 在其后，形成死锁）。
  router.push('/admin/users')
  await router.isReady()
  return mount(UsersView, { global: { plugins: [router] } })
}

describe('UsersView', () => {
  beforeEach(() => {
    h.store = makeStore([
      user({ username: 'alice', userId: 'id-1', tenant: 'acme', roles: ['ops'], effectiveScopes: ['chat', 'role-admin'], version: 3 }),
      user({ username: 'bob', userId: 'id-2', tenant: 'globex', roles: [], effectiveScopes: [], enabled: false, version: 0 }),
    ]) as unknown as Record<string, unknown>
  })

  it('从 store 渲染用户行', async () => {
    const wrapper = await mountView()
    const rows = wrapper.findAll('.uv__row')
    expect(rows.length).toBe(2)
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('bob')
    // effectiveScopes 摘要为计数，绝不误标 directScopes
    expect(wrapper.text()).toContain('2 项')
  })

  it('当前页客户端文本筛选窄化', async () => {
    const wrapper = await mountView()
    await wrapper.find('input[type="search"]').setValue('alice')
    const rows = wrapper.findAll('.uv__row')
    expect(rows.length).toBe(1)
    expect(rows[0].text()).toContain('alice')
    expect(wrapper.text()).not.toContain('bob')
  })

  it('全空 → 展示空态与新建入口', async () => {
    h.store = makeStore([]) as unknown as Record<string, unknown>
    const wrapper = await mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无用户')
    expect(wrapper.findAll('.uv__row').length).toBe(0)
  })
})
