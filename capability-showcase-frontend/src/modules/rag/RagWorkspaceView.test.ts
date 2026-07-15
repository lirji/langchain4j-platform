import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useCatalogStore } from '../../stores/catalog'
import { useSessionStore } from '../../stores/session'
import { loadCatalog } from '../../test/fixtures'
import { fetchRagConfig } from '../../api/knowledge'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import InfoNote from '../_shared/InfoNote.vue'
import WorkbenchSection from '../_shared/WorkbenchSection.vue'
import RagWorkspaceView from './RagWorkspaceView.vue'

// fetchRagConfig 走网关，测试里 mock 掉；其余 api/knowledge 导出保持真实。
vi.mock('../../api/knowledge', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/knowledge')>()),
  fetchRagConfig: vi.fn(),
}))

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a><slot /></a>' }
const mountOpts = { global: { stubs: { RouterLink } } }

function setupCatalog(): void {
  setActivePinia(createPinia())
  useCatalogStore().catalog = loadCatalog()
}

describe('RagWorkspaceView', () => {
  beforeEach(setupCatalog)
  afterEach(() => {
    vi.mocked(fetchRagConfig).mockReset()
    document.body.querySelectorAll('*').forEach((n) => n.remove())
  })

  it('着陆页渲染 文档库 / 检索台 / 文档入库 / GraphRAG 分区', () => {
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('文档库')
    expect(text).toContain('检索台')
    expect(text).toContain('文档入库')
    expect(text).toContain('GraphRAG')
  })

  it('未探测到运行时（无凭证）时诚实回退到 HashEmbedding 降级横幅', () => {
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('HashEmbedding')
    expect(text).toContain('非真实语义')
    // 生产语义提示
    expect(text).toContain('qdrant')
  })

  it('语义就绪时横幅动态展示真实 provider/模型 + 混排开关', async () => {
    vi.mocked(fetchRagConfig).mockResolvedValue({
      contractVersion: 2,
      publicEnabled: false,
      sharedImagesSupported: false,
      rag: {
        embeddingProvider: 'ollama',
        embeddingModel: 'nomic-embed-text',
        semantic: true,
        vectorStoreProvider: 'qdrant',
        esHybridEnabled: true,
        fusionStrategy: 'rrf',
        graphEnabled: true,
        keywordHybridEnabled: true,
        multimodalEnabled: false,
      },
    })
    useSessionStore().setApiKey('dev-key-acme') // 置可执行凭证 → 触发 probeRagConfig
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...mountOpts })
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('语义就绪')
    expect(text).toContain('nomic-embed-text')
    expect(text).toContain('rrf')
    // 作用域到 InfoNote 横幅（整页文本含 header/状态 chip 的降级字样，会误伤）：
    // 语义就绪分支渲染 success 横幅，且不再渲染 HashEmbedding 降级横幅。
    const banners = wrapper.findAllComponents(InfoNote).map((n) => n.text())
    expect(banners.some((t) => t.includes('语义就绪'))).toBe(true)
    expect(banners.some((t) => t.includes('HashEmbedding'))).toBe(false)
  })

  it('文档入库诚实提示需 ingest scope；GraphRAG 诚实锁定 feature flag', () => {
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('ingest')
    expect(text).toContain('app.rag.graph.enabled')
  })

  it('深链 rag.query 时聚焦单个运行器', () => {
    const wrapper = mount(RagWorkspaceView, {
      props: { moduleId: 'rag', capId: 'rag.query' },
      ...mountOpts,
    })
    expect(wrapper.findAllComponents(CapabilityRunner).length).toBe(1)
    // 聚焦模式不渲染任何工作台分区（断言组件本身，避免"检索台"字样出现在文案里造成误伤）
    expect(wrapper.findAllComponents(WorkbenchSection).length).toBe(0)
  })

  it('未知能力 id 优雅报错', () => {
    const wrapper = mount(RagWorkspaceView, {
      props: { moduleId: 'rag', capId: 'rag.nope' },
      ...mountOpts,
    })
    expect(wrapper.text()).toContain('能力不存在')
  })
})
