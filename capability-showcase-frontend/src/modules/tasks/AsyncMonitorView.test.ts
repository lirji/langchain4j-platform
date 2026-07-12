import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { Capability } from '../../types/catalog'
import { useCatalogStore } from '../../stores/catalog'
import { loadCatalog } from '../../test/fixtures'
import CapabilityCard from '../../components/capability/CapabilityCard.vue'
import AsyncTaskTimeline from './AsyncTaskTimeline.vue'
import AsyncMonitorView from './AsyncMonitorView.vue'

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a><slot /></a>' }
const mountOpts = { global: { stubs: { RouterLink } } }

function setupCatalog(): void {
  setActivePinia(createPinia())
  useCatalogStore().catalog = loadCatalog()
}

function cardIds(wrapper: ReturnType<typeof mount>): string[] {
  return wrapper.findAllComponents(CapabilityCard).map((c) => (c.props('cap') as Capability).id)
}

describe('AsyncMonitorView', () => {
  beforeEach(setupCatalog)

  it('着陆页渲染模块头 + 任务能力 + 死信 + 常驻时间线', () => {
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('异步任务 Async Monitor') // ModuleHeader 标题
    expect(text).toContain('任务能力')
    expect(text).toContain('webhook 死信')
    expect(text).toContain('会话任务时间线')
  })

  it('死信从卡片提升为常驻区块：不在能力网格，且提供加载入口', () => {
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks' }, ...mountOpts })
    const ids = cardIds(wrapper)
    // 死信不再是能力卡
    expect(ids).not.toContain('async.deadletter')
    // 其余任务能力仍在网格
    expect(ids).toContain('async.create')
    expect(ids).toContain('async.stream')
    // 死信常驻区块入口
    const text = wrapper.text()
    expect(text).toContain('加载死信')
    expect(text).toContain('尚未加载死信')
  })

  it('常驻时间线组件始终挂载', () => {
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks' }, ...mountOpts })
    expect(wrapper.findComponent(AsyncTaskTimeline).exists()).toBe(true)
  })
})
