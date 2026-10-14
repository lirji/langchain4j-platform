import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RetrievalQualityPanel from './RetrievalQualityPanel.vue'
import { buttonByText, cleanup, jsonResponse, setupCatalog } from '../../test/interactionHarness'
import { useSessionStore } from '../../stores/session'
const definition = { name: '退款检索集', cases: [{ id: 'refund', question: '多久退款', relevant: [{ visibility: 'tenant', docId: 'refund' }], feedbackId: null }], settings: { topK: 5, minScore: 0, category: null, allowedDrop: 0, allowedP95IncreaseMs: 1000 } }
const dataset = { id: 'd1', revision: 2, definition, baselineRunId: null, updatedBy: 'editor', updatedAt: 1 }
const result = { id: 'r1', datasetId: 'd1', datasetRevision: 2, snapshot: definition, label: '调整后', startedAt: 1, status: 'SUCCEEDED', baselineRunId: null, metrics: { recall: 1, precision: 1, mrr: 1, hitRate: 1, p50Ms: 10, p95Ms: 10 }, results: [], gate: { verdict: 'BASELINE_REQUIRED', reasons: ['baseline_required'], recallDelta: null } }
async function open(view: ReturnType<typeof mount>) { await buttonByText(view, '查看评测集').trigger('click'); await flushPromises(); await buttonByText(view, '退款检索集').trigger('click') }
describe('检索质量评测', () => {
  beforeEach(() => setupCatalog())
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); cleanup() })
  it('完整执行仍无基线时不显示通过，设基线使用服务端修订号', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse([dataset])).mockResolvedValueOnce(jsonResponse(result)).mockResolvedValueOnce(jsonResponse({ ...dataset, revision: 3, baselineRunId: 'r1' }))
    vi.stubGlobal('fetch', fetcher); const view = mount(RetrievalQualityPanel)
    expect(fetcher).not.toHaveBeenCalled(); await open(view)
    await view.get('form[aria-label="执行检索评测"] input').setValue('调整后')
    await view.get('form[aria-label="执行检索评测"]').trigger('submit'); await flushPromises()
    expect(view.text()).toContain('调整后：尚无基线'); expect(view.text()).not.toContain('调整后：通过')
    await buttonByText(view, '将此运行设为基线').trigger('click'); await flushPromises()
    expect(JSON.parse(fetcher.mock.calls[2][1].body)).toEqual({ expectedRevision: 2, runId: 'r1' })
    view.unmount()
  })
  it('未知结果重试复用运行键，明确失败后可以发起新运行', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse([dataset])).mockRejectedValueOnce(new TypeError('network')).mockResolvedValueOnce(jsonResponse({ ...result, status: 'FAILED', metrics: null, gate: { verdict: 'FAILED', reasons: ['incomplete_evaluation'] } })).mockResolvedValueOnce(jsonResponse(result))
    vi.stubGlobal('fetch', fetcher); const view = mount(RetrievalQualityPanel); await open(view)
    const form = view.get('form[aria-label="执行检索评测"]'); await form.get('input').setValue('调整后')
    await form.trigger('submit'); await flushPromises(); await form.trigger('submit'); await flushPromises()
    const first = JSON.parse(fetcher.mock.calls[1][1].body).idempotencyKey
    expect(JSON.parse(fetcher.mock.calls[2][1].body).idempotencyKey).toBe(first)
    expect(view.text()).toContain('不能作为通过证据或基线')
    expect(view.findAll('button').some(b => b.text() === '将此运行设为基线')).toBe(false)
    await form.trigger('submit'); await flushPromises()
    expect(JSON.parse(fetcher.mock.calls[3][1].body).idempotencyKey).not.toBe(first)
    view.unmount()
  })
  it('版本冲突后刷新不覆盖正在编辑的定义', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse([dataset])).mockResolvedValueOnce(jsonResponse({ code: 'conflict' }, 409)).mockResolvedValueOnce(jsonResponse({ ...dataset, revision: 3 }))
    vi.stubGlobal('fetch', fetcher); const view = mount(RetrievalQualityPanel); await open(view)
    await buttonByText(view, '编辑定义').trigger('click')
    const form = view.get('form[aria-label="评测集编辑"]'); await form.get('input').setValue('本地编辑')
    await form.trigger('submit'); await flushPromises(); expect(view.text()).toContain('未保存内容已保留')
    await buttonByText(view, '刷新评测集').trigger('click'); await flushPromises()
    expect((form.get('input').element as HTMLInputElement).value).toBe('本地编辑')
    view.unmount()
  })
  it('权限失败不伪装成空列表，切换身份丢弃迟到响应', async () => {
    let resolve!: (r: Response) => void
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse({ code: 'forbidden' }, 403)).mockImplementationOnce(() => new Promise<Response>(r => { resolve = r }))
    vi.stubGlobal('fetch', fetcher); const view = mount(RetrievalQualityPanel)
    await buttonByText(view, '查看评测集').trigger('click'); await flushPromises(); expect(view.text()).toContain('需要 ingest 权限'); expect(view.text()).not.toContain('暂无评测集')
    await buttonByText(view, '查看评测集').trigger('click'); useSessionStore().apiKey = 'changed'; await flushPromises()
    resolve(jsonResponse([dataset])); await flushPromises(); expect(view.text()).not.toContain('退款检索集')
    view.unmount()
  })
})
