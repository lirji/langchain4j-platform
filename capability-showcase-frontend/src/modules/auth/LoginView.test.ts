import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { nextTick } from 'vue'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '../../stores/auth'
import LoginView from './LoginView.vue'

// DEMO_LOGIN_ENABLED 由测试逐例控制（getter 读 hoisted 状态）；其余 config 导出保留真实值。
const cfgState = vi.hoisted(() => ({ demoEnabled: true, oidcEnabled: false, tenants: ['acme'] as string[] }))
vi.mock('../../config', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../config')>()
  return {
    ...actual,
    get DEMO_LOGIN_ENABLED() {
      return cfgState.demoEnabled
    },
    get OIDC_ENABLED() {
      return cfgState.oidcEnabled
    },
    get CASDOOR_TENANTS() {
      return cfgState.tenants
    },
  }
})

// 隔离真实 router（懒加载尚未创建的 admin/register 视图会解析失败）；保留 sanitizeRedirect 真实语义。
vi.mock('../../router', () => ({
  router: { currentRoute: { value: { name: 'login', fullPath: '/login' } }, replace: vi.fn(), push: vi.fn() },
  sanitizeRedirect: (raw: unknown) =>
    typeof raw === 'string' && raw.startsWith('/') && !raw.startsWith('//') ? raw : null,
}))

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

async function mountLogin(path = '/login') {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'overview', component: { template: '<div/>' } },
      { path: '/login', name: 'login', component: LoginView },
      { path: '/register', name: 'register', component: { template: '<div/>' } },
    ],
  })
  router.push(path)
  await router.isReady()
  return mount(LoginView, { global: { plugins: [router] } })
}

beforeEach(() => {
  setActivePinia(createPinia())
  cfgState.demoEnabled = true
  cfgState.oidcEnabled = false
  cfgState.tenants = ['acme']
})
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

  it('DEMO_LOGIN_ENABLED=true（默认）：渲染演示账号卡', async () => {
    vi.stubGlobal('fetch', vi.fn())
    const wrapper = await mountLogin()
    expect(wrapper.findAll('.lp-demo').length).toBe(3)
    expect(wrapper.find('.lp-divider').exists()).toBe(true)
  })

  it('DEMO_LOGIN_ENABLED=false：不渲染演示账号卡', async () => {
    cfgState.demoEnabled = false
    vi.stubGlobal('fetch', vi.fn())
    const wrapper = await mountLogin()
    expect(wrapper.find('.lp-demo').exists()).toBe(false)
    expect(wrapper.find('.lp-divider').exists()).toBe(false)
  })

  it('组件源码不含任何内联明文演示口令', () => {
    // vitest cwd = 前端包根；不用 import.meta.url（其 scheme 在测试环境不保证为 file://）。
    const src = readFileSync(resolve(process.cwd(), 'src/modules/auth/LoginView.vue'), 'utf8')
    expect(src).not.toContain('demo12345')
  })

  it('注册入口随 registrationEnabled 显隐（fail-closed）', async () => {
    vi.stubGlobal('fetch', vi.fn())
    const wrapper = await mountLogin()
    // 默认 publicConfig=null → 不显示
    expect(wrapper.text()).not.toContain('去注册')

    useAuthStore().publicConfig = { registrationEnabled: true, passwordMinLength: 8, passwordMaxLength: 64 }
    await nextTick()
    expect(wrapper.text()).toContain('去注册')

    useAuthStore().publicConfig = { registrationEnabled: false, passwordMinLength: 8, passwordMaxLength: 64 }
    await nextTick()
    expect(wrapper.text()).not.toContain('去注册')
  })

  it('合法 portal 参数在 OIDC 模式下一次性自动发起 Casdoor', async () => {
    cfgState.oidcEnabled = true
    const auth = useAuthStore()
    const start = vi.spyOn(auth, 'startOidcLogin').mockResolvedValue()

    await mountLogin('/login?source=portal&auto=1&tenant=acme&redirect=%2Fm%2Frag')
    await flushPromises()

    expect(start).toHaveBeenCalledTimes(1)
    expect(start).toHaveBeenCalledWith('acme', '/m/rag')
  })

  it('人工输入不存在租户时停留本页并显示错误', async () => {
    cfgState.oidcEnabled = true
    const auth = useAuthStore()
    const start = vi.spyOn(auth, 'startOidcLogin')
    const wrapper = await mountLogin('/login?redirect=%2Fm%2Frag')

    await wrapper.get('#login-tenant').setValue('not-exists')
    await wrapper.get('.lp-btn--casdoor').trigger('click')
    await flushPromises()

    expect(start).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('租户 not-exists 不存在或未开放')
    expect(wrapper.text()).toContain('当前可用租户：acme')
  })

  it('portal auto 的未知租户同样不跳转', async () => {
    cfgState.oidcEnabled = true
    const auth = useAuthStore()
    const start = vi.spyOn(auth, 'startOidcLogin')

    const wrapper = await mountLogin('/login?source=portal&auto=1&tenant=not-exists')
    await flushPromises()

    expect(start).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('租户 not-exists 不存在或未开放')
  })

  it('allowlist 为空时禁用 OIDC 表单并 fail-closed', async () => {
    cfgState.oidcEnabled = true
    cfgState.tenants = []
    const wrapper = await mountLogin()

    expect(wrapper.get('#login-tenant').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.lp-btn--casdoor').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('当前未配置可用租户')
  })

  it('普通登录、非法 tenant 与非 OIDC 模式均不自动跳', async () => {
    const auth = useAuthStore()
    const start = vi.spyOn(auth, 'startOidcLogin').mockResolvedValue()

    cfgState.oidcEnabled = true
    await mountLogin('/login')
    await flushPromises()
    await mountLogin('/login?source=portal&auto=1&tenant=..%2Fevil')
    await flushPromises()
    expect(start).not.toHaveBeenCalled()

    cfgState.oidcEnabled = false
    await mountLogin('/login?source=portal&auto=1&tenant=acme')
    await flushPromises()
    expect(start).not.toHaveBeenCalled()
  })
})
