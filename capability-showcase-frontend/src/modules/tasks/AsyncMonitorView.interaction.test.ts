import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SseHandlers } from '../../api/sse'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import AsyncMonitorView from './AsyncMonitorView.vue'
import {
  buttonByText,
  capability,
  cleanup,
  jsonResponse,
  RouterLinkStub,
  setupCatalog,
} from '../../test/interactionHarness'

/**
 * Async Monitor 交互测试：Runner 结果 ingest→时间线 upsert、刷新/取消、SSE 订阅投影、死信区块。
 * 一次性调用走 vi.stubGlobal('fetch') 路由式 mock（与 chat/rag 系一致——模块级 mock api/client 存在
 * importActual 依赖子图双实例问题，视图可能绑定到真实实现）；SSE 订阅用 streamCapability 模块 mock 注入 handlers。
 * 疑似 bug（issue-04/05/06）以 skip+期望行为呈现。
 */

const mocks = vi.hoisted(() => ({ stream: vi.fn() }))
vi.mock('../../api/sse', async (original) => ({
  ...(await original<typeof import('../../api/sse')>()),
  streamCapability: mocks.stream,
}))

const opts = { global: { stubs: { RouterLink: RouterLinkStub } } }

async function settle(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  await flushPromises()
}

function seed(wrapper: ReturnType<typeof mount>, status = 'RUNNING'): void {
  wrapper.findComponent(CapabilityRunner).vm.$emit('result', {
    cap: capability('async.create'),
    data: { taskId: 't-1', kind: 'agent.run', status },
    status: 200,
  })
}

function timelineButton(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('.tl__btn').find((b) => b.text().includes(text))
  if (!button) throw new Error(`timeline button missing: ${text}`)
  return button
}

