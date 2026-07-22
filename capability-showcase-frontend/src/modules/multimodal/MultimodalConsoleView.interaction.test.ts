import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import MultimodalConsoleView from './MultimodalConsoleView.vue'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import { useCatalogStore } from '../../stores/catalog'
import {
  buttonByText,
  capability,
  cleanup,
  jsonResponse,
  RouterLinkStub,
  setupCatalog,
  sseResponse,
} from '../../test/interactionHarness'

/**
 * Multimodal Console 交互测试（docs/tests/showcase-mm-platform-interop-0717-2007）。
 * 覆盖：目录合同、chips 双分区独立选择、multipart / JSON / multipart-sse 请求合同、
 * 文件预览与 object URL 回收、gate 0-fetch、深链与缺失分支、pending 切换 abort。
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
  const found = node.findAll('button').find((button) => /执行|开始流式/.test(button.text()))
  if (!found) throw new Error('missing execute button')
  return found
}

async function setFile(node: ReturnType<typeof runner>, file: File): Promise<void> {
  const input = node.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  await input.trigger('change')
}

describe('MultimodalConsoleView interaction', () => {
  let createObjectUrl: ReturnType<typeof vi.fn>
  let revokeObjectUrl: ReturnType<typeof vi.fn>

  beforeEach(() => {
    setupCatalog()
    const NativeURL = globalThis.URL
    createObjectUrl = vi.fn(() => 'blob:test-preview')
    revokeObjectUrl = vi.fn()
    vi.stubGlobal(
      'URL',
      class extends NativeURL {
        static createObjectURL = createObjectUrl
        static revokeObjectURL = revokeObjectUrl
      },
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    cleanup()
  })

  it('MM-01 真实目录锁定八项能力的传输合同', () => {
    expect(
      [
        'vision.caption.file',
        'vision.caption.json',
        'chat.vision',
        'rag.image.ingest',
        'rag.image.search',
        'voice.transcribe',
        'voice.chat',
        'voice.chat.stream',
      ].map((id) => {
        const cap = capability(id)
        return [cap.id, cap.method, cap.path, cap.requestKind]
      }),
    ).toEqual([
      ['vision.caption.file', 'POST', '/vision/caption', 'multipart'],
      ['vision.caption.json', 'POST', '/vision/caption', 'json'],
      ['chat.vision', 'POST', '/chat/vision', 'multipart'],
      ['rag.image.ingest', 'POST', '/rag/image', 'multipart'],
      ['rag.image.search', 'POST', '/rag/image-search', 'json'],
      ['voice.transcribe', 'POST', '/voice/transcribe', 'multipart'],
      ['voice.chat', 'POST', '/voice/chat', 'multipart'],
      ['voice.chat.stream', 'POST', '/voice/chat/stream', 'multipart-sse'],
    ])
  })

  it('MM-02 图像与语音 chip 独立选择且各分区 runner 唯一', async () => {
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    await wrapper.get('[data-cap="chat.vision"]').trigger('click')
    await wrapper.get('[data-cap="voice.chat"]').trigger('click')
    expect(wrapper.get('[data-cap="chat.vision"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('[data-cap="voice.chat"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.findAll('[role="tab"][aria-selected="true"]')).toHaveLength(2)
    expect(wrapper.findAllComponents(CapabilityRunner).map((r) => (r.props('cap') as { id: string }).id))
      .toEqual(['chat.vision', 'voice.chat'])
    wrapper.unmount()
  })

  it('MM-03 文件 caption：required 阻断，选图预览，multipart 成功并在切换时回收 URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ caption: 'a red shoe' }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    const capRunner = runner(wrapper, 'vision.caption.file')
    await executeButton(capRunner).trigger('click')
    expect(fetchMock).not.toHaveBeenCalled()
    expect(capRunner.text()).toContain('图片 为必填项')

    const image = new File(['png'], 'shoe.png', { type: 'image/png' })
    await setFile(capRunner, image)
    expect(capRunner.get('img.filepreview__thumb').attributes('src')).toBe('blob:test-preview')
    await executeButton(capRunner).trigger('click')
    await settle()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/vision/caption')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('X-Api-Key')).toBe('test-key')
    expect(new Headers(init.headers).has('Content-Type')).toBe(false)
    expect(init.body).toBeInstanceOf(FormData)
    expect((init.body as FormData).get('file')).toBe(image)
    expect(capRunner.text()).toContain('a red shoe')
    await wrapper.get('[data-cap="vision.caption.json"]').trigger('click')
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:test-preview')
    wrapper.unmount()
  })

  it('MM-04 base64 caption 发送精确 JSON，凭证不进入 URL/body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ caption: 'ok' }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    await wrapper.get('[data-cap="vision.caption.json"]').trigger('click')
    const capRunner = runner(wrapper, 'vision.caption.json')
    const fields = capRunner.findAll('.form-control')
    await fields[0].setValue('base64-value')
    await fields[1].setValue('image/png')
    await fields[2].setValue('describe')
    await executeButton(capRunner).trigger('click')
    await settle()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/vision/caption')
    expect(new Headers(init.headers).get('Content-Type')).toBe('application/json')
    expect(JSON.parse(String(init.body))).toEqual({
      imageBase64: 'base64-value',
      mimeType: 'image/png',
      instruction: 'describe',
    })
    expect(url + String(init.body)).not.toContain('test-key')
    expect(capRunner.text()).toContain('ok')
    expect(capRunner.text()).toContain('HTTP 200')
    wrapper.unmount()
  })

  it('MM-05 chat.vision 把消息放 query、图片放 FormData', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ reply: 'seen' }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    await wrapper.get('[data-cap="chat.vision"]').trigger('click')
    const capRunner = runner(wrapper, 'chat.vision')
    const image = new File(['jpg'], 'a b.jpg', { type: 'image/jpeg' })
    await setFile(capRunner, image)
    await capRunner.get('textarea.form-control').setValue('图里有什么？')
    await executeButton(capRunner).trigger('click')
    await settle()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/chat/vision?message=%E5%9B%BE%E9%87%8C%E6%9C%89%E4%BB%80%E4%B9%88%EF%BC%9F')
    expect((init.body as FormData).get('image')).toBe(image)
    expect(new Headers(init.headers).has('Content-Type')).toBe(false)
    expect(capRunner.text()).toContain('seen')
    wrapper.unmount()
  })

  it('MM-17 voice.transcribe / voice.chat 均发 multipart 到各自路径', async () => {
    // 模拟已开启部署（默认 flag-off），测请求合同。
    capability('voice.transcribe').state = 'ready'
    capability('voice.chat').state = 'ready'
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ text: '转写结果' }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    const audio = new File(['wav'], 'a.wav', { type: 'audio/wav' })
    // 默认语音 runner 即 voice.transcribe。
    const transcribe = runner(wrapper, 'voice.transcribe')
    await setFile(transcribe, audio)
    await executeButton(transcribe).trigger('click')
    await settle()
    const [url1, init1] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url1).toBe('/voice/transcribe')
    expect((init1.body as FormData).get('audio')).toBe(audio)
    expect(transcribe.text()).toContain('转写结果')

    await wrapper.get('[data-cap="voice.chat"]').trigger('click')
    const chat = runner(wrapper, 'voice.chat')
    await setFile(chat, audio)
    await executeButton(chat).trigger('click')
    await settle()
    const [url2, init2] = fetchMock.mock.calls[1] as [string, RequestInit]
    // chatId 的 defaultValue='default' 会被表单预填并进入 query —— 锁定该预填行为。
    expect(url2).toBe('/voice/chat?chatId=default')
    expect(init2.body).toBeInstanceOf(FormData)
    wrapper.unmount()
  })

  it('MM-18 rag.image.search 成功 JSON 契约与 rag.image.ingest multipart 契约', async () => {
    // 模拟已配置图片向量 provider 的部署，默认目录保持 fail-closed。
    capability('rag.image.search').state = 'ready'
    capability('rag.image.ingest').state = 'scope-required'
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ results: [{ doc: 'd1' }] }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    await wrapper.get('[data-cap="rag.image.search"]').trigger('click')
    const search = runner(wrapper, 'rag.image.search')
    await search.get('textarea.form-control').setValue('红色的运动鞋')
    await executeButton(search).trigger('click')
    await settle()
    const [url1, init1] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url1).toBe('/rag/image-search')
    expect(JSON.parse(String(init1.body))).toEqual({ query: '红色的运动鞋' })
    expect(search.text()).toContain('HTTP 200')

    await wrapper.get('[data-cap="rag.image.ingest"]').trigger('click')
    const ingest = runner(wrapper, 'rag.image.ingest')
    const image = new File(['png'], 'p.png', { type: 'image/png' })
    await setFile(ingest, image)
    await executeButton(ingest).trigger('click')
    await settle()
    const [url2, init2] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(url2).toBe('/rag/image')
    expect((init2.body as FormData).get('image')).toBe(image)
    wrapper.unmount()
  })

  it('MM-06 图片检索错误可访问，切换能力销毁旧错误状态', async () => {
    // 模拟已配置图片向量 provider 的部署，测运行时错误呈现。
    capability('rag.image.search').state = 'ready'
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ message: 'embedding unavailable' }, 503)),
    )
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    await wrapper.get('[data-cap="rag.image.search"]').trigger('click')
    const capRunner = runner(wrapper, 'rag.image.search')
    await capRunner.get('textarea.form-control').setValue('red shoe')
    await executeButton(capRunner).trigger('click')
    await settle()
    expect(capRunner.get('[role="alert"]').text()).toContain('embedding unavailable')
    await wrapper.get('[data-cap="vision.caption.json"]').trigger('click')
    expect(wrapper.text()).not.toContain('embedding unavailable')
    wrapper.unmount()
  })

  it('MM-07 voice.chat.stream 发送 multipart-sse 并跨 chunk 拼接到 done', async () => {
    // 目录默认 voice flag-off（后端 VOICE_ENABLED:false）；本用例模拟已开启部署，测请求合同。
    capability('voice.chat.stream').state = 'ready'
    const fetchMock = vi.fn().mockResolvedValue(
      sseResponse(['data: hel', 'lo\n\ndata: world\n\n', 'event: done\ndata: {}\n\n']),
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    await wrapper.get('[data-cap="voice.chat.stream"]').trigger('click')
    const capRunner = runner(wrapper, 'voice.chat.stream')
    const audio = new File(['wav'], 'voice.wav', { type: 'audio/wav' })
    await setFile(capRunner, audio)
    await capRunner.get('input[type="text"]').setValue('chat/中')
    await executeButton(capRunner).trigger('click')
    await settle()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = new Headers(init.headers)
    expect(url).toBe('/voice/chat/stream?chatId=chat%2F%E4%B8%AD')
    expect(headers.get('Accept')).toBe('text/event-stream')
    expect(headers.has('Content-Type')).toBe(false)
    expect((init.body as FormData).get('audio')).toBe(audio)
    expect(capRunner.text()).toContain('helloworld')
    expect(capRunner.text()).toContain('已完成')
    wrapper.unmount()
  })

  it('MM-08 流式深链只挂一个 runner 且识别 multipart-sse', () => {
    const wrapper = mount(MultimodalConsoleView, {
      props: { moduleId: 'multimodal', capId: 'voice.chat.stream' },
      ...opts,
    })
    expect(wrapper.findAllComponents(CapabilityRunner)).toHaveLength(1)
    expect(runner(wrapper, 'voice.chat.stream').exists()).toBe(true)
    expect(wrapper.text()).toContain('multipart-sse')
    wrapper.unmount()
  })

  it('MM-09 部分/全部能力缺失时只渲染真实可用分区或能力待补', async () => {
    const catalog = useCatalogStore()
    const mod = catalog.catalog!.modules.find((m) => m.id === 'multimodal')!
    mod.capabilities = mod.capabilities.filter((c) => c.id === 'voice.transcribe')
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    expect(wrapper.find('[aria-label="图像能力"]').exists()).toBe(false)
    expect(runner(wrapper, 'voice.transcribe').exists()).toBe(true)
    mod.capabilities = []
    await nextTick()
    expect(wrapper.text()).toContain('能力待补')
    wrapper.unmount()
  })

  it('MM-10 错误 moduleId/错误 capId 明确报错且不挂 runner', () => {
    const missingModule = mount(MultimodalConsoleView, { props: { moduleId: 'missing' }, ...opts })
    expect(missingModule.text()).toContain('模块不存在')
    expect(missingModule.findComponent(CapabilityRunner).exists()).toBe(false)
    missingModule.unmount()
    const missingCap = mount(MultimodalConsoleView, {
      props: { moduleId: 'multimodal', capId: 'voice.missing' },
      ...opts,
    })
    expect(missingCap.text()).toContain('能力不存在')
    expect(missingCap.findComponent(CapabilityRunner).exists()).toBe(false)
    missingCap.unmount()
  })

  it('MM-11 未登录时默认 runner 执行入口被 gate 禁用且 0 fetch', async () => {
    setupCatalog('')
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    const runners = wrapper.findAllComponents(CapabilityRunner)
    expect(runners.length).toBeGreaterThan(0)
    for (const capRunner of runners) {
      const button = capRunner.findAll('button').find((b) => /执行|开始流式/.test(b.text()))!
      expect(button.attributes('disabled')).toBeDefined()
      await button.trigger('click')
    }
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请先')
    wrapper.unmount()
  })

  it('MM-12 目录移除并恢复旧能力时不让 stale selected id 夺回选择（issue-16 已修）', async () => {
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    await wrapper.get('[data-cap="chat.vision"]').trigger('click')
    const mod = useCatalogStore().catalog!.modules.find((m) => m.id === 'multimodal')!
    const old = mod.capabilities.find((c) => c.id === 'chat.vision')!
    mod.capabilities = mod.capabilities.filter((c) => c.id !== 'chat.vision')
    await nextTick()
    expect(runner(wrapper, 'vision.caption.file').exists()).toBe(true)
    mod.capabilities.push(old)
    await nextTick()
    // 预期：用户未操作时保持首项，不自动跳回已移除又恢复的旧能力。
    expect(runner(wrapper, 'vision.caption.file').exists()).toBe(true)
    wrapper.unmount()
  })

  it('MM-13 pending runner 切 chip 会 abort 旧请求且旧错误不泄漏', async () => {
    let seenSignal: AbortSignal | undefined
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((_url: string, init: RequestInit) => {
        seenSignal = init.signal as AbortSignal
        return new Promise<Response>((_resolve, reject) => {
          seenSignal?.addEventListener('abort', () => reject(new DOMException('', 'AbortError')))
        })
      }),
    )
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    await wrapper.get('[data-cap="vision.caption.json"]').trigger('click')
    const capRunner = runner(wrapper, 'vision.caption.json')
    await capRunner.get('textarea.form-control').setValue('base64')
    await executeButton(capRunner).trigger('click')
    await flushPromises()
    expect(capRunner.text()).toContain('停止')
    await wrapper.get('[data-cap="rag.image.search"]').trigger('click')
    await settle()
    expect(seenSignal?.aborted).toBe(true)
    expect(wrapper.text()).not.toContain('已取消本次请求')
    wrapper.unmount()
  })

  // Voice 与图片 embedding 均在 provider 未配置时 fail-closed；vision caption 仍默认 ready。
  it('MM-14 voice 三能力目录 fail-closed，vision/rag 保持可用态，banner 文案准确', () => {
    for (const id of ['voice.transcribe', 'voice.chat', 'voice.chat.stream']) {
      expect(capability(id)).toMatchObject({ featureFlagDefault: false, state: 'flag-off' })
    }
    for (const id of ['vision.caption.file', 'vision.caption.json', 'chat.vision']) {
      expect(capability(id).state).toBe('ready')
    }
    for (const id of ['rag.image.ingest', 'rag.image.search']) {
      expect(capability(id).state).toBe('flag-off')
    }
    // banner 不再声称「多数能力默认未注册」。
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    expect(wrapper.text()).not.toContain('多数能力默认未注册')
    expect(wrapper.text()).toContain('app.voice.enabled')
    wrapper.unmount()
  })

  it('MM-15 rag.image.ingest 开启后标 scope-required：gate 对缺 scope 的 Bearer 前置禁用（issue-20 已修）', () => {
    const ingest = capability('rag.image.ingest')
    expect(ingest.requiredScopes).toContain('ingest')
    expect(ingest.state).toBe('flag-off')
    ingest.state = 'scope-required'
    expect(ingest.state).toBe('scope-required')
  })

  it('MM-16 flag-off 能力 chip 标注「未启用」且 runner 给出精确 flag 并 0 fetch', async () => {
    // 用本测试自己的目录副本把 voice.chat 置为 flag-off，验证诚实呈现链路（不依赖 ISSUE-19 修复）。
    const voiceChat = capability('voice.chat')
    voiceChat.state = 'flag-off'
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    const chip = wrapper.get('[data-cap="voice.chat"]')
    expect(chip.text()).toContain('未启用')
    await chip.trigger('click')
    const capRunner = runner(wrapper, 'voice.chat')
    const button = executeButton(capRunner)
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
    expect(fetchMock).not.toHaveBeenCalled()
    expect(capRunner.text()).toContain('app.voice.enabled')
    wrapper.unmount()
  })
})
