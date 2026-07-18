import { useBreakpoint } from './useBreakpoint'

/**
 * 兼容包装：既有消费方（App/AppHeader/useGlobalShortcuts）继续用 { isDesktop }，
 * 实现已移至 useBreakpoint（断点常量单一真源 + setup 同步初始化）。
 */
export function useIsDesktop() {
  const { isDesktop } = useBreakpoint()
  return { isDesktop }
}
