import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SseEventTimeline from './SseEventTimeline.vue'
import type { SseEvent } from '../../api/sse'

function ev(event: string, data: unknown): SseEvent {
  return { event, data: typeof data === 'string' ? data : JSON.stringify(data) }
}

describe('SseEventTimeline', () => {
  it('流式中无事件 → 等待任务事件占位，绝不出现「等待首个 token」（回归断言）', () => {
    const w = mount(SseEventTimeline, { props: { events: [], status: 'streaming' } })
    expect(w.text()).toContain('等待任务事件')
    expect(w.text()).not.toContain('等待首个 token')
  })

  it('待机无事件 → 空态提示', () => {
    const w = mount(SseEventTimeline, { props: { events: [], status: 'idle' } })
    expect(w.text()).toContain('尚无事件')
    expect(w.text()).not.toContain('等待首个 token')
  })

  it('按到达顺序渲染命名事件与格式化 data', () => {
    const events: SseEvent[] = [
      ev('PENDING', { taskId: 't1', status: 'PENDING' }),
      ev('RUNNING', { taskId: 't1', status: 'RUNNING' }),
      ev('SUCCEEDED', { taskId: 't1', status: 'SUCCEEDED', result: 42 }),
    ]
    const w = mount(SseEventTimeline, { props: { events, status: 'done' } })
    const text = w.text()
    expect(text).toContain('PENDING')
    expect(text).toContain('RUNNING')
    expect(text).toContain('SUCCEEDED')
    expect(text).toContain('t1')
  })

  it('非 JSON data 原样显示，不崩溃', () => {
    const w = mount(SseEventTimeline, {
      props: { events: [ev('RUNNING', 'not-json-payload')], status: 'streaming' },
    })
    expect(w.exists()).toBe(true)
    expect(w.text()).toContain('not-json-payload')
  })
})
