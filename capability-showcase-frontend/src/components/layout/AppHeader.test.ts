import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { useUiStore } from '../../stores/ui'
import { stubMatchMedia, type ViewportStub } from '../../test/viewport'
import AppHeader from './AppHeader.vue'

const stub = { template: '<div />' }
function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', name: 'overview', component: stub }],
  })
}

async function mountHeader() {
  const router = makeRouter()
  await router.push('/')
  await router.isReady()
  return mount(AppHeader, {
    global: {
      plugins: [router],
      stubs: { AuthControl: true, ThemeToggle: true, Breadcrumb: true, DensityToggle: true },
    },
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
})

describe('AppHeader · 菜单按钮（桌面折叠开关）', () => {
  // jsdom 无 matchMedia → useIsDesktop 回退 isDesktop=true（桌面路径）。
  it('桌面点击 ☰ 切换 navCollapsed，aria-expanded 同步反映', async () => {
    const ui = useUiStore()
    const wrapper = await mountHeader()
    const btn = wrapper.find('.header__menu')
    // 初始展开：navCollapsed=false → aria-expanded=true
    expect(btn.attributes('aria-expanded')).toBe('true')
    expect(btn.attributes('aria-label')).toBe('收起导航菜单')

    await btn.trigger('click')
    expect(ui.navCollapsed).toBe(true)
    expect(btn.attributes('aria-expanded')).toBe('false')
    expect(btn.attributes('aria-label')).toBe('展开导航菜单')

    await btn.trigger('click')
    expect(ui.navCollapsed).toBe(false)
    expect(btn.attributes('aria-expanded')).toBe('true')
  })

  it('菜单按钮在桌面亦可见（有常驻可恢复入口）', async () => {
    const wrapper = await mountHeader()
    const btn = wrapper.find('.header__menu')
    expect(btn.exists()).toBe(true)
  })
})

describe('AppHeader · 菜单按钮（移动抽屉分流）', () => {
  let viewport: ViewportStub | null = null

  afterEach(() => {
    viewport?.restore()
    viewport = null
  })

  it('移动视口点击 ☰ 切换 sidebarOpen（不动 navCollapsed）', async () => {
    viewport = stubMatchMedia({ desktop: false, phone: true })
    const ui = useUiStore()
    const wrapper = await mountHeader()
    const btn = wrapper.find('.header__menu')

    expect(ui.sidebarOpen).toBe(false)
    await btn.trigger('click')
    expect(ui.sidebarOpen).toBe(true)
    expect(ui.navCollapsed).toBe(false)
    expect(btn.attributes('aria-expanded')).toBe('true')

    await btn.trigger('click')
    expect(ui.sidebarOpen).toBe(false)
    expect(btn.attributes('aria-expanded')).toBe('false')
  })
})
