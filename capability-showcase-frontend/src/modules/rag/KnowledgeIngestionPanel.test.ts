import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { setupCatalog, buttonByText, deferred, jsonResponse } from '../../test/interactionHarness'
import { useSessionStore } from '../../stores/session'
import { useAuthStore } from '../../stores/auth'
import { useIngestionState } from './ingestionState'
import KnowledgeIngestionPanel from './KnowledgeIngestionPanel.vue'
import { POLL_INTERVAL_MS } from './knowledgePresentation'

const job = (overrides = {}) => ({ jobId: 'job-1', documentId: 'manual', documentVersion: 1, status: 'PROCESSING', sinks: { VECTOR: 'RUNNING' }, retryCount: 0, ...overrides })
function fill() {
  const state = useIngestionState()
  state.documentId = 'manual'; state.file = new File(['真实测试正文'], 'manual.docx', { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' })
  return state
}
const wrappers: ReturnType<typeof mount>[] = []
function render(props = {}) { const wrapper = mount(KnowledgeIngestionPanel, { props }); wrappers.push(wrapper); return wrapper }
beforeEach(() => { setupCatalog(); vi.useFakeTimers() })
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0; vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('异步文档入库', () => {
  it('按真实 multipart 契约提交，202 不宣称完成，READY 后才通知文档刷新', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(jsonResponse(job(url.endsWith('/job-1') ? { status: 'READY', sinks: { VECTOR: 'SUCCEEDED', REGISTRY: 'SUCCEEDED' } } : {}), 202)))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = render(); const state = fill()
    await wrapper.get('.ki-form').trigger('submit'); await flushPromises()
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/rag/ingestions')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('Content-Type')).toBeNull()
    expect(new Headers(init.headers).get('Idempotency-Key')).toBe(state.attempt!.idempotencyKey)
    const body = init.body as FormData
    expect(body.get('file')).toBeInstanceOf(File)
    expect(body.get('documentId')).toBe('manual')
    expect(body.get('documentVersion')).toBe('1')
    expect(body.get('displayName')).toBe('manual.docx')
    expect(wrapper.emitted('ready')).toBeUndefined()
    expect(wrapper.text()).toContain('入库处理中')
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(wrapper.emitted('ready')).toHaveLength(1)
    expect(wrapper.text()).toContain('已可检索')
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('断网后锁定原文件并复用幂等键确认，不生成第二个意图', async () => {
    const fetchMock = vi.fn().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(jsonResponse(job(), 202))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = render(); const state = fill()
    await wrapper.get('.ki-form').trigger('submit'); await flushPromises()
    const key = state.attempt!.idempotencyKey
    expect(state.phase).toBe('uncertain')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('确认原提交结果')
    expect(state.newDocument()).toBe(false)
    await wrapper.get('.ki-form').trigger('submit'); await flushPromises()
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get('Idempotency-Key')).toBe(key)
    expect((fetchMock.mock.calls[1][1].body as FormData).get('file')).toBe((fetchMock.mock.calls[0][1].body as FormData).get('file'))
    expect(state.phase).toBe('accepted')
  })

  it('停止等待只中止 HTTP，保留原提交并明确结果未知', async () => {
    const pending = deferred<Response>()
    let signal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_url, init) => { signal = init.signal; return pending.promise }))
    const wrapper = render(); const state = fill()
    await wrapper.get('.ki-form').trigger('submit')
    const key = state.attempt!.idempotencyKey
    await buttonByText(wrapper, '停止等待').trigger('click')
    expect(signal?.aborted).toBe(true)
    expect(state.phase).toBe('uncertain')
    expect(state.attempt!.idempotencyKey).toBe(key)
    pending.resolve(jsonResponse(job(), 202)); await flushPromises()
    expect(state.jobs).toHaveLength(0)
    expect(wrapper.text()).toContain('服务端仍可能处理中')
  })

  it('409 保留输入，修正文档版本后分配新提交编号', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ error: 'conflict' }, 409))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = render(); const state = fill()
    await wrapper.get('.ki-form').trigger('submit'); await flushPromises()
    const key = state.attempt!.idempotencyKey
    expect(state.documentId).toBe('manual'); expect(state.file!.name).toBe('manual.docx')
    expect(state.phase).toBe('rejected')
    state.documentVersion = 2
    await wrapper.get('.ki-form').trigger('submit'); await flushPromises()
    expect(state.attempt!.idempotencyKey).not.toBe(key)
    expect(wrapper.text()).toContain('文档版本、提交内容或任务状态冲突')
  })

  it('回执内容损坏按未知结果处理，不清空原提交', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ ok: true }, 202)))
    const wrapper = render(); const state = fill()
    await wrapper.get('.ki-form').trigger('submit'); await flushPromises()
    expect(state.phase).toBe('uncertain'); expect(state.attempt).not.toBeNull()
    expect(state.jobs).toHaveLength(0)
  })

  it('HTTP 408 可能已经受理，仍锁定原提交，不能视为明确拒绝', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({}, 408)))
    const wrapper = render(); const state = fill()
    await wrapper.get('.ki-form').trigger('submit'); await flushPromises()
    expect(state.phase).toBe('uncertain')
    expect(state.newDocument()).toBe(false)
  })

  it('使用记录的原提交编号恢复时，按原编号提交并由服务端验证文件', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(job(), 202))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = render(); const state = fill()
    state.recoveryKey = '  original-ingestion-key  '
    await wrapper.get('.ki-form').trigger('submit'); await flushPromises()
    expect(new Headers(fetchMock.mock.calls[0][1].headers).get('Idempotency-Key')).toBe('original-ingestion-key')
  })

  it('重试请求断网后必须先查询状态，不能继续重复重试', async () => {
    const fetchMock = vi.fn().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(jsonResponse(job({ status: 'PROCESSING' })))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = render(); const state = useIngestionState()
    state.upsert(job({ status: 'FAILED' })); await flushPromises()
    await buttonByText(wrapper, '已处理原因，重试原任务').trigger('click'); await flushPromises()
    expect(buttonByText(wrapper, '已处理原因，重试原任务').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('请先刷新任务状态')
    await buttonByText(wrapper, '刷新').trigger('click'); await flushPromises()
    expect(fetchMock.mock.calls[1][0]).toBe('/rag/ingestions/job-1')
    expect(state.uncertainRetries).toHaveLength(0)
    expect(state.jobs[0].status).toBe('PROCESSING')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('人工处理态展示失败阶段与安全建议，可按后端契约手动重试', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(job({ status: 'PROCESSING' }), 202))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = render(); const state = useIngestionState()
    state.upsert(job({ status: 'MANUAL_REVIEW', retryable: false, errorCode: 'DOCUMENT_OCR_UNAVAILABLE', failedStage: 'PREPARE', sinks: {} }))
    await flushPromises()
    expect(wrapper.text()).toContain('OCR 引擎或语言数据不可用')
    expect(wrapper.text()).toContain('解析与分块准备')
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 2)
    expect(fetchMock).not.toHaveBeenCalled()
    await buttonByText(wrapper, '已处理原因，重试原任务').trigger('click'); await flushPromises()
    expect(fetchMock.mock.calls[0][0]).toBe('/rag/ingestions/job-1/retry')
    expect(fetchMock.mock.calls[0][1].method).toBe('POST')
    expect(wrapper.text()).toContain('重试已受理')
  })

  it('PARTIAL 有下次重试时继续观察，失败不能当作已完成', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(job({ status: 'READY' })))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = render()
    useIngestionState().upsert(job({ status: 'PARTIAL', retryable: true, retryCount: 1, nextRetryAt: '2026-10-19T00:00:00Z' }))
    await flushPromises()
    expect(wrapper.text()).toContain('2026/10/19 08:00:00')
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(wrapper.emitted('ready')).toHaveLength(1)
  })

  it('并发跟踪时，另一任务成功不能抹掉失败任务的刷新提示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => Promise.resolve(
      url.endsWith('/job-1') ? jsonResponse({}, 404) : jsonResponse(job({ jobId: 'job-2', status: 'READY' })),
    )))
    const wrapper = render(); const state = useIngestionState()
    state.upsert(job()); state.upsert(job({ jobId: 'job-2' }))
    await flushPromises(); await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    expect(wrapper.text()).toContain('任务 job-1：')
    expect(wrapper.text()).toContain('保留上次查询结果')
    expect(state.jobs.find(row => row.jobId === 'job-2')?.status).toBe('READY')
  })

  it('身份切换清空文件与任务，旧回执不能写入新租户', async () => {
    const pending = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(pending.promise))
    const wrapper = render(); const state = fill()
    await wrapper.get('.ki-form').trigger('submit')
    useSessionStore().setApiKey('other-tenant-key')
    pending.resolve(jsonResponse(job(), 202)); await flushPromises()
    expect(state.jobs).toHaveLength(0); expect(state.file).toBeNull(); expect(state.phase).toBe('idle')
    expect(wrapper.emitted('ready')).toBeUndefined()
  })

  it('卸载停止轮询，原提交保留在会话内供返回页面确认', async () => {
    const pending = deferred<Response>()
    let signal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_url, init) => { signal = init.signal; return pending.promise }))
    const wrapper = render(); const state = fill()
    await wrapper.get('.ki-form').trigger('submit')
    const key = state.attempt!.idempotencyKey
    wrapper.unmount()
    expect(signal?.aborted).toBe(true); expect(state.phase).toBe('uncertain')
    expect(render().text()).toContain('确认原提交结果')
    expect(state.attempt!.idempotencyKey).toBe(key)
    pending.resolve(jsonResponse(job(), 202)); await flushPromises()
    expect(state.jobs).toHaveLength(0)
  })

  it('无 ingest 权限允许查询自己的任务，但禁止上传与人工重试', async () => {
    useSessionStore().clearApiKey()
    useAuthStore().accessToken = 'token'
    useAuthStore().user = { username: 'reader', tenant: 'acme', scopes: [] }
    const wrapper = render()
    useIngestionState().upsert(job({ status: 'FAILED' })); await flushPromises()
    expect(buttonByText(wrapper, '提交入库').attributes('disabled')).toBeDefined()
    expect(buttonByText(wrapper, '已处理原因，重试原任务').attributes('disabled')).toBeDefined()
  })
})
