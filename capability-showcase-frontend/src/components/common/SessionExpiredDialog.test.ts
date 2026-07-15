import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { setActivePinia, createPinia } from 'pinia'
import { useUiStore } from '../../stores/ui'
import SessionExpiredDialog from './SessionExpiredDialog.vue'

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'overview', component: { template: '<div/>' } },
      { path: '/login', name: 'login', component: { template: '<div/>' } },
    ],
  })
}

async function mountDialog(router: Router) {
  router.push('/')
  await router.isReady()
  // stub teleport → 面板内联渲染，便于 find。
  return mount(SessionExpiredDialog, { global: { plugins: [router], stubs: { teleport: true } } })
}

beforeEach(() => setActivePinia(createPinia()))

describe('SessionExpiredDialog', () => {
  it('关闭态不渲染面板；openAuthModal 后渲染', async () => {
    const wrapper = await mountDialog(makeRouter())
    expect(wrapper.find('.sed__panel').exists()).toBe(false)
    useUiStore().openAuthModal('/m/x')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.sed__panel').exists()).toBe(true)
  })

  it('apikey 模式点「重新登录」→ 跳登录页带 redirect（还原 deep-link），并关模态', async () => {
    const router = makeRouter()
    const replace = vi.spyOn(router, 'replace')
    const wrapper = await mountDialog(router)
    const ui = useUiStore()
    ui.openAuthModal('/m/agent/run')
    await wrapper.vm.$nextTick()

    await wrapper.find('.sed__btn--primary').trigger('click')
    await flushPromises()

    expect(replace).toHaveBeenCalledWith({ name: 'login', query: { redirect: '/m/agent/run' } })
    expect(ui.authModalOpen).toBe(false)
  })

  it('点「稍后」→ 关模态、不跳转', async () => {
    const router = makeRouter()
    const replace = vi.spyOn(router, 'replace')
    const wrapper = await mountDialog(router)
    const ui = useUiStore()
    ui.openAuthModal('/x')
    await wrapper.vm.$nextTick()

    // 第一个 .sed__btn 是「稍后」（primary 另带 --primary 类）。
    await wrapper.find('.sed__btn').trigger('click')
    expect(ui.authModalOpen).toBe(false)
    expect(replace).not.toHaveBeenCalled()
  })
})
