import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUiStore } from './ui'

const NAV_COLLAPSED_KEY = 'showcase.navCollapsed'

/** 用全新 pinia 重建 store，触发一次 localStorage 初始读取。 */
function freshStore() {
  setActivePinia(createPinia())
  return useUiStore()
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
})
afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
})

describe('ui store · 桌面折叠（navCollapsed）', () => {
  it('setNavCollapsed(true) 持久化 "1"，重建 store 读回 true', () => {
    const ui = useUiStore()
    ui.setNavCollapsed(true)
    expect(ui.navCollapsed).toBe(true)
    expect(localStorage.getItem(NAV_COLLAPSED_KEY)).toBe('1')
    const ui2 = freshStore()
    expect(ui2.navCollapsed).toBe(true)
  })

  it('setNavCollapsed 幂等：重复 set 同值不抛错、状态稳定', () => {
    const ui = useUiStore()
    ui.setNavCollapsed(true)
    ui.setNavCollapsed(true)
    expect(ui.navCollapsed).toBe(true)
    expect(localStorage.getItem(NAV_COLLAPSED_KEY)).toBe('1')
    ui.setNavCollapsed(false)
    expect(ui.navCollapsed).toBe(false)
    expect(localStorage.getItem(NAV_COLLAPSED_KEY)).toBe('0')
  })

  it('toggleNavCollapsed 翻转并持久化', () => {
    const ui = useUiStore()
    expect(ui.navCollapsed).toBe(false)
    ui.toggleNavCollapsed()
    expect(ui.navCollapsed).toBe(true)
    expect(localStorage.getItem(NAV_COLLAPSED_KEY)).toBe('1')
    ui.toggleNavCollapsed()
    expect(ui.navCollapsed).toBe(false)
  })

  it.each([
    ['0', false],
    ['true', false],
    ['garbage', false],
    ['', false],
    ['1', true],
  ])('严格解析存储值 %s → %s', (stored, expected) => {
    localStorage.setItem(NAV_COLLAPSED_KEY, stored)
    const ui = freshStore()
    expect(ui.navCollapsed).toBe(expected)
  })

  it('localStorage.setItem 抛错时 setNavCollapsed 不阻断、内存态仍更新', () => {
    const ui = useUiStore()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded')
    })
    expect(() => ui.setNavCollapsed(true)).not.toThrow()
    expect(ui.navCollapsed).toBe(true)
  })
})

describe('ui store · 移动抽屉（sidebarOpen，仅内存）', () => {
  it('set/open/close/toggle 变更 sidebarOpen，且不写 localStorage', () => {
    const ui = useUiStore()
    expect(ui.sidebarOpen).toBe(false)
    ui.openSidebar()
    expect(ui.sidebarOpen).toBe(true)
    ui.closeSidebar()
    expect(ui.sidebarOpen).toBe(false)
    ui.setSidebarOpen(true)
    expect(ui.sidebarOpen).toBe(true)
    ui.toggleSidebar()
    expect(ui.sidebarOpen).toBe(false)
    // 移动抽屉不持久化：无 sidebar 相关 key 写入
    expect(localStorage.getItem('showcase.sidebarOpen')).toBeNull()
  })

  it('setSidebarOpen 幂等：重复设同值稳定', () => {
    const ui = useUiStore()
    ui.setSidebarOpen(true)
    ui.setSidebarOpen(true)
    expect(ui.sidebarOpen).toBe(true)
  })
})
