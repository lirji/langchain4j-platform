import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { Capability } from '../../types/catalog'
import { useCatalogStore } from '../../stores/catalog'
import { loadCatalog } from '../../test/fixtures'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import WorkflowDeskView from './WorkflowDeskView.vue'

function setupCatalog(): void {
  setActivePinia(createPinia())
  useCatalogStore().catalog = loadCatalog()
}

describe('WorkflowDeskView', () => {
  beforeEach(setupCatalog)
  afterEach(() => document.body.querySelectorAll('*').forEach((n) => n.remove()))

  it('着陆页渲染 发起 / 待办 / 危险区 分区', () => {
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    const text = wrapper.text()
    expect(text).toContain('① 发起退款流程')
    expect(text).toContain('② 待办清单')
    expect(text).toContain('④ 危险区')
  })

  it('② 待办清单渲染主从两栏（未拉取占位 + 未选择详情占位）', () => {
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    const text = wrapper.text()
    // 主：尚未拉取待办占位；从：未选择任务占位。主从结构均须渲染。
    expect(text).toContain('尚未拉取待办')
    expect(text).toContain('选择一个待办任务')
  })

  it('危险区（purge）默认锁定：显示破坏性提示且运行器不可执行', () => {
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    const text = wrapper.text()
    // 视图侧危险提示 + StateBadge 已锁定 + 运行器 gate 锁定原因
    expect(text).toContain('破坏性操作，默认锁定')
    expect(text).toContain('已锁定')
    expect(text).toContain('危险能力已默认锁定')
  })

  it('发起成功后把 StartResult 记入本会话列表（串联，不发网络）', async () => {
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    const runners = wrapper.findAllComponents(CapabilityRunner)
    expect(runners.length).toBeGreaterThan(0)
    const startCap = useCatalogStore().capabilityById('workflow.refund.start') as Capability
    // 模拟通用运行器成功回调，验证视图串联逻辑
    runners[0].vm.$emit('result', {
      cap: startCap,
      data: { instanceId: 'wf-1', taskId: 'task-9', status: 'PENDING_APPROVAL', deduplicated: false },
      status: 200,
    })
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('本会话发起的实例')
    expect(text).toContain('wf-1')
    expect(text).toContain('task-9')
  })

  it('deep-link 到具体能力时聚焦单个运行器', () => {
    const wrapper = mount(WorkflowDeskView, {
      props: { moduleId: 'workflow', capId: 'workflow.refund.start' },
    })
    expect(wrapper.findAllComponents(CapabilityRunner).length).toBe(1)
    // 聚焦模式不渲染工作台分区标题
    expect(wrapper.text()).not.toContain('② 待办清单')
  })

  it('未知能力 id 优雅报错', () => {
    const wrapper = mount(WorkflowDeskView, {
      props: { moduleId: 'workflow', capId: 'workflow.nope' },
    })
    expect(wrapper.text()).toContain('能力不存在')
  })
})
