import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useCatalogStore } from '../../stores/catalog'
import { loadCatalog } from '../../test/fixtures'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import RagWorkspaceView from './RagWorkspaceView.vue'

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a><slot /></a>' }
const mountOpts = { global: { stubs: { RouterLink } } }

function setupCatalog(): void {
  setActivePinia(createPinia())
  useCatalogStore().catalog = loadCatalog()
}

describe('RagWorkspaceView', () => {
  beforeEach(setupCatalog)
  afterEach(() => document.body.querySelectorAll('*').forEach((n) => n.remove()))

  it('着陆页渲染 文档库 / 检索台 / 文档入库 / GraphRAG 分区', () => {
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('文档库')
    expect(text).toContain('检索台')
    expect(text).toContain('文档入库')
    expect(text).toContain('GraphRAG')
  })

  it('检索台顶部展示 ready-degraded 降级横幅（HashEmbedding 非真实语义）', () => {
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('HashEmbedding')
    expect(text).toContain('非真实语义')
    // 生产语义提示
    expect(text).toContain('qdrant')
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
    // 聚焦模式不渲染工作台分区
    expect(wrapper.text()).not.toContain('检索台')
  })

  it('未知能力 id 优雅报错', () => {
    const wrapper = mount(RagWorkspaceView, {
      props: { moduleId: 'rag', capId: 'rag.nope' },
      ...mountOpts,
    })
    expect(wrapper.text()).toContain('能力不存在')
  })
})
