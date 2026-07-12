import { onMounted, onUnmounted } from 'vue'
import { useUiStore } from '../stores/ui'

/**
 * 全局快捷键（在 App 挂载）：
 * - ⌘/Ctrl+K  打开/关闭命令面板
 * - /          聚焦侧栏筛选框（非输入态时）
 * - ⌘/Ctrl+J  打开/关闭请求历史
 * - ⌘/Ctrl+/  快捷键帮助
 * - ⌘/Ctrl+⇧L 切换主题
 * - ⌘/Ctrl+⇧D 切换密度
 *
 * 注意：能力运行的 ⌘⏎ / Esc 由 CapabilityRunner 自行处理，这里不重复注册。
 */
const SIDENAV_FILTER_ID = 'sidenav-filter'

function isTypingTarget(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

export function useGlobalShortcuts(): void {
  const ui = useUiStore()

  function onKeydown(e: KeyboardEvent): void {
    const mod = e.metaKey || e.ctrlKey
    const key = e.key
    const lower = key.length === 1 ? key.toLowerCase() : key

    if (mod && !e.altKey) {
      if (!e.shiftKey && lower === 'k') {
        e.preventDefault()
        ui.toggleCmdk()
        return
      }
      if (!e.shiftKey && lower === 'j') {
        e.preventDefault()
        ui.toggleHistory()
        return
      }
      if (!e.shiftKey && key === '/') {
        e.preventDefault()
        ui.toggleShortcuts()
        return
      }
      if (e.shiftKey && lower === 'l') {
        e.preventDefault()
        ui.cycleTheme()
        return
      }
      if (e.shiftKey && lower === 'd') {
        e.preventDefault()
        ui.cycleDensity()
        return
      }
    }

    // 裸 "/"：聚焦侧栏筛选（当前不在输入控件中）
    if (!mod && !e.altKey && key === '/' && !isTypingTarget(e.target)) {
      const el = document.getElementById(SIDENAV_FILTER_ID) as HTMLInputElement | null
      if (el) {
        e.preventDefault()
        el.focus()
        el.select?.()
      }
    }
  }

  onMounted(() => window.addEventListener('keydown', onKeydown))
  onUnmounted(() => window.removeEventListener('keydown', onKeydown))
}
