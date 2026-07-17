import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import SseConsole from './SseConsole.vue'
import { cleanup } from '../../test/interactionHarness'

describe('SseConsole', () => {
  afterEach(() => cleanup())

  it('流式 token、状态、note/error、trace 均真实呈现', () => {
    const wrapper = mount(SseConsole, {
      props: {
        tokens: 'hello world',
        events: [
          { event: 'message', data: 'hello' },
          { event: 'blocked', data: 'policy' },
        ],
        status: 'streaming' as const,
        note: '护栏提示',
        error: '流错误',
        elapsedMs: 12,
        traceId: '1234567890',
      },
    })
    expect(wrapper.get('.sse__transcript').text()).toContain('hello world')
    expect(wrapper.get('[role="status"]').text()).toContain('护栏提示')
    expect(wrapper.get('[role="alert"]').text()).toContain('流错误')
    expect(wrapper.text()).toContain('2 事件')
    expect(wrapper.text()).toContain('trace 12345678')
    wrapper.unmount()
  })

  it('搜索命中数和高亮来自 token，而非事件 data', async () => {
    const wrapper = mount(SseConsole, {
      props: {
        tokens: 'refund refund order',
        events: [{ event: 'message', data: 'refund' }],
        status: 'done' as const,
      },
    })
    await wrapper.get('input[type="search"]').setValue('refund')
    expect(wrapper.get('.sse__search-count').text()).toBe('2')
    expect(wrapper.findAll('.sse__hit')).toHaveLength(2)
    wrapper.unmount()
  })

  it('切到事件流后按顺序展示原始命名事件，空 token 时下载禁用', async () => {
    const wrapper = mount(SseConsole, {
      props: {
        tokens: '',
        events: [{ event: 'RUNNING', data: '{"n":1}' }],
        status: 'done' as const,
      },
    })
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.get('.sse__events').text()).toContain('RUNNING')
    expect(wrapper.get('button[title="下载转录 (.txt)"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
})
