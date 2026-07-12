import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { Capability } from '../../types/catalog'
import { useCatalogStore } from '../../stores/catalog'
import { loadCatalog } from '../../test/fixtures'
import CapabilityCard from '../../components/capability/CapabilityCard.vue'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import InteropEvalView from './InteropEvalView.vue'

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a><slot /></a>' }
const mountOpts = { global: { stubs: { RouterLink } } }

function setupCatalog(): void {
  setActivePinia(createPinia())
  useCatalogStore().catalog = loadCatalog()
}

function cardIds(wrapper: ReturnType<typeof mount>): string[] {
  return wrapper.findAllComponents(CapabilityCard).map((c) => (c.props('cap') as Capability).id)
}

describe('InteropEvalView', () => {
  beforeEach(setupCatalog)

  it('着陆页渲染 MCP / Agent Card / 评测 分区', () => {
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('MCP 工具')
    expect(text).toContain('Agent Card / A2A')
    expect(text).toContain('评测 Eval')
  })

  it('评测分区含 422 门禁说明文案，门禁 / 端到端经卡片深链', () => {
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('422')
    expect(text).toContain('非网络错误')
    // 非检索评测能力以卡片深链
    expect(cardIds(wrapper)).toContain('eval.gate')
    expect(cardIds(wrapper)).toContain('eval.run')
    // MCP 已改为三步串联流程，不再是卡片
    expect(cardIds(wrapper)).not.toContain('interop.mcp.call')
  })

  it('MCP 三步串联：提供列出工具入口，检索评测提供指标可视化工具', () => {
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('① 列出工具')
    expect(text).toContain('尚未列出工具')
    // 检索评测自定义工具入口
    expect(text).toContain('运行检索评测')
  })

  it('聚焦 eval.gate：运行器旁展示 422 业务化说明', () => {
    const wrapper = mount(InteropEvalView, {
      props: { moduleId: 'interop-eval', capId: 'eval.gate' },
      ...mountOpts,
    })
    expect(wrapper.findAllComponents(CapabilityRunner).length).toBe(1)
    expect(wrapper.text()).toContain('422 = 检出回归')
  })
})
