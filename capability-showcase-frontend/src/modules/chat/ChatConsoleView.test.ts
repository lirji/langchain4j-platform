import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { DOMWrapper } from '@vue/test-utils'
import { useCatalogStore } from '../../stores/catalog'
import { loadCatalog } from '../../test/fixtures'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import ChatConsoleView from './ChatConsoleView.vue'

function setupCatalog(): void {
  setActivePinia(createPinia())
  useCatalogStore().catalog = loadCatalog()
}

type Wrapper = ReturnType<typeof mount>
function modeButtons(wrapper: Wrapper): DOMWrapper<Element>[] {
  return wrapper.findAll('.chat__mode')
}
function modeByLabel(wrapper: Wrapper, label: string): DOMWrapper<Element> | undefined {
  return modeButtons(wrapper).find((b) => b.text().includes(label))
}

describe('ChatConsoleView', () => {
  beforeEach(setupCatalog)
  afterEach(() => document.body.querySelectorAll('*').forEach((n) => n.remove()))

  it('统一模式选择器收入 chat.* 对话能力（含 flag-off 标注）', () => {
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    const text = wrapper.text()
    expect(text).toContain('同步')
    expect(text).toContain('流式')
    expect(text).toContain('意图路由')
    expect(text).toContain('MCP 工具')
    // flag-off 模式诚实标注「未启用」
    expect(text).toContain('未启用')
  })

  it('默认聚焦流式模式，提示行展示当前能力端点', () => {
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    expect(wrapper.text()).toContain('/chat/stream')
    expect(wrapper.text()).toContain('开始对话')
  })

  it('切到 flag-off 模式（MCP）：composer 经闸门诚实禁用并显示所需 feature flag', async () => {
    // chat.auto/cascade/memory 已默认启用（compose flag on），chat.mcp 仍需外部 MCP server → 保持 flag-off。
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    const mcp = modeByLabel(wrapper, 'MCP 工具')
    expect(mcp).toBeTruthy()
    await mcp!.trigger('click')
    // gate reason 暴露确切 feature flag 属性名
    expect(wrapper.text()).toContain('app.conversation.mcp.enabled')
  })

  it('长期记忆模式暴露画像面板入口', async () => {
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    const mem = modeByLabel(wrapper, '长期记忆')
    expect(mem).toBeTruthy()
    await mem!.trigger('click')
    expect(wrapper.text()).toContain('长期用户画像')
  })

  it('深链非会话能力（chat.extract）委派通用运行器', () => {
    const wrapper = mount(ChatConsoleView, {
      props: { moduleId: 'chat', capId: 'chat.extract' },
    })
    expect(wrapper.findAllComponents(CapabilityRunner).length).toBe(1)
    // 非会话深链不渲染对话流
    expect(wrapper.text()).not.toContain('开始对话')
  })

  it('深链对话模式（chat.sync）进入对话控制台并选中该模式', () => {
    const wrapper = mount(ChatConsoleView, {
      props: { moduleId: 'chat', capId: 'chat.sync' },
    })
    expect(wrapper.text()).toContain('开始对话')
    const active = wrapper.find('.chat__mode.active')
    expect(active.exists()).toBe(true)
    expect(active.text()).toContain('同步')
  })
})
