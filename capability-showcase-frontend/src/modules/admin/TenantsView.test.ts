import { describe, it, expect, beforeEach, vi } from 'vitest'
import { reactive } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import TenantsView from './TenantsView.vue'
import type { TenantView } from '../../types/admin'

const h = vi.hoisted(() => ({ store: null as unknown as Record<string, unknown> }))
vi.mock('../../stores/adminTenants', () => ({ useAdminTenantsStore: () => h.store }))

function tenant(over: Partial<TenantView>): TenantView {
  return {
    tenant: 'acme',
    baseRoles: ['viewer'],
    effectiveBaseScopes: ['chat'],
    memberCount: 3,
    version: 0,
    ...over,
  }
}

function makeStore(tenants: TenantView[], status = 'ready') {
  return reactive({ tenants, status, error: null, load: vi.fn() })
}

let router: Router
async function mountView() {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/tenants', name: 'admin-tenants', component: { template: '<div/>' } },
      { path: '/admin/tenants/:tenant', name: 'admin-tenant', component: { template: '<div/>' } },
    ],
  })
  router.push('/admin/tenants')
  await router.isReady()
  return mount(TenantsView, { global: { plugins: [router] } })
}

describe('TenantsView', () => {
  beforeEach(() => {
    h.store = makeStore([
      tenant({ tenant: 'acme', baseRoles: ['viewer'], effectiveBaseScopes: ['chat', 'agent'], memberCount: 5 }),
      tenant({ tenant: 'globex', baseRoles: [], effectiveBaseScopes: [], memberCount: 0, version: -1 }),
    ]) as unknown as Record<string, unknown>
  })

  it('渲染租户行', async () => {
    const wrapper = await mountView()
    expect(wrapper.findAll('.tv__row').length).toBe(2)
    expect(wrapper.text()).toContain('acme')
    expect(wrapper.text()).toContain('globex')
    expect(wrapper.text()).toContain('2 项') // effectiveBaseScopes 计数
  })

  it('全空 → 展示空态', async () => {
    h.store = makeStore([]) as unknown as Record<string, unknown>
    const wrapper = await mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无租户基础角色')
    expect(wrapper.findAll('.tv__row').length).toBe(0)
  })
})
