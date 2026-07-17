import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ChatConsoleView from './ChatConsoleView.vue'
import { buttonByText, cleanup, jsonResponse, setupCatalog, sseResponse } from '../../test/interactionHarness'

/**
 * Chat 控制台交互测试：参数映射→请求→气泡状态机→SSE 终态→画像读写。
 * 疑似 bug（issue-10/11）以 skip+期望行为呈现，不锁定错误现状。
 */

async function settle(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  await flushPromises()
}

function mode(wrapper: ReturnType<typeof mount>, label: string) {
  const value = wrapper.findAll('.chat__mode').find((b) => b.text().includes(label))
  if (!value) throw new Error(`missing mode ${label}`)
  return value
}

describe('ChatConsoleView interaction', () => {
  beforeEach(() => {
    setupCatalog()
    vi.stubGlobal('crypto', {
      ...globalThis.crypto,
      randomUUID: () => '12345678-abcd-ef00-1111-222222222222',
    })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    cleanup()
  })

  it('同步：参数表单映射到 query/body，成功形成用户/助手气泡并消毒 HTML', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ reply: '<img src=x onerror="boom()">**ok**' }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat', capId: 'chat.sync' } })
    await buttonByText(wrapper, '参数').trigger('click')
    const params = wrapper.findAll('.chat__param-input')
    expect((params[0].element as HTMLInputElement).value).toBe('web-12345678')
    await params[1].setValue(' policy ')
    await wrapper.get('textarea[placeholder*="输入消息"]').setValue(' hello ')
    await buttonByText(wrapper, '发送').trigger('click')
    await settle()

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/chat?chatId=web-12345678')
    expect(JSON.parse(String(init.body))).toEqual({ message: 'hello', category: 'policy' })
    expect(wrapper.findAll('.msg--user')).toHaveLength(1)
    expect(wrapper.findAll('.msg--assistant')).toHaveLength(1)
    expect(wrapper.get('.msg__md').text()).toContain('ok')
    // 无条件强断言：消毒后不得残留任何 on* 事件 handler 或 script 节点。
    expect(wrapper.find('.msg__md [onerror]').exists()).toBe(false)
    expect(wrapper.find('.msg__md script').exists()).toBe(false)
    expect(wrapper.text()).toContain('trace trace-12')
    wrapper.unmount()
  })

  it('流式：跨 chunk token、护栏 note 与 done 分别进入助手/系统消息', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'data: hel',
      'lo\n\nevent: blocked\ndata: unsafe\n\n',
      'data: world\n\nevent: done\ndata: {}\n\n',
    ])))
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    await wrapper.get('.chat__textarea').setValue('stream me')
    await buttonByText(wrapper, '发送').trigger('click')
    await settle()
    expect(wrapper.get('.msg--assistant').text()).toContain('helloworld')
    expect(wrapper.get('.msg--system').text()).toContain('unsafe')
    expect(wrapper.find('.msg--streaming').exists()).toBe(false)
    wrapper.unmount()
  })

  it('HTTP 失败把助手气泡置 error，不残留「等待响应」或 markdown success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ message: 'invalid category' }, 400)))
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat', capId: 'chat.sync' } })
    await wrapper.get('.chat__textarea').setValue('x')
    await buttonByText(wrapper, '发送').trigger('click')
    await settle()
    const bubble = wrapper.get('.msg--assistant')
    expect(bubble.classes()).toContain('msg--error')
    expect(bubble.text()).toContain('invalid category')
    expect(bubble.text()).not.toContain('等待响应')
    wrapper.unmount()
  })

  it('停止流会中止 signal 并把部分 token 标记为已中断', async () => {
    const encoder = new TextEncoder()
    let controller!: ReadableStreamDefaultController<Uint8Array>
    const body = new ReadableStream<Uint8Array>({
      start(c) {
        controller = c
        c.enqueue(encoder.encode('data: partial\n\n'))
      },
    })
    const fetchMock = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      init.signal?.addEventListener('abort', () =>
        controller.error(new DOMException('', 'AbortError')),
      )
      return Promise.resolve(new Response(body, { status: 200 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    await wrapper.get('.chat__textarea').setValue('x')
    await buttonByText(wrapper, '发送').trigger('click')
    await settle()
    await buttonByText(wrapper, '停止').trigger('click')
    await settle()
    expect((fetchMock.mock.calls[0][1] as RequestInit).signal?.aborted).toBe(true)
    expect(wrapper.get('.msg--assistant').text()).toContain('partial')
    expect(wrapper.get('.msg--assistant').text()).toContain('已中断')
    wrapper.unmount()
  })

  it('auto/cascade/memory 模式只发送各自目录声明的参数', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ reply: 'ok' }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    for (const [label, path] of [
      ['意图路由', '/chat/auto'],
      ['级联降级', '/chat/cascade'],
      ['长期记忆', '/chat/memory'],
    ] as const) {
      await mode(wrapper, label).trigger('click')
      await wrapper.get('.chat__textarea').setValue(label)
      await buttonByText(wrapper, '发送').trigger('click')
      await settle()
      const [url, init] = fetchMock.mock.calls.at(-1)! as [string, RequestInit]
      expect(url.startsWith(path)).toBe(true)
      const body = JSON.parse(String(init.body)) as Record<string, unknown>
      expect(body.message).toBe(label)
      if (path === '/chat/cascade') expect(Object.keys(body)).toEqual(['message'])
    }
    wrapper.unmount()
  })

  it('MCP 当前 flag-off：composer 禁用且点击/快捷键均 0 fetch', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    await mode(wrapper, 'MCP 工具').trigger('click')
    await wrapper.get('.chat__textarea').setValue('call tool')
    expect(buttonByText(wrapper, '发送').attributes('disabled')).toBeDefined()
    await wrapper.get('.chat__textarea').trigger('keydown', { ctrlKey: true, key: 'Enter' })
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.get('.chat__gate').text()).toContain('app.conversation.mcp.enabled')
    wrapper.unmount()
  })

  it('画像 GET/DELETE 使用当前 chatId，并区分空画像、成功清除与错误', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ profile: ['vip'] }))
      .mockResolvedValueOnce(jsonResponse({ cleared: true }))
      .mockResolvedValueOnce(jsonResponse(null))
      .mockResolvedValueOnce(jsonResponse({ message: 'profile backend down' }, 500))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    await mode(wrapper, '长期记忆').trigger('click')
    await buttonByText(wrapper, '长期用户画像').trigger('click')
    await buttonByText(wrapper, '查看画像').trigger('click')
    await settle()
    expect(fetchMock.mock.calls[0][0]).toBe('/memory/profile?chatId=web-12345678')
    expect(wrapper.get('.chat__memory-pre').text()).toContain('vip')
    await buttonByText(wrapper, '清除画像').trigger('click')
    await settle()
    expect((fetchMock.mock.calls[1][1] as RequestInit).method).toBe('DELETE')
    expect(wrapper.get('.chat__memory-pre').text()).toContain('已清除')
    // null 响应 → 诚实的「空画像」占位，而非崩溃或空白。
    await buttonByText(wrapper, '查看画像').trigger('click')
    await settle()
    expect(wrapper.get('.chat__memory-pre').text()).toContain('空画像')
    // 500 → memError 以 role=alert 呈现，不伪装成功。
    await buttonByText(wrapper, '查看画像').trigger('click')
    await settle()
    expect(wrapper.get('.chat__memory-body [role="alert"]').text()).toContain('profile backend down')
    wrapper.unmount()
  })

  it('issue-10 回归：业务 error 保留已收 token，并另显示错误', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'data: partial answer\n\n',
      'event: error\ndata: tool failed\n\n',
    ])))
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    await wrapper.get('.chat__textarea').setValue('x')
    await buttonByText(wrapper, '发送').trigger('click')
    await settle()
    expect(wrapper.get('.msg--assistant').text()).toContain('partial answer')
    expect(wrapper.get('.msg--system').text()).toContain('tool failed')
    wrapper.unmount()
  })

  it('issue-11 回归：清空换 chatId 后清理旧画像且旧请求不能晚到', async () => {
    let resolveProfile!: (r: Response) => void
    const pending = new Promise<Response>((resolve) => { resolveProfile = resolve })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ profile: 'old' }))
      .mockResolvedValueOnce(jsonResponse({ reply: 'ok' }))
      .mockReturnValueOnce(pending)
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    await mode(wrapper, '长期记忆').trigger('click')
    await buttonByText(wrapper, '长期用户画像').trigger('click')
    await buttonByText(wrapper, '查看画像').trigger('click')
    await settle()
    expect(wrapper.get('.chat__memory-pre').text()).toContain('old')
    await wrapper.get('.chat__textarea').setValue('seed')
    await buttonByText(wrapper, '发送').trigger('click')
    await settle()
    // 发起一个 pending 的画像请求，随后清空（换 chatId）——旧请求晚到不得写入新会话抽屉。
    await buttonByText(wrapper, '查看画像').trigger('click')
    await buttonByText(wrapper, '清空').trigger('click')
    expect(wrapper.find('.chat__memory-pre').exists()).toBe(false)
    resolveProfile(jsonResponse({ profile: 'stale-late' }))
    await settle()
    expect(wrapper.find('.chat__memory-pre').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('stale-late')
    wrapper.unmount()
  })
})
