import { effectScope } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useAbortable } from './useAbortable'

describe('useAbortable', () => {
  it('fresh 中止上一 controller，abort 清引用', () => {
    const scope = effectScope()
    const state = scope.run(useAbortable)!
    const first = state.fresh()
    const spy = vi.spyOn(first, 'abort')
    const second = state.fresh()
    expect(spy).toHaveBeenCalledOnce()
    expect(first.signal.aborted).toBe(true)
    expect(state.controller.value).toBe(second)
    state.abort()
    expect(second.signal.aborted).toBe(true)
    expect(state.controller.value).toBeNull()
    scope.stop()
  })

  it('scope dispose 自动中止当前 controller', () => {
    const scope = effectScope()
    const state = scope.run(useAbortable)!
    const current = state.fresh()
    scope.stop()
    expect(current.signal.aborted).toBe(true)
  })
})
