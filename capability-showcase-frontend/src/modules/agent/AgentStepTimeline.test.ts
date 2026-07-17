import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import AgentStepTimeline from './AgentStepTimeline.vue'
import { cleanup } from '../../test/interactionHarness'

describe('AgentStepTimeline', () => {
  afterEach(() => cleanup())

  it('string、known aliases 与 primitive 按原顺序编号', () => {
    const wrapper = mount(AgentStepTimeline, {
      props: {
        steps: [
          'start',
          { name: 'tool', reasoning: 'think', toolName: 'lookup', arguments: { id: 1 }, output: 'ok' },
          null,
        ],
      },
    })
    expect(wrapper.findAll('.st__node').map((n) => n.text())).toEqual(['1', '2', '3'])
    expect(wrapper.text()).toContain('start')
    expect(wrapper.text()).toContain('think')
    expect(wrapper.text()).toContain('lookup')
    expect(wrapper.text()).toContain('{"id":1}')
    wrapper.unmount()
  })

  it('未知对象不臆造字段，交给 JsonView 原样展示', () => {
    const wrapper = mount(AgentStepTimeline, { props: { steps: [{ custom: { nested: true } }] } })
    expect(wrapper.find('.st__json').exists()).toBe(true)
    expect(wrapper.text()).toContain('custom')
    expect(wrapper.text()).toContain('nested')
    wrapper.unmount()
  })

  it('同语义多个 alias 只取优先级最高的第一个', () => {
    const wrapper = mount(AgentStepTimeline, { props: { steps: [{ thought: 'first', reasoning: 'second' }] } })
    expect(wrapper.text()).toContain('first')
    expect(wrapper.text()).not.toContain('second')
    wrapper.unmount()
  })
})
