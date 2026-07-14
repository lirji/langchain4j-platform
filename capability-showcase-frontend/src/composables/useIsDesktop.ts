import { onMounted, onUnmounted, ref } from 'vue'

/**
 * 响应式桌面断点（>=1024px），与 App/AppHeader 的布局媒体查询一致。
 * 用于：桌面折叠 vs 移动抽屉的分流、隐藏侧栏 inert、断点切换清理。
 * 无 matchMedia 环境（SSR/测试）安全回退为 true（桌面）。
 */
export function useIsDesktop() {
  const isDesktop = ref(true)
  let mql: MediaQueryList | null = null
  const onChange = (e: MediaQueryListEvent): void => {
    isDesktop.value = e.matches
  }
  onMounted(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
    mql = window.matchMedia('(min-width: 1024px)')
    isDesktop.value = mql.matches
    mql.addEventListener('change', onChange)
  })
  onUnmounted(() => {
    mql?.removeEventListener('change', onChange)
  })
  return { isDesktop }
}
