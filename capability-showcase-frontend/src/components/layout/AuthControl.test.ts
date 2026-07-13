import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '../../stores/auth'
import { useSessionStore } from '../../stores/session'
import AuthControl from './AuthControl.vue'

// 隔离真实 router：AuthControl → ApiKeyInput → catalog store → api/client → authorizedFetch → ../router，
// 真实 router 会懒加载尚未创建的 admin/register 视图，故按既有约定 mock 掉。
vi.mock('../../router', () => ({
  router: { currentRoute: { value: { name: 'overview', fullPath: '/' } }, replace: vi.fn(), push: vi.fn() },
  sanitizeRedirect: () => null,
}))

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'overview', component: { template: '<div/>' } },
      { path: '/login', name: 'login', component: { template: '<div/>' } },
      {
        path: '/admin/users',
        name: 'admin-users',
        meta: { requiredScopes: ['role-admin'] },
        component: { template: '<div/>' },
      },
    ],
  })
}

async function mountAuth() {
  const router = makeRouter()
  router.push('/')
  await router.isReady()
  const wrapper = mount(AuthControl, { global: { plugins: [router] } })
  return { wrapper, router }
}

beforeEach(() => setActivePinia(createPinia()))

describe('AuthControl', () => {
  it('已登录：身份 chip 展示 用户名 · 租户 · Bearer 凭证模式', async () => {
    const auth = useAuthStore()
    auth.accessToken = 'tok'
    auth.user = { username: 'alice', tenant: 'acme', scopes: ['chat'] }
    const { wrapper } = await mountAuth()

    const text = wrapper.text()
    expect(text).toContain('alice')
    expect(text).toContain('acme')
    expect(text).toContain('Bearer')
  })

  it('api-key 覆盖登录会话：高对比警告 + 清除入口，可一键清除', async () => {
    const auth = useAuthStore()
    auth.accessToken = 'tok'
    auth.user = { username: 'alice', tenant: 'acme', scopes: ['chat'] }
    const session = useSessionStore()
    session.setApiKey('sk-secret')
    const { wrapper } = await mountAuth()

    expect(wrapper.text()).toContain('账号权限预判暂停')
    // 凭证模式徽章切到 API Key
    expect(wrapper.text()).toContain('API Key')

    const clearBtn = wrapper.find('.authctl__warn-clear')
    expect(clearBtn.exists()).toBe(true)
    await clearBtn.trigger('click')

    expect(session.hasApiKey).toBe(false)
    expect(wrapper.find('.authctl__warn').exists()).toBe(false)
  })

  it('管理路由（meta.requiredScopes）隐藏 api-key 入口，避免身份混淆', async () => {
    const auth = useAuthStore()
    auth.accessToken = 'tok'
    auth.user = { username: 'alice', tenant: 'acme', scopes: ['role-admin'] }
    const { wrapper, router } = await mountAuth()

    // 普通路由：高级（api-key）入口存在
    expect(wrapper.find('.authctl__adv').exists()).toBe(true)

    await router.push('/admin/users')
    await wrapper.vm.$nextTick()

    // 管理路由：api-key 入口隐藏
    expect(wrapper.find('.authctl__adv').exists()).toBe(false)
  })
})
