import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SseStageConsole from './SseStageConsole.vue'
import type { SseEvent } from '../../api/sse'

function ev(event: string, data: unknown): SseEvent {
  return { event, data: typeof data === 'string' ? data : JSON.stringify(data) }
}

describe('SseStageConsole', () => {
  it('流式中且无内容 → 显示轮次占位，绝不出现「等待首个 token」（回归断言）', () => {
    const w = mount(SseStageConsole, { props: { events: [], status: 'streaming' } })
    expect(w.text()).toContain('正在进行第 1 轮推理')
    expect(w.text()).not.toContain('等待首个 token')
  })

  it('待机且无事件 → 空态提示', () => {
    const w = mount(SseStageConsole, { props: { events: [], status: 'idle' } })
    expect(w.text()).toContain('尚无输出')
    expect(w.text()).not.toContain('等待首个 token')
  })

  it('渲染每轮答案、评分与待改进项', () => {
    const events: SseEvent[] = [
      ev('attempt-start', { n: 1 }),
      ev('answer', { n: 1, answer: '这是答案' }),
      ev('critique', {
        n: 1,
        answer: '这是答案',
        aggregate: 0.8,
        correctness: 0.8,
        completeness: 0.8,
        clarity: 0.8,
        mainIssue: '待补充示例',
      }),
    ]
    const w = mount(SseStageConsole, { props: { events, status: 'streaming' } })
    expect(w.text()).toContain('这是答案')
    expect(w.text()).toContain('第 1 轮')
    expect(w.text()).toContain('80%')
    expect(w.text()).toContain('待补充示例')
  })

  it('done 后显示最终答案与收敛徽章', () => {
    const events: SseEvent[] = [
      ev('answer', { n: 1, answer: 'A' }),
      ev('done', {
        question: 'q',
        finalAnswer: '最终答案文本',
        attempts: [],
        acceptedByThreshold: true,
        tenantId: 't',
      }),
    ]
    const w = mount(SseStageConsole, { props: { events, status: 'done' } })
    expect(w.text()).toContain('最终答案')
    expect(w.text()).toContain('最终答案文本')
    expect(w.text()).toContain('已收敛')
  })

  it('事件数据损坏也不崩溃', () => {
    const events: SseEvent[] = [ev('attempt-start', { n: 1 }), { event: 'answer', data: '{坏掉的' }]
    const w = mount(SseStageConsole, { props: { events, status: 'streaming' } })
    expect(w.exists()).toBe(true)
    // 切到原始事件流 tab 仍可查看原始负载
    expect(w.text()).not.toContain('等待首个 token')
  })
})
