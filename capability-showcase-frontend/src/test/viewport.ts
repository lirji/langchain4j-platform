/**
 * 测试用 matchMedia stub：jsdom 无 matchMedia，useBreakpoint/useIsDesktop 在测试里
 * 恒走"回退桌面"分支——移动分支要靠本工具驱动。
 * 约定：含 min-width 的查询按 desktop 档判定，含 max-width 的按 phone 档判定，
 * 其余查询（如 prefers-color-scheme）恒 false。用完必须 restore()。
 */
type Listener = (e: MediaQueryListEvent) => void

export interface ViewportStub {
  /** 切换断点档位并向所有已注册监听派发 change 事件（模拟旋屏/缩放窗口） */
  set(next: { desktop?: boolean; phone?: boolean }): void
  /** 还原 window.matchMedia（jsdom 下即删除） */
  restore(): void
}

export function stubMatchMedia(initial: { desktop?: boolean; phone?: boolean } = {}): ViewportStub {
  const state = { desktop: initial.desktop ?? true, phone: initial.phone ?? false }
  const lists: Array<{ query: string; listeners: Set<Listener> }> = []
  const matchFor = (query: string): boolean => {
    if (query.includes('min-width')) return state.desktop
    if (query.includes('max-width')) return state.phone
    return false
  }
  const original = (window as { matchMedia?: typeof window.matchMedia }).matchMedia
  window.matchMedia = ((query: string) => {
    const listeners = new Set<Listener>()
    lists.push({ query, listeners })
    return {
      media: query,
      get matches() {
        return matchFor(query)
      },
      addEventListener: (_type: string, fn: Listener) => {
        listeners.add(fn)
      },
      removeEventListener: (_type: string, fn: Listener) => {
        listeners.delete(fn)
      },
      addListener: (fn: Listener) => {
        listeners.add(fn)
      },
      removeListener: (fn: Listener) => {
        listeners.delete(fn)
      },
      onchange: null,
      dispatchEvent: () => false,
    } as unknown as MediaQueryList
  }) as typeof window.matchMedia
  return {
    set(next) {
      if (next.desktop !== undefined) state.desktop = next.desktop
      if (next.phone !== undefined) state.phone = next.phone
      for (const { query, listeners } of lists) {
        const e = { matches: matchFor(query), media: query } as MediaQueryListEvent
        listeners.forEach((fn) => fn(e))
      }
    },
    restore() {
      if (original) window.matchMedia = original
      else delete (window as { matchMedia?: typeof window.matchMedia }).matchMedia
    },
  }
}
