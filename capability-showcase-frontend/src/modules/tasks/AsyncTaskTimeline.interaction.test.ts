import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import AsyncTaskTimeline from './AsyncTaskTimeline.vue'
import type { TrackedTask } from './types'
import { cleanup } from '../../test/interactionHarness'

const task = (status: string): TrackedTask => ({
  taskId: `id-${status}`,
  status,
  updatedAt: '10:00:00',
  events: [],
})

describe('AsyncTaskTimeline actions/stages', () => {
  afterEach(() => cleanup())

  it.each(['PENDING', 'RUNNING', 'LEASED', 'RETRYING'])('%s 归运行中且未到终态', (status) => {
    const wrapper = mount(AsyncTaskTimeline, { props: { tasks: [task(status)] } })
    expect(wrapper.findAll('.tl__filter').find((b) => b.text().includes('运行中'))!.text()).toContain('1')
    expect(wrapper.findAll('.tl__node').at(-1)!.attributes('data-reached')).toBe('false')
    expect(wrapper.find('.tl__btn--danger').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it.each(['SUCCEEDED', 'FAILED', 'CANCELLED'])('%s 到终态并禁用 stream/cancel，refresh 仍可用', (status) => {
    const wrapper = mount(AsyncTaskTimeline, { props: { tasks: [task(status)] } })
    const buttons = wrapper.findAll('.tl__btn')
    expect(buttons[0].attributes('disabled')).toBeDefined()
    expect(buttons[1].attributes('disabled')).toBeUndefined()
    expect(buttons[2].attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('.tl__node').at(-1)!.text()).toBe(status)
    wrapper.unmount()
  })

  it('三个 action emit 精确 task id；全局 disabled 时均不 emit', async () => {
    const wrapper = mount(AsyncTaskTimeline, { props: { tasks: [task('RUNNING')] } })
    const buttons = wrapper.findAll('.tl__btn')
    await buttons[0].trigger('click')
    await buttons[1].trigger('click')
    await buttons[2].trigger('click')
    expect(wrapper.emitted('stream')).toEqual([['id-RUNNING']])
    expect(wrapper.emitted('refresh')).toEqual([['id-RUNNING']])
    expect(wrapper.emitted('cancel')).toEqual([['id-RUNNING']])
    await wrapper.setProps({ disabled: true })
    await buttons[0].trigger('click')
    await buttons[1].trigger('click')
    await buttons[2].trigger('click')
    expect(wrapper.emitted('stream')).toHaveLength(1)
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    wrapper.unmount()
  })
})
