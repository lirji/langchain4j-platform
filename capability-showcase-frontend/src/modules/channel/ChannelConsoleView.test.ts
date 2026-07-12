import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { Capability } from '../../types/catalog'
import { useCatalogStore } from '../../stores/catalog'
import { loadCatalog } from '../../test/fixtures'
import CapabilityCard from '../../components/capability/CapabilityCard.vue'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import ChannelConsoleView from './ChannelConsoleView.vue'

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a><slot /></a>' }
const mountOpts = { global: { stubs: { RouterLink } } }

function setupCatalog(): void {
  setActivePinia(createPinia())
  useCatalogStore().catalog = loadCatalog()
}

function cardIds(wrapper: ReturnType<typeof mount>): string[] {
  return wrapper.findAllComponents(CapabilityCard).map((c) => (c.props('cap') as Capability).id)
}

function runnerIds(wrapper: ReturnType<typeof mount>): string[] {
  return wrapper.findAllComponents(CapabilityRunner).map((c) => (c.props('cap') as Capability).id)
}

describe('ChannelConsoleView', () => {
  beforeEach(setupCatalog)

  it('着陆页渲染 发现 / 出站 / 回调 / 入站 分区', () => {
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('能力发现')
    expect(text).toContain('出站投递')
    expect(text).toContain('回调桥')
    expect(text).toContain('入站事件')
  })

  it('出站区标注真实副作用 + 默认锁定', () => {
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('真实外部副作用')
    expect(text).toContain('默认锁定')
    // messages.send 状态为 display-only → StateBadge 已锁定
    expect(text).toContain('已锁定')
    expect(cardIds(wrapper)).toContain('channel.messages.send')
  })

  it('回调能力以内联运行器呈现（header 构建器 + 演示 header 注入）', () => {
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...mountOpts })
    // 回调桥用内联 CapabilityRunner（表单里的 header 字段即 header 构建器）
    expect(runnerIds(wrapper)).toContain('channel.callbacks.async-task')
    expect(runnerIds(wrapper)).toContain('channel.callbacks.workflow')
    const cb = useCatalogStore().capabilityById('channel.callbacks.async-task') as Capability
    expect(cb.params.some((p) => p.in === 'header')).toBe(true)
    // 视图明确说明 header 注入不覆盖 X-Api-Key
    expect(wrapper.text()).toContain('X-Api-Key')
    expect(wrapper.text()).toContain('header 构建器')
  })

  it('能力发现区默认未拉取，提供「发现渠道」入口', () => {
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('发现渠道')
    expect(text).toContain('尚未发现')
  })

  it('聚焦 channel.messages.send：运行器旁展示真实投递锁定提示', () => {
    const wrapper = mount(ChannelConsoleView, {
      props: { moduleId: 'channel', capId: 'channel.messages.send' },
      ...mountOpts,
    })
    expect(wrapper.findAllComponents(CapabilityRunner).length).toBe(1)
    expect(wrapper.text()).toContain('真实外部投递，默认锁定')
  })
})
