import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ChannelConsoleView from './ChannelConsoleView.vue'
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
 * Channel Console 交互测试（docs/tests/showcase-mm-platform-interop-0717-2007）。
 * 覆盖：能力发现（gate/探测/空态/兜底/错误恢复）、出站 display-only 二次确认合同、
 * 回调桥 header 注入与凭证隔离、入站深链、缺失分支。
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

function runner(wrapper: ReturnType<typeof mount>, id: string) {
  const found = wrapper
    .findAllComponents(CapabilityRunner)
    .find((node) => (node.props('cap') as { id: string }).id === id)
  if (!found) throw new Error(`missing runner ${id}`)
  return found
}

function executeButton(node: ReturnType<typeof runner>) {
  const found = node.findAll('button').find((b) => /执行|开始流式/.test(b.text()))
  if (!found) throw new Error('missing execute button')
  return found
}

describe('ChannelConsoleView interaction', () => {
  beforeEach(() => setupCatalog())
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    cleanup()
  })

  it('CH-01 未登录时发现按钮禁用、提示登录且 0 fetch', async () => {
    setupCatalog('')
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    const button = buttonByText(wrapper, '发现渠道')
    expect(button.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('请先登录')
    await button.trigger('click')
    expect(fetchMock).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('CH-02 discover 精确 GET 并解析 label/detail，不把凭证写入数据区', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        channels: [
          {
            channel: 'feishu',
            type: 'bot',
            provider: 'open-feishu',
            enabled: true,
            target: 'ops',
            description: 'ops channel',
          },
        ],
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    await buttonByText(wrapper, '发现渠道').trigger('click')
    await settle()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/channel/capabilities')
    expect(init.method).toBe('GET')
    expect(init.body).toBeUndefined()
    expect(new Headers(init.headers).get('X-Api-Key')).toBe('test-key')
    expect(wrapper.get('.ch__channel-name').text()).toBe('feishu')
    expect(wrapper.get('.ch__channel-detail').text()).toContain('type: bot')
    expect(wrapper.get('.ch__channel-detail').text()).toContain('enabled: true')
    expect(wrapper.text()).not.toContain('test-key')
    wrapper.unmount()
  })

  it.each([
    ['root', (items: unknown[]) => items],
    ['channels', (items: unknown[]) => ({ channels: items })],
    ['capabilities', (items: unknown[]) => ({ capabilities: items })],
    ['configured', (items: unknown[]) => ({ configured: items })],
    ['data', (items: unknown[]) => ({ data: items })],
    ['items', (items: unknown[]) => ({ items })],
    ['results', (items: unknown[]) => ({ results: items })],
  ])('CH-03 parseChannels 探测 %s 并兼容 name/id/type/provider/primitive', async (_key, envelope) => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(envelope([{ name: 'n1' }, { id: 2 }, { type: 'voice' }, { provider: 'webhook' }, 'plain'])),
      ),
    )
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    await buttonByText(wrapper, '发现渠道').trigger('click')
    await settle()
    expect(wrapper.findAll('.ch__channel-name').map((n) => n.text())).toEqual([
      'n1',
      '2',
      'voice',
      'webhook',
      'plain',
    ])
    wrapper.unmount()
  })

  it('CH-04 空列表与不可解析响应分别显示 EmptyState/ResponseViewer', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ channels: [] }))
      .mockResolvedValueOnce(jsonResponse({ service: 'channel', status: 'unknown-shape' }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    await buttonByText(wrapper, '发现渠道').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('没有已配置渠道')
    await buttonByText(wrapper, '重新发现').trigger('click')
    await settle()
    expect(wrapper.get('.ch__fallback').text()).toContain('unknown-shape')
    wrapper.unmount()
  })

  it('CH-05 busy 期间禁用，HTTP 错误 finally 复位，重试清错', async () => {
    const pending = deferred<Response>()
    const fetchMock = vi.fn()
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(jsonResponse({ channels: ['webhook'] }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    const button = buttonByText(wrapper, '发现渠道')
    await button.trigger('click')
    await flushPromises()
    expect(button.text()).toContain('发现中')
    expect(button.attributes('disabled')).toBeDefined()
    pending.resolve(jsonResponse({ message: 'channel down' }, 503))
    await settle()
    expect(wrapper.get('[role="alert"]').text()).toContain('channel down')
    expect(buttonByText(wrapper, '重新发现').attributes('disabled')).toBeUndefined()
    await buttonByText(wrapper, '重新发现').trigger('click')
    await settle()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('.ch__channel-name').text()).toBe('webhook')
    wrapper.unmount()
  })

  it('CH-06 出站 display-only 未确认 0 fetch，确认后发送精确 JSON', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ status: 'ACCEPTED' }, 202))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChannelConsoleView, {
      props: { moduleId: 'channel', capId: 'channel.messages.send' },
      ...opts,
    })
    const capRunner = runner(wrapper, 'channel.messages.send')
    const execute = executeButton(capRunner)
    expect(execute.attributes('disabled')).toBeDefined()
    await execute.trigger('click')
    expect(fetchMock).not.toHaveBeenCalled()
    // 「钉钉」为目录 examples 预设 chip：一键载入 channel/target/message 示例值。
    await buttonByText(capRunner, '钉钉').trigger('click')
    await capRunner.get('input[type="checkbox"]').setValue(true)
    expect(execute.attributes('disabled')).toBeUndefined()
    await execute.trigger('click')
    await settle()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/channel/messages')
    expect(JSON.parse(String(init.body))).toEqual({
      channel: 'dingtalk',
      target: 'chat-123',
      message: '你好',
    })
    expect(wrapper.text()).toContain('HTTP 202')
    wrapper.unmount()
  })

  it('CH-07 两个回调 runner 注入各自业务 header，API key 仍来自会话且 curl 不泄密', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ accepted: true }, 202))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    const asyncRunner = runner(wrapper, 'channel.callbacks.async-task')
    const asyncInputs = asyncRunner.findAll('input[type="text"]')
    await asyncInputs[0].setValue('task-1')
    await asyncInputs[1].setValue('SUCCEEDED')
    await buttonByText(asyncRunner, '预览 curl').trigger('click')
    expect(asyncRunner.get('.runner__curl-code').text()).toContain('X-Async-Task-Id: task-1')
    expect(asyncRunner.get('.runner__curl-code').text()).toContain('X-Api-Key: $API_KEY')
    expect(asyncRunner.get('.runner__curl-code').text()).not.toContain('test-key')
    await executeButton(asyncRunner).trigger('click')
    await settle()
    const asyncCall = fetchMock.mock.calls[0] as [string, RequestInit]
    const asyncHeaders = new Headers(asyncCall[1].headers)
    expect(asyncCall[0]).toBe('/channel/callbacks/async-task')
    expect(asyncHeaders.get('X-Async-Task-Id')).toBe('task-1')
    expect(asyncHeaders.get('X-Async-Task-Status')).toBe('SUCCEEDED')
    expect(asyncHeaders.get('X-Api-Key')).toBe('test-key')
    // 本用例只锁 header 注入合同；body 缺失是 ISSUE-01（catalog 缺 body 参数），由 CH-10 挂账，
    // 不在此断言 body === undefined 以免把疑似 bug 锁成正确行为。
    expect(asyncRunner.text()).toContain('HTTP 202')

    const workflowRunner = runner(wrapper, 'channel.callbacks.workflow')
    const workflowInputs = workflowRunner.findAll('input[type="text"]')
    await workflowInputs[0].setValue('wf-1')
    await workflowInputs[1].setValue('COMPLETED')
    await executeButton(workflowRunner).trigger('click')
    await settle()
    const workflowCall = fetchMock.mock.calls[1] as [string, RequestInit]
    const workflowHeaders = new Headers(workflowCall[1].headers)
    expect(workflowCall[0]).toBe('/channel/callbacks/workflow')
    expect(workflowHeaders.get('X-Workflow-Instance-Id')).toBe('wf-1')
    expect(workflowHeaders.get('X-Workflow-Status')).toBe('COMPLETED')
    expect(workflowRunner.text()).toContain('HTTP 202')
    wrapper.unmount()
  })

  it('CH-08 inbound 卡片深链准确，聚焦 runner 保留签名 header 字段', () => {
    const landing = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    const inbound = landing
      .findAllComponents(CapabilityCard)
      .find((card) => (card.props('cap') as { id: string }).id === 'channel.inbound')!
    expect(inbound.exists()).toBe(true)
    expect(inbound.element.getAttribute('data-to')).toBe('/m/channel/channel.inbound')
    landing.unmount()
    const focused = mount(ChannelConsoleView, {
      props: { moduleId: 'channel', capId: 'channel.inbound' },
      ...opts,
    })
    const capRunner = runner(focused, 'channel.inbound')
    // issue-01 修复后：channel/eventType 文本框 + payload textarea + 签名 header，共 3 个文本输入。
    expect(capRunner.findAll('input[type="text"]')).toHaveLength(3)
    expect(capRunner.find('textarea').exists()).toBe(true)
    expect(capRunner.text()).toContain('签名')
    expect(capRunner.text()).toContain('渠道')
    expect(capRunner.text()).toContain('事件类型')
    focused.unmount()
  })

  it('CH-09 错误 module/cap 与空目录有明确 EmptyState', () => {
    const missing = mount(ChannelConsoleView, { props: { moduleId: 'missing' }, ...opts })
    expect(missing.text()).toContain('模块不存在')
    missing.unmount()
    const cap = mount(ChannelConsoleView, {
      props: { moduleId: 'channel', capId: 'channel.missing' },
      ...opts,
    })
    expect(cap.text()).toContain('能力不存在')
    cap.unmount()
    useCatalogStore().catalog!.modules.find((m) => m.id === 'channel')!.capabilities = []
    const empty = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    expect(empty.text()).toContain('能力待补')
    empty.unmount()
  })

  it('CH-10 回调与 inbound 按后端 controller 合同发送 JSON body（issue-01 已修）', async () => {
    // 后端 ChannelController：/channel/callbacks/* 需 @RequestBody Map，/channel/inbound 需 ChannelInboundEvent。
    // catalog 现有 payload/channel/eventType body 参数；payload 留空时也会发 {}（@RequestBody 不缺体）。
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ accepted: true }, 202))
    vi.stubGlobal('fetch', fetchMock)
    const callback = mount(ChannelConsoleView, {
      props: { moduleId: 'channel', capId: 'channel.callbacks.async-task' },
      ...opts,
    })
    await callback.get('textarea').setValue('{"orderNo":"A100"}')
    await executeButton(runner(callback, 'channel.callbacks.async-task')).trigger('click')
    await settle()
    const [, cbInit] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(cbInit.headers).get('Content-Type')).toBe('application/json')
    expect(JSON.parse(String(cbInit.body))).toEqual({ payload: { orderNo: 'A100' } })
    callback.unmount()

    // payload 留空 → 仍发 JSON body {}（不再是无体请求）。
    fetchMock.mockClear()
    const emptyCallback = mount(ChannelConsoleView, {
      props: { moduleId: 'channel', capId: 'channel.callbacks.workflow' },
      ...opts,
    })
    await executeButton(runner(emptyCallback, 'channel.callbacks.workflow')).trigger('click')
    await settle()
    const [, emptyInit] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(String(emptyInit.body)).toBe('{}')
    emptyCallback.unmount()

    // inbound：channel/eventType/payload 进 body。
    fetchMock.mockClear()
    const inbound = mount(ChannelConsoleView, {
      props: { moduleId: 'channel', capId: 'channel.inbound' },
      ...opts,
    })
    const inboundRunner = runner(inbound, 'channel.inbound')
    const textInputs = inboundRunner.findAll('input[type="text"]')
    await textInputs[0].setValue('dingtalk')
    await textInputs[1].setValue('message')
    await inboundRunner.get('textarea').setValue('{"text":"你好"}')
    await executeButton(inboundRunner).trigger('click')
    await settle()
    const [inUrl, inInit] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(inUrl).toBe('/channel/inbound')
    expect(JSON.parse(String(inInit.body))).toEqual({
      channel: 'dingtalk',
      eventType: 'message',
      payload: { text: '你好' },
    })
    inbound.unmount()
  })

  it('CH-11 双击只发一次且 null 成功有明确空体终态（issue-10/12 已修）', async () => {
    const pending = deferred<Response>()
    const fetchMock = vi.fn().mockReturnValue(pending.promise)
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    const button = buttonByText(wrapper, '发现渠道')
    const first = button.trigger('click')
    const second = button.trigger('click')
    await Promise.all([first, second])
    expect(fetchMock).toHaveBeenCalledTimes(1)
    pending.resolve(jsonResponse(null))
    await settle()
    expect(wrapper.text()).toContain('成功，但响应体为空')
    // issue-14：畸形项过滤（null/空对象不产生伪渠道条目）。
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ channels: [null, {}, { name: 'ok' }] })),
    )
    await buttonByText(wrapper, '重新发现').trigger('click')
    await settle()
    expect(wrapper.findAll('.ch__channel-name').map((n) => n.text())).toEqual(['ok'])
    wrapper.unmount()
  })

  it('CH-12 凭证切换清空旧租户渠道、旧响应不回写、pending 请求被 abort（issue-11/17 已修）', async () => {
    let seenSignal: AbortSignal | undefined
    const pending = deferred<Response>()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((_url: string, init: RequestInit) => {
        seenSignal = init.signal as AbortSignal
        return pending.promise
      }),
    )
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    await buttonByText(wrapper, '发现渠道').trigger('click')
    useSessionStore().setApiKey('tenant-b-key')
    pending.resolve(jsonResponse({ channels: ['tenant-a-secret'] }))
    await settle()
    // issue-11：旧租户响应不得回写新会话。
    expect(wrapper.text()).not.toContain('tenant-a-secret')
    // issue-17：discover() 的 runContext 应带 signal，卸载时 abort pending 请求。
    wrapper.unmount()
    expect(seenSignal).toBeDefined()
    expect(seenSignal?.aborted).toBe(true)
  })
})
