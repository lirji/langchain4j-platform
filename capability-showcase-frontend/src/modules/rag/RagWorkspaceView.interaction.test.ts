import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RagWorkspaceView from './RagWorkspaceView.vue'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import {
  buttonByText,
  cleanup,
  deferred,
  jsonResponse,
  RouterLinkStub,
  setupCatalog,
} from '../../test/interactionHarness'

/**
 * RAG 工作台交互测试：检索排序/高亮/visibility、文档分页/详情/删除/编码、乱序守卫、上传回流。
 * 疑似 bug（issue-08/12/13/15）以 skip+期望行为呈现。
 */

const doc = (id: string, name = id) => ({
  docId: id,
  tenantId: 'acme',
  displayName: name,
  contentType: 'text/plain',
  sizeBytes: 1536,
  segmentCount: 2,
  version: 1,
  uploadedAt: '2026-07-17T00:00:00Z',
  category: 'policy',
})
const config = (publicEnabled = false) => ({
  contractVersion: 2,
  publicEnabled,
  sharedImagesSupported: false,
  rag: {
    embeddingProvider: 'ollama',
    embeddingModel: 'nomic',
    semantic: true,
    vectorStoreProvider: 'qdrant',
    esHybridEnabled: true,
    fusionStrategy: 'rrf',
    graphEnabled: true,
    keywordHybridEnabled: true,
    multimodalEnabled: false,
  },
})
const opts = { global: { stubs: { RouterLink: RouterLinkStub } } }

async function settle(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  await flushPromises()
}