describe('AsyncMonitorView interaction', () => {
  beforeEach(() => {
    setupCatalog()
    mocks.stream.mockReset()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    cleanup()
  })

  it('Runner create/update 结果按 taskId upsert，不产生重复 timeline item', async () => {
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.create' }, ...opts })
    seed(wrapper, 'PENDING')
    await settle()
    wrapper.findComponent(CapabilityRunner).vm.$emit('result', {
      cap: capability('async.status.update'),
      data: { taskId: 't-1', kind: 'agent.run', status: 'RUNNING' },
      status: 200,
    })
    await settle()
    expect(wrapper.findAll('.tl__item')).toHaveLength(1)
    expect(wrapper.get('.tl__item').text()).toContain('RUNNING')
    expect(wrapper.get('.tl__item').text()).toContain('agent.run')
    wrapper.unmount()
  })

  it('刷新详情 GET /async/tasks/{id} 并更新状态；失败只挂 error', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(
      jsonResponse({ taskId: 't-1', status: 'SUCCEEDED', kind: 'agent.run' }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.get' }, ...opts })
    seed(wrapper)
    await settle()
    await timelineButton(wrapper, '刷新').trigger('click')
    await settle()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/async/tasks/t-1')
    expect(init.method).toBe('GET')
    expect(new Headers(init.headers).get('X-Api-Key')).toBe('test-key')
    expect(wrapper.get('.tl__item').text()).toContain('SUCCEEDED')
    fetchMock.mockResolvedValueOnce(jsonResponse({ message: 'gone' }, 404))
    await timelineButton(wrapper, '刷新').trigger('click')
    await settle()
    expect(wrapper.get('.tl__error').text()).toContain('gone')
    wrapper.unmount()
  })

  it('cancel 200 响应无 status 时按后端契约投影 CANCELLED；失败不强写', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse({ taskId: 't-1', cancelled: true }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.cancel' }, ...opts })
    seed(wrapper)
    await settle()
    await timelineButton(wrapper, '取消').trigger('click')
    await settle()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/async/tasks/t-1')
    expect(init.method).toBe('DELETE')
    expect(wrapper.get('.tl__item').text()).toContain('CANCELLED')
    wrapper.unmount()

    // 失败路径：404（任务已终态）→ 保留原状态并展示错误，不得乐观强写 CANCELLED。
    setupCatalog()
    const failFetch = vi.fn().mockResolvedValueOnce(jsonResponse({ message: 'already terminal' }, 404))
    vi.stubGlobal('fetch', failFetch)
    const failed = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.cancel' }, ...opts })
    seed(failed)
    await settle()
    await timelineButton(failed, '取消').trigger('click')
    await settle()
    expect(failed.get('.tl__item').text()).toContain('RUNNING')
    expect(failed.get('.tl__error').text()).toContain('already terminal')
    failed.unmount()
  })

  it('SSE 状态事件进入同一 task，记录 event id/error，done 关闭 streaming', async () => {
    let handlers!: SseHandlers
    const abort = vi.fn()
    mocks.stream.mockImplementation((_cap, _values, _ctx, h: SseHandlers) => {
      handlers = h
      return { abort }
    })
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.stream' }, ...opts })
    seed(wrapper, 'PENDING')
    await settle()
    await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    expect(mocks.stream).toHaveBeenCalledWith(
      capability('async.stream'),
      { taskId: 't-1' },
      expect.any(Object),
      expect.any(Object),
      { lastEventId: undefined },
    )
    handlers.onEvent?.({ event: 'RUNNING', id: 'evt-42', data: '{"taskId":"t-1","status":"RUNNING"}' })
    await settle()
    expect(wrapper.get('.tl__item').text()).toContain('RUNNING')
    expect(wrapper.get('.tl__item').text()).toContain('续订点 evt-42')
    expect(wrapper.get('.tl__events').text()).toContain('1 事件')
    handlers.onNamed?.('error', 'worker lost')
    handlers.onDone?.('error')
    await settle()
    expect(wrapper.get('.tl__error').text()).toContain('worker lost')
    expect(timelineButton(wrapper, 'SSE 订阅').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('卸载中止所有仍登记的流 handle', async () => {
    const abort = vi.fn()
    mocks.stream.mockReturnValue({ abort })
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.stream' }, ...opts })
    seed(wrapper)
    await settle()
    await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    wrapper.unmount()
    expect(abort).toHaveBeenCalledOnce()
  })

  it('deadletter 数组/信封渲染表，空数组渲染空态，未知对象走 raw fallback', async () => {
    for (const [data, expected] of [
      [{ items: [{ id: 'd1', taskId: 't1' }] }, 'd1'],
      [[], '死信队列为空'],
      [{ count: 9 }, 'count'],
    ] as const) {
      setupCatalog()
      const fetchMock = vi.fn().mockResolvedValue(jsonResponse(data))
      vi.stubGlobal('fetch', fetchMock)
      const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks' }, ...opts })
      await buttonByText(wrapper, '加载死信').trigger('click')
      await settle()
      expect(fetchMock.mock.calls[0][0]).toBe('/async/webhook-outbox/dead')
      expect(wrapper.text()).toContain(expected)
      wrapper.unmount()
      vi.unstubAllGlobals()
    }
  })

  it('issue-16 回归：连点刷新时旧响应晚到不得覆盖新状态', async () => {
    let resolveOld!: (r: Response) => void
    const oldPending = new Promise<Response>((resolve) => { resolveOld = resolve })
    const fetchMock = vi.fn()
      .mockReturnValueOnce(oldPending)
      .mockResolvedValueOnce(jsonResponse({ taskId: 't-1', status: 'SUCCEEDED', kind: 'agent.run' }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.get' }, ...opts })
    seed(wrapper)
    await settle()
    await timelineButton(wrapper, '刷新').trigger('click')
    await timelineButton(wrapper, '刷新').trigger('click')
    await settle()
    expect(wrapper.get('.tl__item').text()).toContain('SUCCEEDED')
    resolveOld(jsonResponse({ taskId: 't-1', status: 'RUNNING', kind: 'agent.run' }))
    await settle()
    // 旧响应（RUNNING）晚到被丢弃，终态保持 SUCCEEDED
    expect(wrapper.get('.tl__item').text()).toContain('SUCCEEDED')
    expect(wrapper.find('.tl__error').exists()).toBe(false)
    wrapper.unmount()
  })

  it('issue-04 回归：手动第二次订阅携带上次 lastEventId', async () => {
    const handlers: SseHandlers[] = []
    mocks.stream.mockImplementation((_c, _v, _ctx, h: SseHandlers) => {
      handlers.push(h)
      return { abort: vi.fn() }
    })
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.stream' }, ...opts })
    seed(wrapper)
    await settle()
    await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    handlers[0].onEvent?.({ event: 'RUNNING', id: '42', data: '{}' })
    handlers[0].onDone?.('complete')
    await settle()
    await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    const futureOptions = (mocks.stream.mock.calls[1] as unknown[])[4] as { lastEventId?: string }
    expect(futureOptions.lastEventId).toBe('42')
    wrapper.unmount()
  })

  it('issue-05 回归：旧订阅迟到 onDone 不能关闭新订阅', async () => {
    const handlers: SseHandlers[] = []
    mocks.stream.mockImplementation((_c, _v, _ctx, h: SseHandlers) => {
      handlers.push(h)
      return { abort: vi.fn() }
    })
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.stream' }, ...opts })
    seed(wrapper)
    await settle()
    await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    handlers[0].onDone?.('complete')
    await settle()
    await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    handlers[0].onDone?.('abort')
    await settle()
    expect(timelineButton(wrapper, '流式中').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('issue-06 回归：单任务事件缓存有界并标记丢弃数', async () => {
    let handlers!: SseHandlers
    mocks.stream.mockImplementation((_c, _v, _ctx, h: SseHandlers) => {
      handlers = h
      return { abort: vi.fn() }
    })
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.stream' }, ...opts })
    seed(wrapper)
    await settle()
    await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    for (let i = 0; i < 2005; i += 1) handlers.onEvent?.({ event: 'RUNNING', id: String(i), data: '{}' })
    await settle()
    expect(wrapper.get('.tl__events').text()).toContain('2000 事件')
    expect(wrapper.get('.tl__events').text()).toContain('已丢弃 5')
    wrapper.unmount()
  })
})
