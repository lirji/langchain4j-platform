/** 向上找最近的真正滚动容器（overflow-y auto/scroll）。 */
export function nearestScroller(node: HTMLElement | null): HTMLElement | null {
  for (let el = node?.parentElement ?? null; el; el = el.parentElement) {
    const oy = getComputedStyle(el).overflowY
    if (oy === 'auto' || oy === 'scroll') return el
  }
  return null
}

/**
 * 把目标滚进最近的滚动容器（壳层是 .app-main，不是 window）。
 * 不能用 scrollIntoView(block:'start')：它会连 overflow:hidden 的 app-shell
 * 一起滚，把顶栏顶出屏外。
 */
export function scrollIntoMain(
  el: Element | null | undefined,
  opts?: { behavior?: ScrollBehavior; offset?: number },
): void {
  if (!el || !(el instanceof HTMLElement)) return
  const behavior = opts?.behavior ?? 'smooth'
  const offset = opts?.offset ?? 8
  const scroller = nearestScroller(el)
  if (!scroller) {
    el.scrollIntoView?.({ behavior, block: 'start' })
    return
  }
  const top =
    el.getBoundingClientRect().top - scroller.getBoundingClientRect().top + scroller.scrollTop - offset
  scroller.scrollTo({ top: Math.max(0, top), behavior })
}
