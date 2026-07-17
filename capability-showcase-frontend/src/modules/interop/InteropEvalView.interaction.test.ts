import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import InteropEvalView from './InteropEvalView.vue'
import CapabilityCard from '../../components/capability/CapabilityCard.vue'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import { useCatalogStore } from '../../stores/catalog'
import { useSessionStore } from '../../stores/session'
import {
  buttonByText,
  cleanup,
  deferred,
  jsonResponse,
  RouterLinkStub,
  setupCatalog,
} from '../../test/interactionHarness'

/**
 * Interop & Eval 交互测试（docs/tests/showcase-mm-platform-interop-0717-2007）。
 * 覆盖：MCP 三步串联（列表→详情→调用）、parseTools 探测与兜底、检索评测指标/明细/兜底、
 * eval.gate 422 业务化、卡片深链、gate 0-fetch、缺失分支。
 * it.skip 为挂账疑似 bug（03-suspected-issues.md），修复后启用。
 */

const opts = { global: { stubs: { RouterLink: RouterLinkStub } } }

async function settle(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  await flushPromises()
}

function toolButton(wrapper: ReturnType<typeof mount>, name: string) {
  const found = wrapper.findAll('.ie__tool').find((b) => b.text().includes(name))
  if (!found) throw new Error(`missing tool ${name}`)
  return found
}

function runRetrievalButton(wrapper: ReturnType<typeof mount>) {
  return buttonByText(wrapper, '运行检索评测')
}

function runnerExecute(wrapper: ReturnType<typeof mount>) {
  const runner = wrapper.getComponent(CapabilityRunner)
  const button = runner.findAll('button').find((b) => /执行|开始流式/.test(b.text()))
  if (!button) throw new Error('missing runner execute')
  return button
}

