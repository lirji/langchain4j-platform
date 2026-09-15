import { describe, expect, it, vi } from 'vitest'
import { nearestScroller, scrollIntoMain } from './scroll'

describe('scrollIntoMain', () => {
  it('没有滚动容器时回退到 scrollIntoView(start)', () => {
    const el = document.createElement('div')
    const spy = vi.fn()
    el.scrollIntoView = spy
    scrollIntoMain(el)
    expect(spy).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })
  })

  it('只滚最近的 overflow-y:auto 祖先，不碰 overflow:hidden 的壳', () => {
    const shell = document.createElement('div')
    const main = document.createElement('div')
    const el = document.createElement('div')
    Object.defineProperty(shell, 'style', { value: { overflowY: 'hidden' } })
    vi.spyOn(window, 'getComputedStyle').mockImplementation((node) => {
      const overflowY = node === main ? 'auto' : node === shell ? 'hidden' : 'visible'
      return { overflowY } as CSSStyleDeclaration
    })
    const scrollTo = vi.fn()
    main.scrollTo = scrollTo
    Object.defineProperty(main, 'scrollTop', { value: 40, writable: true })
    main.getBoundingClientRect = () =>
      ({ top: 80, bottom: 680, left: 0, right: 800, width: 800, height: 600 }) as DOMRect
    el.getBoundingClientRect = () =>
      ({ top: 280, bottom: 360, left: 0, right: 800, width: 800, height: 80 }) as DOMRect
    shell.appendChild(main)
    main.appendChild(el)
    document.body.appendChild(shell)

    scrollIntoMain(el)
    expect(nearestScroller(el)).toBe(main)
    expect(scrollTo).toHaveBeenCalledWith({ top: 232, behavior: 'smooth' })
    expect(el.scrollIntoView).toBeUndefined()

    shell.remove()
    vi.restoreAllMocks()
  })
})
