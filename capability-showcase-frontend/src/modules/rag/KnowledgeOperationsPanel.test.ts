import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import KnowledgeOperationsPanel from './KnowledgeOperationsPanel.vue'
import { buttonByText, cleanup, jsonResponse, setupCatalog } from '../../test/interactionHarness'
import { useSessionStore } from '../../stores/session'
const row = { id: 'candidate-1', tenantId: 'tenant', documentId: 'doc-1', feedbackId: null, rollbackOf: null,
  draft: { title: '退款政策', content: '三天退款', category: null, checks: [{ question: '退款多久', requiredText: '三天' }] },
  status: 'APPROVED', version: 2, evaluation: null, probe: null, release: { documentVersion: 0, reviewer: 'editor', publisher: null, jobId: null }, createdBy: 'editor', updatedBy: 'editor', createdAt: 1, updatedAt: 1 }
describe('知识运营闭环', () => {
  beforeEach(() => setupCatalog())
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); cleanup() })
  it('202显示发布中并携带服务端修订号，不显示发布完成', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse([row]))
      .mockResolvedValueOnce(jsonResponse({ ...row, status: 'PUBLISHING', version: 4,
        release: { ...row.release, documentVersion: 1, publisher: 'editor', jobId: 'job-1' } }, 202))
    vi.stubGlobal('fetch', fetcher)
    const view = mount(KnowledgeOperationsPanel)
    expect(fetcher).not.toHaveBeenCalled()
    await buttonByText(view, '查看修订候选').trigger('click'); await flushPromises()
    await buttonByText(view, '退款政策 · 已审核').trigger('click')
    await buttonByText(view, '发布到知识库').trigger('click'); await flushPromises()
    expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({ expectedVersion: 2 })
    expect(view.text()).toContain('入库处理中')
    expect(view.text()).not.toContain('已发布')
    expect(view.text()).toContain('同步入库状态')
    view.unmount()
  })
  it('409后保留草稿，刷新只更新修订，需再次主动保存', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse([row]))
      .mockResolvedValueOnce(jsonResponse({ code: 'conflict' }, 409))
      .mockResolvedValueOnce(jsonResponse({ ...row, version: 5 }))
    vi.stubGlobal('fetch', fetcher)
    const view = mount(KnowledgeOperationsPanel)
    await buttonByText(view, '查看修订候选').trigger('click'); await flushPromises()
    await buttonByText(view, '退款政策 · 已审核').trigger('click')
    await buttonByText(view, '编辑草稿').trigger('click')
    await view.get('form[aria-label="知识草稿"] textarea').setValue('新政策内容')
    await view.get('form[aria-label="知识草稿"]').trigger('submit'); await flushPromises()
    expect(view.get('[role=alert]').text()).toContain('编辑内容已保留')
    await buttonByText(view, '刷新详情').trigger('click'); await flushPromises()
    expect((view.get('form[aria-label="知识草稿"] textarea').element as HTMLTextAreaElement).value).toBe('新政策内容')
    expect(fetcher).toHaveBeenCalledTimes(3)
    view.unmount()
  })
  it('权限拒绝不显示空收件箱，身份改变清除旧响应', async () => {
    let resolve!: (r: Response) => void
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse({ code: 'forbidden' }, 403))
      .mockImplementationOnce(() => new Promise<Response>(r => { resolve = r }))
    vi.stubGlobal('fetch', fetcher)
    const view = mount(KnowledgeOperationsPanel)
    await buttonByText(view, '查看反馈收件箱').trigger('click'); await flushPromises()
    expect(view.text()).toContain('需要 ingest 权限')
    expect(view.text()).not.toContain('暂无记录')
    await buttonByText(view, '查看修订候选').trigger('click')
    useSessionStore().apiKey = 'other-test-key'; await flushPromises()
    resolve(jsonResponse([row])); await flushPromises()
    expect(view.text()).not.toContain('退款政策')
    view.unmount()
  })
  it('网络失败重试反馈复用幂等键，变更输入生成新意图', async () => {
    const fetcher = vi.fn().mockRejectedValue(new TypeError('network'))
    vi.stubGlobal('fetch', fetcher)
    const view = mount(KnowledgeOperationsPanel, { props: { query: '退款多久' } })
    await buttonByText(view, '使用当前检索问题').trigger('click')
    const form = view.get('form[aria-label="检索反馈"]')
    await form.trigger('submit'); await flushPromises()
    await form.trigger('submit'); await flushPromises()
    const first = JSON.parse(fetcher.mock.calls[0][1].body).idempotencyKey
    expect(JSON.parse(fetcher.mock.calls[1][1].body).idempotencyKey).toBe(first)
    await form.get('textarea').setValue('补充信息')
    await form.trigger('submit'); await flushPromises()
    expect(JSON.parse(fetcher.mock.calls[2][1].body).idempotencyKey).not.toBe(first)
    view.unmount()
  })
})
