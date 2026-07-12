import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { Capability } from '../../types/catalog'
import { useCatalogStore } from '../../stores/catalog'
import { loadCatalog } from '../../test/fixtures'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import MultimodalConsoleView from './MultimodalConsoleView.vue'

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a><slot /></a>' }
const mountOpts = { global: { stubs: { RouterLink } } }

function setupCatalog(): void {
  setActivePinia(createPinia())
  useCatalogStore().catalog = loadCatalog()
}

function runnerIds(wrapper: ReturnType<typeof mount>): string[] {
  return wrapper.findAllComponents(CapabilityRunner).map((c) => (c.props('cap') as Capability).id)
}

describe('MultimodalConsoleView', () => {
  beforeEach(setupCatalog)

  it('着陆页渲染 图像 / 语音 分区与 flag 提示', () => {
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('图像')
    expect(text).toContain('语音')
    // flag-off 顶部提示
    expect(text).toContain('app.vision.enabled')
    expect(text).toContain('app.voice.enabled')
  })

  it('选择器提供图像 / 语音各能力，默认挂载首个的运行器', () => {
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...mountOpts })
    // 选择器 chips 覆盖图像与语音的关键能力
    expect(wrapper.find('[data-cap="vision.caption.file"]').exists()).toBe(true)
    expect(wrapper.find('[data-cap="voice.chat.stream"]').exists()).toBe(true)
    // 默认各挂一个运行器（图像 vision.caption.file + 语音 voice.transcribe）
    const ids = runnerIds(wrapper)
    expect(ids).toContain('vision.caption.file')
    expect(ids).toContain('voice.transcribe')
  })

  it('点击语音流式能力 chip 后切换到 voice.chat.stream 运行器', async () => {
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...mountOpts })
    await wrapper.find('[data-cap="voice.chat.stream"]').trigger('click')
    expect(runnerIds(wrapper)).toContain('voice.chat.stream')
  })

  it('聚焦 voice.chat.stream：识别为流式（multipart-sse）', () => {
    const wrapper = mount(MultimodalConsoleView, {
      props: { moduleId: 'multimodal', capId: 'voice.chat.stream' },
      ...mountOpts,
    })
    expect(wrapper.findAllComponents(CapabilityRunner).length).toBe(1)
    const text = wrapper.text()
    expect(text).toContain('multipart-sse')
    // 通用运行器把 multipart-sse 识别为流式 → 按钮为「开始流式」
    expect(text).toContain('开始流式')
  })
})
