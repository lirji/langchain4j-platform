import { onMounted, onUnmounted, ref } from 'vue'

/**
 * 断点常量单一真源（px）。CSS 侧 @media 与此对齐（声明见 src/styles/tokens.css 顶部注释）：
 * desktop: >=1024 常驻侧栏；<1024 平板及以下抽屉导航。phone: <=640 手机精修档。
 */
export const BREAKPOINTS = {
  desktop: 1024,
  phone: 640,
} as const

/**
 * 响应式断点：{ isDesktop, isPhone }（641-1023 平板档 = 两者皆 false）。
 * setup 阶段同步读取初值，避免移动端首帧按桌面渲染；
 * 无 matchMedia 环境（jsdom/降级）安全回退桌面（isDesktop=true, isPhone=false）。
 */
export function useBreakpoint() {
  const supported = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  const desktopMql = supported ? window.matchMedia(`(min-width: ${BREAKPOINTS.desktop}px)`) : null
  const phoneMql = supported ? window.matchMedia(`(max-width: ${BREAKPOINTS.phone}px)`) : null
  const isDesktop = ref(desktopMql ? desktopMql.matches : true)
  const isPhone = ref(phoneMql ? phoneMql.matches : false)
  const onDesktopChange = (e: MediaQueryListEvent): void => {
    isDesktop.value = e.matches
  }
  const onPhoneChange = (e: MediaQueryListEvent): void => {
    isPhone.value = e.matches
  }
  onMounted(() => {
    desktopMql?.addEventListener('change', onDesktopChange)
    phoneMql?.addEventListener('change', onPhoneChange)
  })
  onUnmounted(() => {
    desktopMql?.removeEventListener('change', onDesktopChange)
    phoneMql?.removeEventListener('change', onPhoneChange)
  })
  return { isDesktop, isPhone }
}
