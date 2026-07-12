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

  it('模式选择器覆盖核心执行模式（含 flag-off 诚实标注）', () => {
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...mountOpts })
    expect(modeByLabel(wrapper, '同步 ReAct')).toBeTruthy()
    expect(modeByLabel(wrapper, 'DAG')).toBeTruthy()
    expect(modeByLabel(wrapper, '反思流式')).toBeTruthy()
    expect(modeByLabel(wrapper, '数据分析')).toBeTruthy()
    // flag-off 业务流程模式诚实标注「未启用」
    expect(wrapper.text()).toContain('未启用')
  })

  it('切到 flag-off 模式（业务流程）：composer 经闸门诚实锁定并暴露 feature flag', async () => {
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...mountOpts })
    const process = modeByLabel(wrapper, '业务流程')
    expect(process).toBeTruthy()
    await process!.trigger('click')
    expect(wrapper.text()).toContain('app.agent.workflow.enabled')
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
})
