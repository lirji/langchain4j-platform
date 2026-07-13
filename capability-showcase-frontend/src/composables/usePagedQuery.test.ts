import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { effectScope, nextTick } from 'vue'
import { usePagedQuery } from './usePagedQuery'

/** 可控 deferred，便于测乱序 resolve。 */
function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

describe('usePagedQuery', () => {
  afterEach(() => vi.useRealTimers())

  it('乱序保护：慢请求后到也不覆盖新结果', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const dA = deferred<{ items: number[]; total: number }>()
      const dB = deferred<{ items: number[]; total: number }>()
      const fetcher = vi.fn().mockReturnValueOnce(dA.promise).mockReturnValueOnce(dB.promise)
      const q = usePagedQuery<number>({ fetcher })

      void q.load() // A (seq=1)
      void q.load() // B (seq=2)，A 的 controller 被 abort
      dB.resolve({ items: [2], total: 1 }) // 新请求先返回
      await nextTick()
      dA.resolve({ items: [1], total: 1 }) // 陈旧请求后返回 → 必须丢弃
      await nextTick()

      expect(q.items.value).toEqual([2])
    })
    scope.stop()
  })

  it('中止：连续 load，前一次 signal.aborted=true', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const signals: AbortSignal[] = []
      const fetcher = vi.fn(({ signal }: { signal: AbortSignal }) => {
        signals.push(signal)
        return new Promise<{ items: number[]; total: number }>(() => {}) // 永不 resolve
      })
      const q = usePagedQuery<number>({ fetcher })
      void q.load()
      void q.load()
      expect(signals[0].aborted).toBe(true)
      expect(signals[1].aborted).toBe(false)
    })
    scope.stop()
  })

  it('防抖：连续 setFilter 只触发一次 fetcher', async () => {
    vi.useFakeTimers()
    const scope = effectScope()
    scope.run(() => {
      const fetcher = vi.fn().mockResolvedValue({ items: [], total: 0 })
      const q = usePagedQuery<number>({ fetcher, debounceMs: 300 })
      q.setFilter('q', 'a')
      q.setFilter('q', 'ab')
      q.setFilter('q', 'abc')
      expect(fetcher).not.toHaveBeenCalled()
      vi.advanceTimersByTime(300)
      expect(fetcher).toHaveBeenCalledTimes(1)
    })
    scope.stop()
  })

  it('分页边界 hasNext/hasPrev', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const q = usePagedQuery<number>({
        pageSize: 10,
        fetcher: async () => ({ items: [], total: 25 }),
      })
      await q.load()
      expect(q.hasPrev.value).toBe(false)
      expect(q.hasNext.value).toBe(true)
      q.nextPage()
      await nextTick()
      expect(q.offset.value).toBe(10)
      expect(q.hasPrev.value).toBe(true)
    })
    scope.stop()
  })

  it('patchItem/removeItem 就地改', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const q = usePagedQuery<{ id: number }>({ fetcher: async () => ({ items: [{ id: 1 }, { id: 2 }], total: 2 }) })
      await q.load()
      q.patchItem((x) => x.id === 1, { id: 99 })
      expect(q.items.value[0]).toEqual({ id: 99 })
      q.removeItem((x) => x.id === 2)
      expect(q.items.value).toHaveLength(1)
    })
    scope.stop()
  })
})
