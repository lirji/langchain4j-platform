import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { DOMWrapper } from '@vue/test-utils'
import type { Capability } from '../../types/catalog'
import { useCatalogStore } from '../../stores/catalog'
import { loadCatalog } from '../../test/fixtures'
import CapabilityCard from '../../components/capability/CapabilityCard.vue'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import AgentLabView from './AgentLabView.vue'
import SseConsole from '../../components/capability/SseConsole.vue'
import SseStageConsole from '../../components/capability/SseStageConsole.vue'
import SseEventTimeline from '../../components/capability/SseEventTimeline.vue'

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a><slot /></a>' }
const mountOpts = { global: { stubs: { RouterLink } } }

function setupCatalog(): void {
  setActivePinia(createPinia())
  useCatalogStore().catalog = loadCatalog()
}

type Wrapper = ReturnType<typeof mount>
function modeByLabel(wrapper: Wrapper, label: string): DOMWrapper<Element> | undefined {
  return wrapper.findAll('.ag__mode').find((b) => b.text().includes(label))
}
function cardIds(wrapper: Wrapper): string[] {
  return wrapper.findAllComponents(CapabilityCard).map((c) => (c.props('cap') as Capability).id)
}

describe('AgentLabView', () => {
  beforeEach(setupCatalog)
  afterEach(() => document.body.querySelectorAll('*').forEach((n) => n.remove()))

  it('着陆页渲染目标 composer + 分组模式选择器 + 更多能力', () => {
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...mountOpts })
    const text = wrapper.text()
    // 分组标签
    expect(text).toContain('单 Agent')
    expect(text).toContain('DAG 编排')
    expect(text).toContain('轻量编排')
    expect(text).toContain('智能体')
    // 更多能力分区
    expect(text).toContain('更多能力：任务管理')
  })

  it('模式选择器覆盖核心执行模式，flag 标注以模式按钮 is-off 为准（当前目录全 ready）', () => {
    // issue-14 修正：不再用整页文本查「未启用」（会误命中 ModuleHeader 状态图例的固定文案），
    // 改为作用域断言：目录当前 agent 能力全 ready → 不存在带 is-off 的模式按钮。
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...mountOpts })
    expect(modeByLabel(wrapper, '同步 ReAct')).toBeTruthy()
    expect(modeByLabel(wrapper, 'DAG')).toBeTruthy()
    expect(modeByLabel(wrapper, '反思流式')).toBeTruthy()
    expect(modeByLabel(wrapper, '数据分析')).toBeTruthy()
    expect(wrapper.find('.ag__mode.is-off').exists()).toBe(false)
  })

  it('业务流程模式当前 ready：可选中且端点提示切到 /agent/process/run（不被闸门锁定）', async () => {
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...mountOpts })
    const process = modeByLabel(wrapper, '业务流程')
    expect(process).toBeTruthy()
    expect(process!.classes()).not.toContain('is-off')
    await process!.trigger('click')
    expect(process!.classes()).toContain('active')
    expect(wrapper.get('.ag__endpoint').text()).toContain('/agent/process/run')
  })

  it('更多能力网格深链任务管理能力', () => {
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...mountOpts })
    const ids = cardIds(wrapper)
    expect(ids).toContain('agent.tasks.list')
    expect(ids).toContain('agent.tasks.stream')
    expect(ids).toContain('agent.tasks.cancel')
  })

  it('深链 agent.run 时聚焦单个运行器', () => {
    const wrapper = mount(AgentLabView, {
      props: { moduleId: 'agent', capId: 'agent.run' },
      ...mountOpts,
    })
    expect(wrapper.findAllComponents(CapabilityRunner).length).toBe(1)
    // 聚焦模式不渲染工作台着陆
    expect(wrapper.text()).not.toContain('更多能力：任务管理')
  })

  it('未知能力 id 优雅报错', () => {
    const wrapper = mount(AgentLabView, {
      props: { moduleId: 'agent', capId: 'agent.nope' },
      ...mountOpts,
    })
    expect(wrapper.text()).toContain('能力不存在')
  })

  it('反思流式（含 answer+critique）挂 SseStageConsole，而非拼接视图或通用时间线', async () => {
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...mountOpts })
    await modeByLabel(wrapper, '反思流式')!.trigger('click')
    expect(wrapper.findComponent(SseStageConsole).exists()).toBe(true)
    expect(wrapper.findComponent(SseConsole).exists()).toBe(false)
    expect(wrapper.findComponent(SseEventTimeline).exists()).toBe(false)
  })

  it('任务状态流（agent.tasks.stream，命名事件流）深链挂通用 SseEventTimeline，不卡拼接视图', () => {
    const wrapper = mount(AgentLabView, {
      props: { moduleId: 'agent', capId: 'agent.tasks.stream' },
      ...mountOpts,
    })
    expect(wrapper.findComponent(SseEventTimeline).exists()).toBe(true)
    expect(wrapper.findComponent(SseConsole).exists()).toBe(false)
    expect(wrapper.findComponent(SseStageConsole).exists()).toBe(false)
  })
})
