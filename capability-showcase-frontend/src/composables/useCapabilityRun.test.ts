import { effectScope, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCapabilityRun } from './useCapabilityRun'
import { useCatalogStore } from '../stores/catalog'
import { cleanup, jsonResponse, setupCatalog, sseResponse, textResponse } from '../test/interactionHarness'

/**
 * 核心执行状态机单测：一次性 JSON 与 SSE 流的成功/错误/中断/上限/终态。
 * 不挂完整页面，失败定位到状态机本身。
 */

async function waitFor(check: () => boolean): Promise<void> {
  for (let i = 0; i < 30; i += 1) {
    if (check()) return
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  throw new Error('condition did not become true')
}

describe('useCapabilityRun', () => {
  beforeEach(() => setupCatalog())
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    cleanup()
  })

  it('JSON success 锁定 status/trace/result/elapsed 与 success 终态', async () => {
    vi.spyOn(Date, 'now').mockReturnValueOnce(100).mockReturnValue(145)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ reply: 'ok' })))
    const scope = effectScope()
    const run = scope.run(() => useCapabilityRun(() => useCatalogStore().capabilityById('chat.sync')!))!
    await run.run({ chatId: 'c1', message: 'hello' })
    expect(run.phase.value).toBe('success')
    expect(run.result.value?.data).toEqual({ reply: 'ok' })
    expect(run.httpStatus.value).toBe(200)
    expect(run.traceId.value).toBe('trace-12345678')
    expect(run.elapsedMs.value).toBe(45)
    scope.stop()
  })

  it('HTTP JSON 错误保留 status/body 并呈现人话，不伪成功', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ message: 'bad input' }, 400)))
    const scope = effectScope()
    const run = scope.run(() => useCapabilityRun(() => useCatalogStore().capabilityById('chat.sync')!))!
    await run.run({ message: 'x' })
    expect(run.phase.value).toBe('error')
    expect(run.httpStatus.value).toBe(400)
    expect(run.errorMessage.value).toContain('bad input')
    expect(run.result.value?.data).toEqual({ message: 'bad input' })
    scope.stop()
  })

  it('网络错误与无凭证 gate 分流，gate 拒绝时 0 fetch', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('offline'))
    vi.stubGlobal('fetch', fetchMock)
    const scope = effectScope()
    const run = scope.run(() => useCapabilityRun(() => useCatalogStore().capabilityById('chat.sync')!))!
    await run.run({ message: 'x' })
    expect(run.phase.value).toBe('error')
    expect(run.errorMessage.value).toContain('网络请求失败')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    // 无凭证：gate 直接拒绝，不再发起第二次 fetch。
    setupCatalog('')
    const gated = scope.run(() => useCapabilityRun(() => useCatalogStore().capabilityById('chat.sync')!))!
    await gated.run({ message: 'x' })
    expect(gated.phase.value).toBe('error')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    scope.stop()
  })

  it('token SSE 跨 chunk 拼接、旁路 note、trace 与 complete 终态', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'data: hel',
      'lo\n\nid: 8\nevent: grounding-warning\ndata: weak source\n\n',
      'data: !\n\nevent: done\ndata: {}\n\n',
    ])))
    const scope = effectScope()
    const run = scope.run(() => useCapabilityRun(() => useCatalogStore().capabilityById('chat.stream')!))!
    await run.run({ message: 'x' })
    await waitFor(() => run.phase.value === 'done')
    expect(run.sse.tokens).toBe('hello!')
    expect(run.sse.events.map((e) => e.event)).toEqual(['message', 'grounding-warning', 'message', 'done'])
    expect(run.sse.note).toContain('weak source')
    expect(run.sse.status).toBe('done')
    expect(run.traceId.value).toBe('trace-sse-1234')
    scope.stop()
  })

  it('命名 stage SSE 不拼 token，业务 error 归 error 终态', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event: attempt-start\ndata: {"n":1}\n\n',
      'event: error\ndata: evaluator failed\n\n',
    ])))
    const scope = effectScope()
    const run = scope.run(() =>
      useCapabilityRun(() => useCatalogStore().capabilityById('agent.reflexive.stream')!),
    )!
    await run.run({ question: 'q' })
    await waitFor(() => run.phase.value === 'error')
    expect(run.sse.tokens).toBe('')
    expect(run.sse.events).toHaveLength(2)
    expect(run.errorMessage.value).toBe('evaluator failed')
    expect(run.sse.status).toBe('error')
    scope.stop()
  })

  it('SSE 握手错误读取文本体并进入 error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(textResponse('gateway down', 503)))
    const scope = effectScope()
    const run = scope.run(() => useCapabilityRun(() => useCatalogStore().capabilityById('chat.stream')!))!
    await run.run({ message: 'x' })
    await waitFor(() => run.phase.value === 'error')
    expect(run.httpStatus.value).toBe(503)
    expect(run.errorMessage.value).toContain('gateway down')
    scope.stop()
  })

  it('非 SSE 请求中途 abort 进入 aborted，提示已取消', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_url: string, init: RequestInit) =>
      new Promise((_resolve, reject) =>
        init.signal?.addEventListener('abort', () => reject(new DOMException('', 'AbortError'))),
      ),
    ))
    const scope = effectScope()
    const run = scope.run(() => useCapabilityRun(() => useCatalogStore().capabilityById('chat.sync')!))!
    const pending = run.run({ message: 'x' })
    await Promise.resolve()
    run.abort()
    await pending
    expect(run.phase.value).toBe('aborted')
    expect(run.errorMessage.value).toContain('已取消')
    scope.stop()
  })

  it('SSE 读取中途 transport 异常进入 error，保留已收 token', async () => {
    const encoder = new TextEncoder()
    // pull 驱动：第一次读到 token，第二次读抛 transport 异常——error() 会丢弃未消费队列，不能用 start+enqueue。
    let pulls = 0
    const body = new ReadableStream<Uint8Array>({
      pull(c) {
        pulls += 1
        if (pulls === 1) c.enqueue(encoder.encode('data: partial\n\n'))
        else c.error(new TypeError('network reset'))
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 200 })))
    const scope = effectScope()
    const run = scope.run(() => useCapabilityRun(() => useCatalogStore().capabilityById('chat.stream')!))!
    await run.run({ message: 'x' })
    await waitFor(() => run.phase.value === 'error')
    expect(run.sse.status).toBe('error')
    expect(run.sse.tokens).toBe('partial')
    expect(run.errorMessage.value).toBeTruthy()
    scope.stop()
  })

  it('abort 正在读取的 SSE 后进入 aborted 且不报 transport error', async () => {
    let streamController!: ReadableStreamDefaultController<Uint8Array>
    const body = new ReadableStream<Uint8Array>({ start(c) { streamController = c } })
    const fetchMock = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      init.signal?.addEventListener('abort', () =>
        streamController.error(new DOMException('aborted', 'AbortError')),
      )
      return Promise.resolve(new Response(body, { status: 200 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const scope = effectScope()
    const run = scope.run(() => useCapabilityRun(() => useCatalogStore().capabilityById('chat.stream')!))!
    await run.run({ message: 'x' })
    run.abort()
    await waitFor(() => run.phase.value === 'aborted')
    expect(run.sse.status).toBe('aborted')
    scope.stop()
  })

  it('通用流事件缓存最多 2000 条', async () => {
    const frames = Array.from({ length: 2005 }, (_, i) => `event: RUNNING\nid: ${i}\ndata: {}\n\n`)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(frames)))
    const scope = effectScope()
    const run = scope.run(() =>
      useCapabilityRun(() => useCatalogStore().capabilityById('agent.tasks.stream')!),
    )!
    await run.run({ taskId: 't1' })
    await waitFor(() => run.phase.value === 'done')
    expect(run.sse.events).toHaveLength(2000)
    scope.stop()
  })

  it('issue-03 回归：reset 后旧流 onDone 不得把 idle 回写为 aborted', async () => {
    let streamController!: ReadableStreamDefaultController<Uint8Array>
    const body = new ReadableStream<Uint8Array>({ start(c) { streamController = c } })
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      init.signal?.addEventListener('abort', () =>
        queueMicrotask(() => streamController.error(new DOMException('', 'AbortError'))),
      )
      return Promise.resolve(new Response(body, { status: 200 }))
    }))
    const scope = effectScope()
    const run = scope.run(() => useCapabilityRun(() => useCatalogStore().capabilityById('chat.stream')!))!
    await run.run({ message: 'x' })
    run.reset()
    await nextTick()
    await Promise.resolve()
    expect(run.phase.value).toBe('idle')
    expect(run.sse.status).toBe('idle')
    scope.stop()
  })
})
