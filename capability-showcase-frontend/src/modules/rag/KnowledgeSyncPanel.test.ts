import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import KnowledgeSyncPanel from './KnowledgeSyncPanel.vue'
import { buttonByText, cleanup, jsonResponse, setupCatalog } from '../../test/interactionHarness'
import { useSessionStore } from '../../stores/session'
const source = { sourceId: 'faq', status: 'BLOCKED', errorCode: 'INGESTION_REQUIRES_ATTENTION', remaining: 1, pendingJobId: 'job1', nextPollAt: 0, paused: false, controlRevision: 3, lastSuccessAt: null, activeRunId: 'r1', executionUserId: 'sync-user' }
const run = { id: 'r1', sequence: '123', sourceId: 'faq', status: 'BLOCKED', triggeredBy: 'operator', executionUserId: 'sync-user', startedAt: 1, updatedAt: 2, finishedAt: null, configHash: 'hash', checkpointBeforeHash: 'before', checkpointAfterHash: null, fetchedCount: 1, remaining: 1, errorCode: 'INGESTION_REQUIRES_ATTENTION', created: 0, updated: 0, deleted: 0, skipped: 0, failed: 1, records: [{ externalId: 'faq1', sourceRevision: 1, documentId: 'doc1', action: 'CREATED', status: 'FAILED', errorCode: 'INGESTION_REQUIRES_ATTENTION', jobId: 'job1' }] }
async function open(view: ReturnType<typeof mount>) { await buttonByText(view, '查看数据源').trigger('click'); await flushPromises(); await view.get('nav button').trigger('click') }
describe('数据接入工作台', () => {
  beforeEach(() => setupCatalog())
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); cleanup() })
  it('暂停使用服务端控制版本，恢复前禁止触发与重试', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse([source])).mockResolvedValueOnce(jsonResponse({ ...source, paused: true, status: 'PAUSED', controlRevision: 4 })).mockResolvedValueOnce(jsonResponse({ ...source, controlRevision: 5 }))
    vi.stubGlobal('fetch', fetcher); const view = mount(KnowledgeSyncPanel); expect(fetcher).not.toHaveBeenCalled(); await open(view)
    await buttonByText(view, '暂停来源').trigger('click'); await flushPromises()
    expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({ expectedRevision: 3 })
    expect(buttonByText(view, '立即同步').attributes('disabled')).toBeDefined()
    expect(buttonByText(view, '重试失败任务').attributes('disabled')).toBeDefined()
    expect(view.text()).toContain('已领取的步骤和入库任务仍可能完成')
    await buttonByText(view, '恢复来源').trigger('click'); await flushPromises()
    expect(JSON.parse(fetcher.mock.calls[2][1].body)).toEqual({ expectedRevision: 4 })
    expect(view.text()).toContain('将从原检查点继续'); view.unmount()
  })
  it('受理不等于成功，运行分页与失败详情来自服务器', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse([source])).mockResolvedValueOnce(jsonResponse(source))
      .mockResolvedValueOnce(jsonResponse({ items: [run], nextCursor: '123' })).mockResolvedValueOnce(jsonResponse(run))
      .mockResolvedValueOnce(jsonResponse({ items: [], nextCursor: null }))
    vi.stubGlobal('fetch', fetcher); const view = mount(KnowledgeSyncPanel); await open(view)
    await buttonByText(view, '重试失败任务').trigger('click'); await flushPromises()
    expect(fetcher.mock.calls[1][0]).toContain('/faq/retry'); expect(view.text()).toContain('请求已受理。是否完成')
    await buttonByText(view, '查看运行记录').trigger('click'); await flushPromises()
    await view.get('.sync-runs button').trigger('click'); await flushPromises()
    expect(view.get('[aria-label="运行详情"]').text()).toContain('faq1 · 版本 1')
    expect(view.get('[aria-label="运行详情"]').text()).toContain('新增 · 失败')
    await buttonByText(view, '下一页运行记录').trigger('click'); await flushPromises()
    expect(fetcher.mock.calls[4][0]).toContain('before=123'); expect(view.find('[aria-label="运行详情"]').exists()).toBe(false)
    view.unmount()
  })
  it('冲突后刷新再操作采用新版本，不自动覆盖他人暂停', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse([source])).mockResolvedValueOnce(jsonResponse({ code: 'CONTROL_CONFLICT' }, 409))
      .mockResolvedValueOnce(jsonResponse({ ...source, paused: true, status: 'PAUSED', controlRevision: 8 }))
    vi.stubGlobal('fetch', fetcher); const view = mount(KnowledgeSyncPanel); await open(view)
    await buttonByText(view, '暂停来源').trigger('click'); await flushPromises()
    expect(view.text()).toContain('请刷新来源'); expect(fetcher).toHaveBeenCalledTimes(2)
    await buttonByText(view, '刷新来源').trigger('click'); await flushPromises()
    expect(buttonByText(view, '恢复来源').exists()).toBe(true); view.unmount()
  })
  it('权限与未启用错误不能显示成成功空态，身份切换丢弃迟到响应', async () => {
    let resolve!: (r: Response) => void
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse({}, 403)).mockResolvedValueOnce(jsonResponse({}, 404)).mockImplementationOnce(() => new Promise<Response>(r => { resolve = r }))
    vi.stubGlobal('fetch', fetcher); const view = mount(KnowledgeSyncPanel)
    await buttonByText(view, '查看数据源').trigger('click'); await flushPromises(); expect(view.text()).toContain('需要 ingest 权限'); expect(view.text()).not.toContain('暂无可管理')
    await buttonByText(view, '查看数据源').trigger('click'); await flushPromises(); expect(view.text()).toContain('数据接入未启用')
    await buttonByText(view, '查看数据源').trigger('click'); useSessionStore().apiKey = 'changed'; await flushPromises()
    resolve(jsonResponse([source])); await flushPromises(); expect(view.text()).not.toContain('sync-user'); expect(view.find('nav').exists()).toBe(false)
    view.unmount()
  })
  it('切换来源取消旧详情请求，迟到数据不能出现在新来源中', async () => {
    let resolve!: (r: Response) => void
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse([source, { ...source, sourceId: 'products' }])).mockResolvedValueOnce(jsonResponse({ items: [run], nextCursor: null })).mockImplementationOnce(() => new Promise<Response>(r => { resolve = r }))
    vi.stubGlobal('fetch', fetcher); const view = mount(KnowledgeSyncPanel); await open(view)
    await buttonByText(view, '查看运行记录').trigger('click'); await flushPromises(); await view.get('.sync-runs button').trigger('click')
    await view.findAll('nav button')[1]!.trigger('click'); resolve(jsonResponse(run)); await flushPromises()
    expect(view.get('[aria-label="来源详情"] h3').text()).toBe('products'); expect(view.find('[aria-label="运行详情"]').exists()).toBe(false)
    view.unmount()
  })
})
