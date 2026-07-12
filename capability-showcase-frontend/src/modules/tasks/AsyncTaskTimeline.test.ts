import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import type { TrackedTask } from './types'
import AsyncTaskTimeline from './AsyncTaskTimeline.vue'

function task(partial: Partial<TrackedTask> & { taskId: string; status: string }): TrackedTask {
  return { updatedAt: '10:00:00', events: [], ...partial }
}

describe('AsyncTaskTimeline', () => {
  it('渲染状态过滤条与各分组计数', () => {
    const tasks = [
      task({ taskId: 'a', status: 'RUNNING' }),
      task({ taskId: 'b', status: 'SUCCEEDED' }),
      task({ taskId: 'c', status: 'SUCCEEDED' }),
      task({ taskId: 'd', status: 'FAILED' }),
    ]
    const wrapper = mount(AsyncTaskTimeline, { props: { tasks } })
    const text = wrapper.text()
    expect(text).toContain('全部')
    expect(text).toContain('运行中')
    expect(text).toContain('成功')
    expect(text).toContain('失败')
    expect(text).toContain('取消')
    // 4 个任务全部渲染
    expect(wrapper.findAll('.tl__item').length).toBe(4)
  })

  it('点击某状态过滤只保留该组任务，再次点击恢复全部', async () => {
    const tasks = [
      task({ taskId: 'a', status: 'RUNNING' }),
      task({ taskId: 'b', status: 'SUCCEEDED' }),
      task({ taskId: 'c', status: 'FAILED' }),
    ]
    const wrapper = mount(AsyncTaskTimeline, { props: { tasks } })
    const filters = wrapper.findAll('.tl__filter')
    const success = filters.find((f) => f.text().includes('成功'))!
    await success.trigger('click')
    expect(wrapper.findAll('.tl__item').length).toBe(1)
    await success.trigger('click')
    expect(wrapper.findAll('.tl__item').length).toBe(3)
  })

  it('重连与续订检查点指示：subscribes>1 显示重连，lastEventId 显示续订点', () => {
    const tasks = [
      task({ taskId: 'a', status: 'RUNNING', subscribes: 3, lastEventId: 'evt-42' }),
    ]
    const wrapper = mount(AsyncTaskTimeline, { props: { tasks } })
    const text = wrapper.text()
    expect(text).toContain('重连 ×2')
    expect(text).toContain('续订点 evt-42')
  })

  it('无任务时不显示过滤条', () => {
    const wrapper = mount(AsyncTaskTimeline, { props: { tasks: [] } })
    expect(wrapper.find('.tl__filters').exists()).toBe(false)
    expect(wrapper.text()).toContain('尚无被追踪的任务')
  })
})
