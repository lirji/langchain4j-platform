import { nextTick, onScopeDispose, watch, type Ref } from 'vue'

/**
 * 浮层焦点陷阱：当 active 为真时，把 Tab 焦点圈在 container 内，
 * Esc 触发 onEscape，关闭后把焦点归还给打开前的元素。
 * 供命令面板 / 历史抽屉 / 快捷键弹窗复用。
 */
const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

export interface FocusTrapOptions {
  /** 响应式开关（getter）。 */
  active: () => boolean
  /** 陷阱容器。 */
  container: Ref<HTMLElement | null>
  /** Esc 回调（通常是关闭浮层）。 */
  onEscape?: () => void
  /** 打开后首个聚焦元素（默认容器内首个可聚焦元素）。 */
  initialFocus?: () => HTMLElement | null
}

export function useFocusTrap(opts: FocusTrapOptions): void {
  let previouslyFocused: HTMLElement | null = null

  function focusables(): HTMLElement[] {
    const root = opts.container.value
    if (!root) return []
    return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
      (el) => el.offsetParent !== null || el === document.activeElement,
    )
  }

  function onKeydown(e: KeyboardEvent): void {
    if (e.key === 'Escape') {
      e.preventDefault()
      opts.onEscape?.()
      return
    }
    if (e.key !== 'Tab') return
    const items = focusables()
    if (items.length === 0) {
      e.preventDefault()
      return
    }
    const first = items[0]
    const last = items[items.length - 1]
    const activeEl = document.activeElement as HTMLElement | null
    const inside = !!opts.container.value?.contains(activeEl)
    if (e.shiftKey) {
      if (activeEl === first || !inside) {
        e.preventDefault()
        last.focus()
      }
    } else if (activeEl === last || !inside) {
      e.preventDefault()
      first.focus()
    }
  }

  watch(
    opts.active,
    (open) => {
      if (open) {
        previouslyFocused = document.activeElement as HTMLElement | null
        document.addEventListener('keydown', onKeydown, true)
        void nextTick(() => {
          const target = opts.initialFocus?.() ?? focusables()[0] ?? null
          target?.focus()
        })
      } else {
        document.removeEventListener('keydown', onKeydown, true)
        previouslyFocused?.focus?.()
        previouslyFocused = null
      }
    },
  )

  onScopeDispose(() => {
    document.removeEventListener('keydown', onKeydown, true)
  })
}