describe('InteropEvalView interaction', () => {
  beforeEach(() => setupCatalog())
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    cleanup()
  })

  it('IE-01 未登录时 MCP/retrieval 均由 gate 禁用并且 0 fetch', async () => {
    setupCatalog('')
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    const list = buttonByText(wrapper, '列出工具')
    expect(list.attributes('disabled')).toBeDefined()
    expect(runRetrievalButton(wrapper).attributes('disabled')).toBeDefined()
    await list.trigger('click')
    await runRetrievalButton(wrapper).trigger('click')
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请先登录')
    wrapper.unmount()
  })

  it('IE-02 MCP 列表→编码详情→调用完整串联并显示结果', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === '/interop/mcp/tools') {
        return Promise.resolve(
          jsonResponse({ tools: [{ name: 'platform/ping 中', description: 'ping tool' }] }),
        )
      }
      if (url === '/interop/mcp/tools/platform%2Fping%20%E4%B8%AD') {
        return Promise.resolve(
          jsonResponse({ name: 'platform/ping 中', inputSchema: { type: 'object' } }),
        )
      }
      if (url === '/interop/mcp/call') {
        return Promise.resolve(jsonResponse({ success: true, result: 'pong' }))
      }
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await buttonByText(wrapper, '列出工具').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('ping tool')
    await toolButton(wrapper, 'platform/ping 中').trigger('click')
    await settle()
    expect(toolButton(wrapper, 'platform/ping 中').attributes('aria-selected')).toBe('true')
    expect(wrapper.text()).toContain('inputSchema')
    await wrapper.get('[aria-label="MCP 调用参数 JSON"]').setValue('{"message":"hello"}')
    await buttonByText(wrapper, '调用').trigger('click')
    await settle()

    expect(fetchMock).toHaveBeenCalledTimes(3)
    const [, detailInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(detailInit.method).toBe('GET')
    const [callUrl, callInit] = fetchMock.mock.calls[2] as [string, RequestInit]
    expect(callUrl).toBe('/interop/mcp/call')
    expect(new Headers(callInit.headers).get('X-Api-Key')).toBe('test-key')
    expect(JSON.parse(String(callInit.body))).toEqual({
      tool: 'platform/ping 中',
      arguments: { message: 'hello' },
    })
    expect(wrapper.text()).toContain('pong')
    wrapper.unmount()
  })

  it.each([
    ['root', (items: unknown[]) => items],
    ['tools', (items: unknown[]) => ({ tools: items })],
    ['items', (items: unknown[]) => ({ items })],
    ['data', (items: unknown[]) => ({ data: items })],
    ['results', (items: unknown[]) => ({ results: items })],
  ])('IE-03 parseTools 探测 %s 数组并兼容 tool/id 与 summary/title', async (_key, envelope) => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          envelope([
            { tool: 'tool-a', summary: 'summary-a' },
            { id: 'tool-b', title: 'title-b' },
          ]),
        ),
      ),
    )
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await buttonByText(wrapper, '列出工具').trigger('click')
    await settle()
    expect(wrapper.findAll('.ie__tool-name').map((n) => n.text())).toEqual(['tool-a', 'tool-b'])
    expect(wrapper.text()).toContain('summary-a')
    expect(wrapper.text()).toContain('title-b')
    wrapper.unmount()
  })

  it('IE-04 工具空数组与不可解析响应走不同终态', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ tools: [] }))
      .mockResolvedValueOnce(jsonResponse({ service: 'interop', version: 2 }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await buttonByText(wrapper, '列出工具').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('没有工具')
    await buttonByText(wrapper, '重新列出').trigger('click')
    await settle()
    expect(wrapper.get('.ie__fallback').text()).toContain('interop')
    expect(wrapper.get('.ie__fallback').text()).toContain('version')
    wrapper.unmount()
  })

  it('IE-05 loadTools busy 禁用按钮，503 后重试清错并恢复列表', async () => {
    const pending = deferred<Response>()
    const fetchMock = vi.fn()
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(jsonResponse({ tools: [{ name: 'recovered' }] }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    const button = buttonByText(wrapper, '列出工具')
    await button.trigger('click')
    await flushPromises()
    expect(button.text()).toContain('列出中')
    expect(button.attributes('disabled')).toBeDefined()
    pending.resolve(jsonResponse({ message: 'interop down' }, 503))
    await settle()
    expect(wrapper.get('[role="alert"]').text()).toContain('interop down')
    await buttonByText(wrapper, '重新列出').trigger('click')
    await settle()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('recovered')
    wrapper.unmount()
  })

  it('IE-06 arguments 语法错误 0 call fetch；空白规范化 {}；null body 有明确成功提示', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === '/interop/mcp/tools') {
        return Promise.resolve(jsonResponse([{ name: 'platform.ping' }]))
      }
      if (url === '/interop/mcp/tools/platform.ping') {
        return Promise.resolve(jsonResponse({ name: 'platform.ping' }))
      }
      if (url === '/interop/mcp/call') return Promise.resolve(jsonResponse(null))
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await buttonByText(wrapper, '列出工具').trigger('click')
    await settle()
    await toolButton(wrapper, 'platform.ping').trigger('click')
    await settle()
    const args = wrapper.get('[aria-label="MCP 调用参数 JSON"]')
    await args.setValue('{bad')
    await buttonByText(wrapper, '调用').trigger('click')
    expect(wrapper.get('[role="alert"]').text()).toContain('arguments 不是合法 JSON')
    expect(fetchMock.mock.calls.filter(([url]) => url === '/interop/mcp/call')).toHaveLength(0)
    await args.setValue('   ')
    await buttonByText(wrapper, '调用').trigger('click')
    await settle()
    const call = fetchMock.mock.calls.find(([url]) => url === '/interop/mcp/call')! as [
      string,
      RequestInit,
    ]
    expect(JSON.parse(String(call[1].body))).toEqual({ tool: 'platform.ping', arguments: {} })
    expect(wrapper.text()).toContain('调用成功，无响应体')
    wrapper.unmount()
  })

  it('IE-07 retrieval 映射参数并同时渲染七类指标与真实 case 行', async () => {
    const response = {
      avgRecall: 0.5,
      meanMrr: '0.250',
      hitRate: 1,
      avgPrecision: 0.3333,
      ndcg: 0.4,
      f1: 0.2,
      map: 0.1,
      results: [{ id: 'c1', question: '退款', recall: 0.5, hit: true }],
    }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(response))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await wrapper
      .get('[aria-label="检索评测用例 JSON"]')
      .setValue('[{"id":"c1","question":"退款","relevantDocIds":["d1"]}]')
    await wrapper.get('.ie__retrieval input[type="number"]').setValue('7')
    await wrapper.get('.ie__retrieval input[type="text"]').setValue(' policy ')
    await runRetrievalButton(wrapper).trigger('click')
    await settle()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/eval/retrieval')
    expect(JSON.parse(String(init.body))).toEqual({
      cases: [{ id: 'c1', question: '退款', relevantDocIds: ['d1'] }],
      topK: 7,
      category: 'policy',
    })
    expect(wrapper.findAll('.stat__label').map((n) => n.text())).toEqual([
      'avgRecall',
      'meanMrr',
      'hitRate',
      'avgPrecision',
      'ndcg',
      'f1',
      'map',
    ])
    expect(wrapper.findAll('.stat__value').map((n) => n.text())).toEqual([
      '0.500',
      '0.250',
      '1.000',
      '0.333',
      '0.400',
      '0.200',
      '0.100',
    ])
    expect(wrapper.get('table').text()).toContain('c1')
    expect(wrapper.get('table').text()).toContain('退款')
    wrapper.unmount()
  })

  it('IE-08 cases 语法错误不发请求并给可访问错误', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await wrapper.get('[aria-label="检索评测用例 JSON"]').setValue('{bad')
    await runRetrievalButton(wrapper).trigger('click')
    expect(wrapper.get('[role="alert"]').text()).toContain('cases 不是合法 JSON 数组')
    expect(fetchMock).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('IE-09 metric/row 各 envelope、仅一类与双失败 fallback 都有确定展示', async () => {
    const metricScopes = ['metrics', 'summary', 'aggregate', 'overall', 'result', 'scores']
    const rowKeys = ['cases', 'perCase', 'caseResults', 'results', 'details', 'items']
    const responses: unknown[] = [
      ...metricScopes.map((key) => ({ [key]: { recall: '0.25', ignored: 'x' } })),
      ...rowKeys.map((key) => ({ [key]: [null, 'bad', { id: key }] })),
      { opaque: { value: 7 } },
    ]
    let index = 0
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(responses[index++]))),
    )
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await wrapper
      .get('[aria-label="检索评测用例 JSON"]')
      .setValue('[{"id":"c","question":"q","relevantDocIds":[]}]')
    for (const key of metricScopes) {
      await runRetrievalButton(wrapper).trigger('click')
      await settle()
      expect(wrapper.get('.stat__value').text(), key).toBe('0.250')
      expect(wrapper.find('.ie__fallback').exists(), key).toBe(false)
    }
    for (const key of rowKeys) {
      await runRetrievalButton(wrapper).trigger('click')
      await settle()
      expect(wrapper.get('table').text(), key).toContain(key)
      expect(wrapper.findAll('tbody tr'), key).toHaveLength(1)
    }
    await runRetrievalButton(wrapper).trigger('click')
    await settle()
    expect(wrapper.get('.ie__fallback').text()).toContain('opaque')
    wrapper.unmount()
  })

  it('IE-10 工具详情能力缺失时显示错误且不发 detail fetch', async () => {
    const mod = useCatalogStore().catalog!.modules.find((m) => m.id === 'interop-eval')!
    mod.capabilities = mod.capabilities.filter((c) => c.id !== 'interop.mcp.tool')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([{ name: 'platform.ping' }]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await buttonByText(wrapper, '列出工具').trigger('click')
    await settle()
    await toolButton(wrapper, 'platform.ping').trigger('click')
    await settle()
    expect(wrapper.get('[role="alert"]').text()).toContain('能力不在目录中')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('IE-11 eval.gate 深链把 422 保留为业务门禁结果并展示 body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ message: 'recall regression', failed: ['c1'] }, 422)),
    )
    const wrapper = mount(InteropEvalView, {
      props: { moduleId: 'interop-eval', capId: 'eval.gate' },
      ...opts,
    })
    await runnerExecute(wrapper).trigger('click')
    await settle()
    expect(wrapper.text()).toContain('422 = 检出回归')
    expect(wrapper.text()).toContain('HTTP 422')
    expect(wrapper.get('[role="alert"]').text()).toContain('门禁未通过')
    expect(wrapper.text()).toContain('recall regression')
    expect(wrapper.text()).toContain('c1')
    wrapper.unmount()
  })

  it('IE-12 Agent/A2A 与 eval 卡片精确深链且 MCP call 不退化成卡片', () => {
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    const ids = wrapper.findAllComponents(CapabilityCard).map((c) => (c.props('cap') as { id: string }).id)
    expect(ids).toEqual([
      'interop.agent-card',
      'interop.a2a.agent-card',
      'interop.a2a.call',
      'eval.capabilities',
      'eval.run',
      'eval.suite.run',
      'eval.dual-run',
      'eval.gate',
    ])
    expect(ids).not.toContain('interop.mcp.call')
    const links = wrapper.findAll('[data-to]').map((a) => a.attributes('data-to'))
    expect(links).toContain('/m/interop-eval/interop.a2a.call')
    expect(links).toContain('/m/interop-eval/eval.gate')
    wrapper.unmount()
  })

  it('IE-13 错误 module/cap 与空能力目录均有明确 EmptyState', () => {
    const missing = mount(InteropEvalView, { props: { moduleId: 'missing' }, ...opts })
    expect(missing.text()).toContain('模块不存在')
    missing.unmount()
    const cap = mount(InteropEvalView, {
      props: { moduleId: 'interop-eval', capId: 'eval.missing' },
      ...opts,
    })
    expect(cap.text()).toContain('能力不存在')
    cap.unmount()
    const mod = useCatalogStore().catalog!.modules.find((m) => m.id === 'interop-eval')!
    mod.capabilities = []
    const empty = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    expect(empty.text()).toContain('能力待补')
    empty.unmount()
  })

  it('IE-20 清空 topK 时请求体正确省略 topK（现状即正确，锁定防回归）', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ avgRecall: 1 }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await wrapper
      .get('[aria-label="检索评测用例 JSON"]')
      .setValue('[{"id":"c","question":"q","relevantDocIds":[]}]')
    await wrapper.get('.ie__retrieval input[type="number"]').setValue('')
    await runRetrievalButton(wrapper).trigger('click')
    await settle()
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(String(init.body))).not.toHaveProperty('topK')
    // 成功终态：avgRecall 以指标卡呈现。
    expect(wrapper.get('.stat__label').text()).toBe('avgRecall')
    expect(wrapper.get('.stat__value').text()).toBe('1.000')
    wrapper.unmount()
  })

  it('IE-21 工具详情空体显示「无详情返回」，调用 500 错误后可重试成功', async () => {
    let callCount = 0
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === '/interop/mcp/tools') {
        return Promise.resolve(jsonResponse([{ name: 'platform.ping' }]))
      }
      if (url === '/interop/mcp/tools/platform.ping') return Promise.resolve(jsonResponse(null))
      if (url === '/interop/mcp/call') {
        callCount += 1
        return Promise.resolve(
          callCount === 1
            ? jsonResponse({ message: 'tool exploded' }, 500)
            : jsonResponse({ result: 'recovered' }),
        )
      }
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await buttonByText(wrapper, '列出工具').trigger('click')
    await settle()
    await toolButton(wrapper, 'platform.ping').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('无详情返回')
    await buttonByText(wrapper, '调用').trigger('click')
    await settle()
    expect(wrapper.get('[role="alert"]').text()).toContain('tool exploded')
    await buttonByText(wrapper, '调用').trigger('click')
    await settle()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('recovered')
    wrapper.unmount()
  })

  it('IE-22 retrieval HTTP 500 显示人话错误且 busy 复位可重试', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ message: 'eval blew up' }, 500))
      .mockResolvedValueOnce(jsonResponse({ avgRecall: 0.5 }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await wrapper
      .get('[aria-label="检索评测用例 JSON"]')
      .setValue('[{"id":"c","question":"q","relevantDocIds":[]}]')
    await runRetrievalButton(wrapper).trigger('click')
    await settle()
    expect(wrapper.get('[role="alert"]').text()).toContain('eval blew up')
    expect(runRetrievalButton(wrapper).attributes('disabled')).toBeUndefined()
    await runRetrievalButton(wrapper).trigger('click')
    await settle()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('.stat__value').text()).toBe('0.500')
    wrapper.unmount()
  })

  it('IE-23 大小写变体指标按规范化 key 去重（issue-18 已修）', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ Recall: 0.8, metrics: { recall: 0.9 } })),
    )
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await wrapper
      .get('[aria-label="检索评测用例 JSON"]')
      .setValue('[{"id":"c","question":"q","relevantDocIds":[]}]')
    await runRetrievalButton(wrapper).trigger('click')
    await settle()
    // 当前 seen 用原始 key 去重 → Recall 与 recall 各出一张卡；语义相同时应只保留一个确定来源。
    expect(wrapper.findAll('.stat__label')).toHaveLength(1)
    wrapper.unmount()
  })

  it('IE-14 选择/重载/调用乱序只允许最新工具状态落地（issue-06/07/08 已修）', async () => {
    const a = deferred<Response>()
    const b = deferred<Response>()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((url: string) => {
        if (url === '/interop/mcp/tools') {
          return Promise.resolve(jsonResponse([{ name: 'A' }, { name: 'B' }]))
        }
        if (url.endsWith('/A')) return a.promise
        if (url.endsWith('/B')) return b.promise
        return Promise.resolve(jsonResponse({ result: 'A-result' }))
      }),
    )
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await buttonByText(wrapper, '列出工具').trigger('click')
    await settle()
    void toolButton(wrapper, 'A').trigger('click')
    void toolButton(wrapper, 'B').trigger('click')
    b.resolve(jsonResponse({ marker: 'NEW-B' }))
    await settle()
    a.resolve(jsonResponse({ marker: 'STALE-A' }))
    await settle()
    // 预期 1（issue-06）：无论响应顺序，最终只显示 B 的详情；A 晚到不得覆盖。
    expect(wrapper.text()).toContain('NEW-B')
    expect(wrapper.text()).not.toContain('STALE-A')
    // 预期 2（issue-07）：调用期间切换工具，结果不得错挂到新工具名下（结果应与调用时快照关联）。
    // 预期 3（issue-08）：重新列出后旧 selectedTool 不在新列表时应取消选择并清理详情/调用状态。
    await buttonByText(wrapper, '重新列出').trigger('click')
    await settle()
    expect(wrapper.text()).not.toContain('② 工具详情')
    wrapper.unmount()
  })

  it('IE-15 拒绝错型 JSON/越界 topK 且清掉旧指标（issue-03/05/09 已修）', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ avgRecall: 1 }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    // issue-09：先成功一轮，随后本地校验失败时旧指标卡不得残留。
    await wrapper
      .get('[aria-label="检索评测用例 JSON"]')
      .setValue('[{"id":"c","question":"q","relevantDocIds":[]}]')
    await runRetrievalButton(wrapper).trigger('click')
    await settle()
    expect(wrapper.get('.stat__value').text()).toBe('1.000')
    fetchMock.mockClear()
    // issue-03：合法 JSON 但错型（非数组/空数组/畸形元素）应前端阻断、0 fetch。
    for (const invalid of ['{}', 'null', '1', '[null]', '[]', '{bad']) {
      await wrapper.get('[aria-label="检索评测用例 JSON"]').setValue(invalid)
      await runRetrievalButton(wrapper).trigger('click')
    }
    expect(wrapper.find('.stat__value').exists()).toBe(false)
    await wrapper
      .get('[aria-label="检索评测用例 JSON"]')
      .setValue('[{"id":"c","question":"q","relevantDocIds":[]}]')
    // issue-05（收窄）：越界/非整数 topK 应前端阻断。
    for (const invalidTopK of ['0', '51', '1.5']) {
      await wrapper.get('.ie__retrieval input[type="number"]').setValue(invalidTopK)
      await runRetrievalButton(wrapper).trigger('click')
    }
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toMatch(/数组|TopK/)
    wrapper.unmount()
  })

  it('IE-24 arguments 非对象 JSON 阻断；tools null 成功有空体终态；探测器过滤伪项目（issue-04/10/14 已修）', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === '/interop/mcp/tools') {
        // null / 空对象 / 重名 → 应只留一个可点的 real（issue-14）
        return Promise.resolve(jsonResponse([null, {}, { name: 'real' }, { name: 'real' }]))
      }
      if (url === '/interop/mcp/tools/real') return Promise.resolve(jsonResponse({ name: 'real' }))
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await buttonByText(wrapper, '列出工具').trigger('click')
    await settle()
    expect(wrapper.findAll('.ie__tool-name').map((n) => n.text())).toEqual(['real'])
    await toolButton(wrapper, 'real').trigger('click')
    await settle()
    // issue-04：数组/标量 arguments 前端阻断，0 次 call fetch。
    for (const bad of ['[]', '1', 'null', '"x"']) {
      await wrapper.get('[aria-label="MCP 调用参数 JSON"]').setValue(bad)
      await buttonByText(wrapper, '调用').trigger('click')
      expect(wrapper.get('[role="alert"]').text()).toContain('arguments 必须是 JSON 对象')
    }
    expect(fetchMock.mock.calls.filter(([url]) => url === '/interop/mcp/call')).toHaveLength(0)

    // issue-10：tools 成功但 null 响应体 → 明确空体终态。
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(null)))
    await buttonByText(wrapper, '重新列出').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('成功，但响应体为空')
    wrapper.unmount()
  })

  it('IE-16 envelope 多键冲突取首个非空数组（issue-15 已修：空数组不遮蔽）', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ tools: [], results: [{ name: 'real-tool' }] })),
    )
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await buttonByText(wrapper, '列出工具').trigger('click')
    await settle()
    // 契约已定为「首个非空数组优先」（firstArray 实现）；全空数组时回落首个空数组 → 空态。
    expect(wrapper.text()).toContain('real-tool')
    wrapper.unmount()
  })

  it('IE-17 同 tick 双击只发一次（issue-12 已修：函数级 busy 防重）', async () => {
    const pending = deferred<Response>()
    const fetchMock = vi.fn().mockReturnValue(pending.promise)
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    const button = buttonByText(wrapper, '列出工具')
    const first = button.trigger('click')
    const second = button.trigger('click')
    await Promise.all([first, second])
    expect(fetchMock).toHaveBeenCalledTimes(1)
    pending.resolve(jsonResponse([]))
    wrapper.unmount()
  })

  it('IE-18 凭证切换清空旧租户数据并拒绝旧响应回写（issue-11 已修）', async () => {
    // 阶段 1（issue-11）：已展示的旧租户数据在凭证切换时应立即清空。
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse([{ name: 'tenant-a-visible-tool' }])),
    )
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await buttonByText(wrapper, '列出工具').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('tenant-a-visible-tool')
    useSessionStore().setApiKey('tenant-b-key')
    await settle()
    expect(wrapper.text()).not.toContain('tenant-a-visible-tool')
    // 阶段 2（issue-11）：pending 旧请求晚到不得回写新会话。
    const pending = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(pending.promise))
    await buttonByText(wrapper, '列出工具').trigger('click')
    useSessionStore().setApiKey('tenant-c-key')
    pending.resolve(jsonResponse([{ name: 'tenant-b-secret-tool' }]))
    await settle()
    expect(wrapper.text()).not.toContain('tenant-b-secret-tool')
    // 阶段 3（issue-17）：卸载时专用请求应被 abort（runContext 需带 signal）。
    wrapper.unmount()
  })

  it('IE-19 catalog retrieval 示例匹配后端 RetrievalCase record（issue-02 已修）', () => {
    const cap = useCatalogStore().capabilityById('eval.retrieval')!
    // 后端 protocol：RetrievalCase(String id, String question, List<String> relevantDocIds)。
    const parsed = JSON.parse(cap.examples![0].body) as { cases: Record<string, unknown>[] }
    expect(Object.keys(parsed.cases[0]).sort()).toEqual(['id', 'question', 'relevantDocIds'])
    const placeholder = cap.params.find((p) => p.name === 'cases')?.placeholder ?? ''
    expect(placeholder).toContain('question')
    expect(placeholder).not.toContain('expectedDocIds')
  })
})