describe('RagWorkspaceView interaction', () => {
  beforeEach(() => setupCatalog())
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    cleanup()
  })

  it('检索 trim 参数、按 score 排序、展示服务端 visibility 与命中高亮', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === '/rag/config') return Promise.resolve(jsonResponse(config()))
      if (url === '/rag/query')
        return Promise.resolve(jsonResponse({
          hits: [
            { docId: 'low', score: 0.2, text: '退款 low', visibility: 'tenant' },
            { docId: 'high', score: 0.9, text: '退款 high', visibility: 'public' },
          ],
        }))
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts })
    await settle()
    await wrapper.get('[aria-label="检索查询"]').setValue(' 退款 ')
    const nums = wrapper.findAll('.rag__params input[type="number"]')
    await nums[0].setValue('7')
    await nums[1].setValue('0.3')
    await wrapper.get('.rag__params input[type="text"]').setValue(' policy ')
    await buttonByText(wrapper, '检索').trigger('click')
    await settle()
    const call = fetchMock.mock.calls.find(([url]) => url === '/rag/query')! as [string, RequestInit]
    expect(JSON.parse(String(call[1].body))).toEqual({ query: '退款', topK: 7, minScore: 0.3, category: 'policy' })
    expect(wrapper.findAll('.rag__id').map((n) => n.text()).slice(-2)).toEqual(['high', 'low'])
    expect(wrapper.findAll('.rag__vis').some((n) => n.text() === '共享')).toBe(true)
    expect(wrapper.findAll('.rag__mark').map((n) => n.text())).toContain('退款')
    wrapper.unmount()
  })

  it('检索空数组、不可解析响应、HTTP 错误走三个不同 UI 分支', async () => {
    const responses = [
      jsonResponse({ hits: [] }),
      jsonResponse({ answer: 'raw-only' }),
      jsonResponse({ message: 'bad query' }, 400),
    ]
    let index = 0
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) =>
      Promise.resolve(url === '/rag/config' ? jsonResponse(config()) : responses[index++]),
    ))
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts })
    await settle()
    for (const expected of ['无命中', 'raw-only', 'bad query']) {
      await wrapper.get('[aria-label="检索查询"]').setValue(`q${index}`)
      await buttonByText(wrapper, '检索').trigger('click')
      await settle()
      expect(wrapper.text()).toContain(expected)
    }
    wrapper.unmount()
  })

  it('文档 list→分页→详情→删除：URL/状态串联正确', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url === '/rag/config') return Promise.resolve(jsonResponse(config()))
      if (url === '/rag/documents?page=1&size=10')
        return Promise.resolve(jsonResponse({ items: [doc('a/b ?中#', 'Policy')], page: 1, size: 10, total: 11, totalPages: 2 }))
      if (url === '/rag/documents?page=2&size=10')
        return Promise.resolve(jsonResponse({ items: [doc('p2')], page: 2, size: 10, total: 11, totalPages: 2 }))
      if (url.startsWith('/rag/documents/a%2Fb%20%3F%E4%B8%AD%23')) {
        return Promise.resolve(
          init?.method === 'DELETE' ? jsonResponse({ deleted: true }) : jsonResponse(doc('a/b ?中#')),
        )
      }
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts })
    await settle()
    await buttonByText(wrapper, '刷新文档').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('Policy')
    expect(wrapper.text()).toContain('第 1 / 2 页')
    await buttonByText(wrapper, '详情').trigger('click')
    await settle()
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('a%2Fb%20%3F%E4%B8%AD%23'))).toBe(true)
    expect(wrapper.text()).toContain('contentType')
    await buttonByText(wrapper, '删除').trigger('click')
    await buttonByText(wrapper, '确认删除').trigger('click')
    await settle()
    // 精确断言：DELETE 打到完整编码后的文档 URL（租户库无 visibility query）。
    expect(fetchMock.mock.calls.some(
      ([url, init]) =>
        url === '/rag/documents/a%2Fb%20%3F%E4%B8%AD%23' && (init as RequestInit)?.method === 'DELETE',
    )).toBe(true)
    expect(wrapper.find('.rag__detail').exists()).toBe(false)
    // 真实翻页：点击「下一页」→ 发 page=2 请求并渲染第二页内容。
    await buttonByText(wrapper, '下一页').trigger('click')
    await settle()
    expect(fetchMock.mock.calls.some(([url]) => url === '/rag/documents?page=2&size=10')).toBe(true)
    expect(wrapper.text()).toContain('第 2 / 2 页')
    expect(wrapper.text()).toContain('p2')
    wrapper.unmount()
  })

  it('共享 tab 双控后请求 visibility=public，tenant 慢响应不得覆盖 public 新结果', async () => {
    const tenant = deferred<Response>()
    const pub = deferred<Response>()
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === '/rag/config') return Promise.resolve(jsonResponse(config(true)))
      if (url === '/rag/documents?page=1&size=10') return tenant.promise
      if (url === '/rag/documents?visibility=public&page=1&size=10') return pub.promise
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts })
    await settle()
    await buttonByText(wrapper, '刷新文档').trigger('click')
    await buttonByText(wrapper, '共享知识库').trigger('click')
    pub.resolve(jsonResponse({ items: [doc('public-new')], page: 1, size: 10, total: 1, totalPages: 1 }))
    await settle()
    tenant.resolve(jsonResponse({ items: [doc('tenant-old')], page: 1, size: 10, total: 1, totalPages: 1 }))
    await settle()
    expect(wrapper.text()).toContain('public-new')
    expect(wrapper.text()).not.toContain('tenant-old')
    expect(wrapper.get('.rag__tab.active').text()).toContain('共享')
    wrapper.unmount()
  })

  it('两个详情请求乱序时保留最后点击的文档', async () => {
    const first = deferred<Response>()
    const second = deferred<Response>()
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === '/rag/config') return Promise.resolve(jsonResponse(config()))
      if (url.includes('?page='))
        return Promise.resolve(jsonResponse({ items: [doc('a'), doc('b')], page: 1, size: 10, total: 2, totalPages: 1 }))
      if (url === '/rag/documents/a') return first.promise
      if (url === '/rag/documents/b') return second.promise
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts })
    await settle()
    await buttonByText(wrapper, '刷新文档').trigger('click')
    await settle()
    const details = wrapper.findAll('button').filter((b) => b.text() === '详情')
    await details[0].trigger('click')
    await details[1].trigger('click')
    second.resolve(jsonResponse({ docId: 'b', marker: 'NEW' }))
    await settle()
    first.resolve(jsonResponse({ docId: 'a', marker: 'OLD' }))
    await settle()
    expect(wrapper.get('.rag__detail').text()).toContain('NEW')
    expect(wrapper.get('.rag__detail').text()).not.toContain('OLD')
    wrapper.unmount()
  })

  it('上传 Runner success 回调将文档页归 1 并刷新', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) =>
      Promise.resolve(
        url === '/rag/config'
          ? jsonResponse(config())
          : jsonResponse({ items: [], page: 1, size: 10, total: 0, totalPages: 1 }),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts })
    await settle()
    const upload = wrapper
      .findAllComponents(CapabilityRunner)
      .find((r) => (r.props('cap') as { id: string }).id === 'rag.upload.json')!
    upload.vm.$emit('result', { cap: upload.props('cap'), data: { docId: 'new' }, status: 200 })
    await settle()
    expect(fetchMock.mock.calls.some(([url]) => url === '/rag/documents?page=1&size=10')).toBe(true)
    wrapper.unmount()
  })

  it('issue-08 回归：topK/minScore 越界时不发请求并显示字段错误', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(config()))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts })
    await settle()
    await wrapper.get('[aria-label="检索查询"]').setValue('q')
    const nums = wrapper.findAll('.rag__params input[type="number"]')
    await nums[0].setValue('0')
    await nums[1].setValue('2')
    await buttonByText(wrapper, '检索').trigger('click')
    expect(fetchMock.mock.calls.filter(([url]) => url === '/rag/query')).toHaveLength(0)
    expect(wrapper.text()).toContain('TopK 不能小于 1')
    expect(wrapper.text()).toContain('最低分需在 0..1 之间')
    // 上界与负值同样禁发
    await nums[0].setValue('51')
    await nums[1].setValue('-0.1')
    await buttonByText(wrapper, '检索').trigger('click')
    expect(fetchMock.mock.calls.filter(([url]) => url === '/rag/query')).toHaveLength(0)
    // 合法值恢复可检索
    await nums[0].setValue('5')
    await nums[1].setValue('0.5')
    fetchMock.mockResolvedValueOnce(jsonResponse({ hits: [] }))
    await buttonByText(wrapper, '检索').trigger('click')
    await settle()
    expect(fetchMock.mock.calls.filter(([url]) => url === '/rag/query')).toHaveLength(1)
    wrapper.unmount()
  })

  it('issue-12 回归：结果高亮绑定 submittedQuery，而非请求期间编辑后的 query', async () => {
    const result = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) =>
      url === '/rag/config' ? Promise.resolve(jsonResponse(config())) : result.promise,
    ))
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts })
    await settle()
    await wrapper.get('[aria-label="检索查询"]').setValue('退款')
    await buttonByText(wrapper, '检索').trigger('click')
    await wrapper.get('[aria-label="检索查询"]').setValue('订单')
    result.resolve(jsonResponse({ hits: [{ text: '退款政策', score: 1 }] }))
    await settle()
    expect(wrapper.get('.rag__mark').text()).toBe('退款')
    wrapper.unmount()
  })

  it('issue-13 回归：当前 graph ready 时不得硬编码显示「未启用」', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(config())))
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts })
    await settle()
    const graphSection = wrapper.findAll('section').find((s) => s.text().includes('GraphRAG'))!
    expect(graphSection.text()).not.toContain('未启用')
    wrapper.unmount()
  })

  it('issue-15 回归：卸载时中止专用检索 fetch，旧响应不得继续占连接', async () => {
    let searchSignal: AbortSignal | undefined
    const never = new Promise<Response>(() => {})
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url === '/rag/config') return Promise.resolve(jsonResponse(config()))
      searchSignal = init?.signal ?? undefined
      return never
    }))
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts })
    await settle()
    await wrapper.get('[aria-label="检索查询"]').setValue('q')
    await buttonByText(wrapper, '检索').trigger('click')
    wrapper.unmount()
    expect(searchSignal?.aborted).toBe(true)
  })
})
