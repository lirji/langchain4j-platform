import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'

export type ThemePref = 'light' | 'dark' | 'system'
export type Density = 'comfortable' | 'compact'
const THEME_KEY = 'showcase.theme'
const DENSITY_KEY = 'showcase.density'
const NAV_COLLAPSED_KEY = 'showcase.navCollapsed'

function readStoredTheme(): ThemePref {
  try {
    const v = localStorage.getItem(THEME_KEY)
    if (v === 'light' || v === 'dark' || v === 'system') return v
  } catch {
    /* localStorage 不可用时忽略 */
  }
  // 默认亮色（而非跟随系统）：控制台以明亮为主基调，暗色由用户在顶栏主动切换。
  return 'light'
}

function readStoredDensity(): Density {
  try {
    const v = localStorage.getItem(DENSITY_KEY)
    if (v === 'comfortable' || v === 'compact') return v
  } catch {
    /* localStorage 不可用时忽略 */
  }
  return 'comfortable'
}

function readStoredNavCollapsed(): boolean {
  try {
    return localStorage.getItem(NAV_COLLAPSED_KEY) === '1'
  } catch {
    return false
  }
}

function prefersDark(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  )
}

/**
 * 全局 UI 状态：主题 / 密度（均可持久化，非敏感）、移动端侧栏、能力筛选，
 * 以及全局浮层（命令面板 / 历史抽屉 / 快捷键弹窗）开关。API Key 不在此，仅内存于 session store。
 */
export const useUiStore = defineStore('ui', () => {
  const theme = ref<ThemePref>(readStoredTheme())
  const density = ref<Density>(readStoredDensity())
  const sidebarOpen = ref(false) // 移动端抽屉开关
  const navCollapsed = ref(readStoredNavCollapsed()) // 桌面端侧栏折叠（持久化，非敏感）
  const filter = ref('')

  // 全局浮层开关（互斥打开，避免多层焦点陷阱相互抢占）。
  const cmdkOpen = ref(false)
  const historyOpen = ref(false)
  const shortcutsOpen = ref(false)

  const effectiveTheme = computed<'light' | 'dark'>(() =>
    theme.value === 'system' ? (prefersDark() ? 'dark' : 'light') : theme.value,
  )

  function applyTheme(): void {
    if (typeof document === 'undefined') return
    document.documentElement.setAttribute('data-theme', effectiveTheme.value)
  }

  function setTheme(pref: ThemePref): void {
    theme.value = pref
    try {
      localStorage.setItem(THEME_KEY, pref)
    } catch {
      /* 忽略 */
    }
    applyTheme()
  }

  function cycleTheme(): void {
    const order: ThemePref[] = ['light', 'dark', 'system']
    const next = order[(order.indexOf(theme.value) + 1) % order.length]
    setTheme(next)
  }

  function applyDensity(): void {
    if (typeof document === 'undefined') return
    document.documentElement.dataset.density = density.value
  }

  function setDensity(d: Density): void {
    density.value = d
    try {
      localStorage.setItem(DENSITY_KEY, d)
    } catch {
      /* 忽略 */
    }
    applyDensity()
  }

  function cycleDensity(): void {
    setDensity(density.value === 'comfortable' ? 'compact' : 'comfortable')
  }

  function toggleSidebar(): void {
    sidebarOpen.value = !sidebarOpen.value
  }
  function closeSidebar(): void {
    sidebarOpen.value = false
  }
  /** 桌面端折叠/展开侧栏（持久化）。 */
  function toggleNavCollapsed(): void {
    navCollapsed.value = !navCollapsed.value
    try {
      localStorage.setItem(NAV_COLLAPSED_KEY, navCollapsed.value ? '1' : '0')
    } catch {
      /* 忽略 */
    }
  }

  // ── 浮层开关（open* 会关闭其它浮层，保持单层模态）──
  function closeCmdk(): void {
    cmdkOpen.value = false
  }
  function closeHistory(): void {
    historyOpen.value = false
  }
  function closeShortcuts(): void {
    shortcutsOpen.value = false
  }
  function openCmdk(): void {
    historyOpen.value = false
    shortcutsOpen.value = false
    cmdkOpen.value = true
  }
  function openHistory(): void {
    cmdkOpen.value = false
    shortcutsOpen.value = false
    historyOpen.value = true
  }
  function openShortcuts(): void {
    cmdkOpen.value = false
    historyOpen.value = false
    shortcutsOpen.value = true
  }
  function toggleCmdk(): void {
    cmdkOpen.value ? closeCmdk() : openCmdk()
  }
  function toggleHistory(): void {
    historyOpen.value ? closeHistory() : openHistory()
  }
  function toggleShortcuts(): void {
    shortcutsOpen.value ? closeShortcuts() : openShortcuts()
  }

  watch(effectiveTheme, applyTheme, { immediate: true })
  watch(density, applyDensity, { immediate: true })

  return {
    theme,
    density,
    effectiveTheme,
    sidebarOpen,
    navCollapsed,
    filter,
    cmdkOpen,
    historyOpen,
    shortcutsOpen,
    setTheme,
    cycleTheme,
    applyTheme,
    setDensity,
    cycleDensity,
    applyDensity,
    toggleSidebar,
    closeSidebar,
    toggleNavCollapsed,
    openCmdk,
    closeCmdk,
    toggleCmdk,
    openHistory,
    closeHistory,
    toggleHistory,
    openShortcuts,
    closeShortcuts,
    toggleShortcuts,
  }
})
