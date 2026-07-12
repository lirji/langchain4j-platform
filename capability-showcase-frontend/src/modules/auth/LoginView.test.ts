import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { setActivePinia, createPinia } from 'pinia'
import LoginView from './LoginView.vue'

function jsonRes(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(),
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response
}

let router: Router

async function mountLogin() {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'overview', component: { template: '<div/>' } },
      { path: '/login', name: 'login', component: LoginView },
    ],
  })
  router.push('/login')
  await router.isReady()
  return mount(LoginView, { global: { plugins: [router] } })
}

beforeEach(() => setActivePinia(createPinia()))
afterEach(() => vi.unstubAllGlobals())

describe('LoginView', () => {
  it('空表单提交：显示校验错误，不发请求', async () => {
    const f = vi.fn()
    vi.stubGlobal('fetch', f)
    const wrapper = await mountLogin()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('请输入用户名和密码')
    expect(f).not.toHaveBeenCalled()
  })

  it('登录失败：展示后端返回的人话消息', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonRes(401, { error: 'invalid_credentials', message: '用户名或密码错误' }))),
    )
    const wrapper = await mountLogin()

    await wrapper.find('#login-username').setValue('alice')
    await wrapper.find('#login-password').setValue('wrong')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('用户名或密码错误')
  })

  it('密码显隐切换', async () => {
    vi.stubGlobal('fetch', vi.fn())
    const wrapper = await mountLogin()
    const pw = wrapper.find('#login-password')
    expect(pw.attributes('type')).toBe('password')
    await wrapper.find('.login__pw-toggle').trigger('click')
    expect(wrapper.find('#login-password').attributes('type')).toBe('text')
  })
})
