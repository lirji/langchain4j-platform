import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { useBreakpoint, BREAKPOINTS } from './useBreakpoint'
import { stubMatchMedia, type ViewportStub } from '../test/viewport'

/** 在真实组件生命周期内调用 composable（onMounted/onUnmounted 需要实例） */
function mountHook() {
  let result!: ReturnType<typeof useBreakpoint>
  const Host = defineComponent({
    setup() {
      result = useBreakpoint()
      return () => h('div')
    },
  })
  const wrapper = mount(Host)
  return { result, wrapper }
}

describe('useBreakpoint', () => {
  let stub: ViewportStub | null = null
  let wrapper: VueWrapper | null = null

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    stub?.restore()
    stub = null
  })

  it('断点常量与 CSS 约定对齐（tokens.css 顶部声明）', () => {
    expect(BREAKPOINTS.desktop).toBe(1024)
    expect(BREAKPOINTS.phone).toBe(640)
  })

  it('无 matchMedia（jsdom 原生）回退桌面：isDesktop=true, isPhone=false', () => {
    const m = mountHook()
    wrapper = m.wrapper
    expect(m.result.isDesktop.value).toBe(true)
    expect(m.result.isPhone.value).toBe(false)
  })

  it('phone 档 setup 同步初始化：挂载即为移动态，无首帧桌面闪烁', () => {
    stub = stubMatchMedia({ desktop: false, phone: true })
    const m = mountHook()
    wrapper = m.wrapper
    expect(m.result.isDesktop.value).toBe(false)
    expect(m.result.isPhone.value).toBe(true)
  })

  it('平板中间档（641-1023）：两者皆 false', () => {
    stub = stubMatchMedia({ desktop: false, phone: false })
    const m = mountHook()
    wrapper = m.wrapper
    expect(m.result.isDesktop.value).toBe(false)
    expect(m.result.isPhone.value).toBe(false)
  })

  it('监听断点切换：set() 派发 change 后 refs 跟随更新', () => {
    stub = stubMatchMedia({ desktop: false, phone: true })
    const m = mountHook()
    wrapper = m.wrapper
    stub.set({ desktop: true, phone: false })
    expect(m.result.isDesktop.value).toBe(true)
    expect(m.result.isPhone.value).toBe(false)
  })

  it('卸载后不再响应变更（监听已移除）', () => {
    stub = stubMatchMedia({ desktop: true, phone: false })
    const m = mountHook()
    m.wrapper.unmount()
    stub.set({ desktop: false, phone: true })
    expect(m.result.isDesktop.value).toBe(true)
    expect(m.result.isPhone.value).toBe(false)
  })
})
