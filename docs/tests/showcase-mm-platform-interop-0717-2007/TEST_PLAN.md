# TEST PLAN — Multimodal / Interop-Eval / Channel 前端交互

## 0. 结论与适用规则

本次应新增三个 Vitest interaction suite，覆盖真实目录、真实 Pinia、真实 `executionGate/runCapability/streamCapability` 调用链，并直接 stub 全局 `fetch`。现有 63/63/76 行浅测试保留，新增 suite 不替代它们。

需求末尾的 Java/AssertJ/Mockito/H2/Spring 测试铁律与开头明确的“前端 Vitest，不是 Java/Maven”互斥。为保证草案可直接落地编译，下面代码必须是 `typescript`，测试单位是 Vitest suite 而不是 Java class；若强行输出 `java` 会得到无法编译、也无法放入本项目测试栈的伪代码。Java 铁律中可迁移的精神约束已映射为：不起应用、无模块 mock、真实对象/真实目录、每例隔离与安全上下文清理、外部依赖全部确定性 stub。

## 1. 被测面与目标

详细仓库事实见 [01-scope.md](./01-scope.md)。核心目标：

- Multimodal：锁定双分区独立选择、8 项能力传输合同、文件/JSON/query、深链、缺失能力、multipart-sse、gate、错误与 pending 切换清理。
- Interop-Eval：锁定 MCP 三步串联、数组 envelope 探测、arguments/cases 校验、指标与明细探测、fallback、卡片路由、422、busy/error；将乱序和状态污染列为修复后回归。
- Channel：锁定发现探测、空/兜底/错误、出站二次确认、回调 header、inbound 深链、busy/gate；将 body 合同、租户切换与乱序列为修复后回归。
- 横切：任何 gate 拒绝必须 `fetch === 0`；API key 只进 header；不把疑似 bug 固化成 passing assertion。

受影响共享方法：`executionGate`、`runCapability`、`streamCapability`、`isStreamingKind`、`humanizeError`、`session.hasCredential/permissionContext/runContext`、`catalog.moduleById/capabilityById`。它们已有纯函数/组件测试，本计划只覆盖三视图如何集成它们。

## 2. 六视角综合策略

### 2.1 test-strategist：分层与验收标准

1. 目录合同层：从真实 `public/catalog.json` 读取能力，断 method/path/requestKind/state/params，不复制生产 fixture。
2. 视图状态层：mount SFC，断 button/aria/runner/card/empty/error/busy 的状态迁移。
3. 传输集成层：从用户点击走到全局 fetch，精确断 URL、method、headers、body/FormData/SSE；不 mock `client.ts`。
4. 边界/异常层：非法 JSON、空/畸形 envelope、null/204、HTTP/网络错误、重试。
5. 安全/租户层：无凭证 0 fetch、display-only 二次确认、header 凭证隔离、凭证切换不泄漏旧租户数据。
6. 竞态回归层：deferred 控制乱序；busy 防重；unmount/切 runner abort；旧响应不能覆盖新状态。

可验证验收标准：

- 三个新文件在默认 apikey/jsdom 配置下独立、合并运行全绿；`npm run type-check` 全绿。
- 每个成功路径至少同时断“请求合同”和“可见终态”，不是只查 fetch 次数或文案。
- 每个本地/gate 失败路径断 0 fetch；每个远端失败断 busy 解除、错误可访问、旧成功不伪装当前成功。
- 所有当前疑似错误只存在 `it.skip` + TODO，修复前不计关键缺口闭合。
- 关键安全 TODO（ISSUE-01/02/03/06/07/11/12）修复并启用后，才可宣称完整验收。

### 2.2 coverage-analyst

逐分支矩阵见 [02-coverage-matrix.md](./02-coverage-matrix.md)。本次最重要缺口是：

- 8 个多模态能力尚未进入现有 57 项 runner 合同表；multipart-sse 只测了文案。
- MCP/retrieval/discovery 的脚本内解析和状态机完全没有 interaction 覆盖。
- 无登录、错误恢复、busy、乱序、凭证/租户切换均未覆盖。
- Channel 的现有测试只确认 catalog 有 header，未验证实际 fetch header，更未发现 body 缺失。

### 2.3 interaction-logic-reviewer

疑似问题完整证据见 [03-suspected-issues.md](./03-suspected-issues.md)。高优先级摘要：

- ISSUE-01：预期回调/入站可提交必需 body；现状 catalog 只有 header，实际 body 缺失。用深链执行即可复现。应修 catalog 后启用 CH-10。
- ISSUE-02：预期 retrieval 示例匹配 protocol；现状 `query/expectedDocIds` 对不上 `question/relevantDocIds`。应修生成源后启用 IE-19。
- ISSUE-03/04/05：预期 cases 为非空结构化数组、arguments 为对象、topK 为可选 1..50 整数；现状只 parse 或依赖 HTML。应加业务校验后启用 IE-15。
- ISSUE-06/07/08：预期工具选择/详情/调用原子关联；现状可被乱序覆盖、结果错挂、reload 留 stale selection。以 deferred A/B 复现，修后启用 IE-14。
- ISSUE-09/10：预期新提交与空成功都有明确终态；现状旧指标残留或 null 结果空白。修后启用 IE-15/CH-11。
- ISSUE-11/12/17：预期凭证切换/卸载使旧请求失效且同 tick 防重；现状无 generation/abort/函数级 busy guard，存在租户串味。修后启用 IE-17/18、CH-11/12。
- ISSUE-13：预期 Bearer 错误使用登录文案；现状专用调用没有把 credential mode 传给 `humanizeError`。需 OIDC 专项验证。
- ISSUE-14/15/16/18：畸形探测项、envelope 冲突、Multimodal stale selected id、指标大小写去重均需明确契约后处理。
- ISSUE-19：预期默认关闭的多模态能力 fail-closed；现状 catalog 全部 default true/ready，与后端条件装配和页面 banner 冲突。修生成源后启用 MM-14。
- ISSUE-20：预期 `rag.image.ingest` 开启后仍做 ingest scope 裁决；现状 gate 仅在 state=scope-required 时读 requiredScopes，单一 state 又要表达 flag-off。应正交化 availability/scope，启用 MM-15。

### 2.4 edge-case-hunter

- JSON：空白、语法错、`null`、数组/对象/标量错型、空数组、数组畸形元素、超大输入（这里只验证前端不崩；性能上限待产品定义）。
- 探测：根数组、每个候选 key、前 key 空/后 key 非空、null/undefined/scalar、空对象、null 项、primitive 项、重复 name、循环对象（真实 JSON 响应不会产生循环，ResponseViewer 序列化保护另有共享测试）。
- 数值：0、1、50、51、小数、NaN 字符串、空 topK；指标 0/1/>1、数字字符串、Infinity/NaN（JSON 无法传 Infinity，但可由直接 data fixture验证过滤）。
- 文件：required 空文件、image/audio MIME、FormData 不手设 Content-Type、文件切 runner 回收 object URL、multipart-sse 跨 chunk。
- 异常：400/401/403/404/422/503、TypeError 网络错误、null/204、无 readable SSE body、abort。
- 并发：双击同动作、A/B 详情反序、reload 与旧 selection、调用中切 tool、凭证 A→B、unmount/切 chip。
- 注入：工具名含 `/ ?中#` 必须 path encode；业务 header 不得覆盖 `X-Api-Key`；API key 不进 URL/body/DOM/curl 明文。
- ThreadLocal/H2 不适用于浏览器测试；等价泄漏点是 Pinia、localStorage、global fetch/URL/crypto、DOM 和 pending promise。

### 2.5 flaky-risk-reviewer

- 每例 `setupCatalog()` 创建新 Pinia并清 localStorage；严禁共享可变 catalog。
- `afterEach` 固定 `vi.unstubAllGlobals()`、`vi.restoreAllMocks()`、`cleanup()`；每个 wrapper 尽量主动 unmount。
- fetch 优先按 URL 分派；只在相同 URL 重试时使用 `mockResolvedValueOnce`，并断调用序号。
- 竞态只用 `deferred<Response>()` 控制，不用真实时间、随机数或固定长 sleep。
- Vue/SSE 用有限次 `flushPromises` + 0ms task drain；不对 elapsedMs、trace 之外的动画/样式做脆弱断言。
- 文件测试 stub `URL.createObjectURL/revokeObjectURL`，避免 jsdom 差异并验证资源回收。
- 不使用 `vi.mock`，避免 Pinia/config/client 双实例；OIDC build-mode 行为另建受控 suite，不能污染默认 apikey suite。
- 不依赖用例执行顺序；`it.each` 每格独立 mount/unmount。

## 3. 完整可落地代码草案

### 3.1 `MultimodalConsoleView.interaction.test.ts`

精确放置路径：`capability-showcase-frontend/src/modules/multimodal/MultimodalConsoleView.interaction.test.ts`

锁定行为：请求合同断言同时覆盖 URL/method/header/body 与 UI 终态；gate/validation 断 0 fetch；SSE 断 FormData、Accept、跨 chunk token 和完成态；pending 切换断真实 AbortSignal。TODO 不接受 stale selection 或 scope 绕过。

```typescript
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import MultimodalConsoleView from './MultimodalConsoleView.vue'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import { useCatalogStore } from '../../stores/catalog'
import { useAuthStore } from '../../stores/auth'
import {
  buttonByText,
  capability,
  cleanup,
  jsonResponse,
  RouterLinkStub,
  setupCatalog,
  sseResponse,
} from '../../test/interactionHarness'

const opts = { global: { stubs: { RouterLink: RouterLinkStub } } }

async function settle(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  await flushPromises()
}

function runner(wrapper: ReturnType<typeof mount>, id: string) {
  const found = wrapper.findAllComponents(CapabilityRunner)
    .find((node) => node.props('cap').id === id)
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
    vi.stubGlobal('URL', class extends NativeURL {
      static createObjectURL = createObjectUrl
      static revokeObjectURL = revokeObjectUrl
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    cleanup()
  })

  it('MM-01 真实目录锁定八项能力的顺序与传输合同', () => {
    expect([
      'vision.caption.file', 'vision.caption.json', 'chat.vision', 'rag.image.ingest',
      'rag.image.search', 'voice.transcribe', 'voice.chat', 'voice.chat.stream',
    ].map((id) => {
      const cap = capability(id)
      return [cap.id, cap.method, cap.path, cap.requestKind]
    })).toEqual([
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

  it('MM-02 图像与语音 chip 独立选择且 aria/runner 始终唯一', async () => {
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    await wrapper.get('[data-cap="chat.vision"]').trigger('click')
    await wrapper.get('[data-cap="voice.chat"]').trigger('click')
    expect(wrapper.get('[data-cap="chat.vision"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('[data-cap="voice.chat"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.findAll('[role="tab"][aria-selected="true"]')).toHaveLength(2)
    expect(wrapper.findAllComponents(CapabilityRunner).map((r) => r.props('cap').id))
      .toEqual(['chat.vision', 'voice.chat'])
    wrapper.unmount()
  })

  it('MM-03 文件 caption：required 阻断，选图预览，multipart 成功并卸载回收 URL', async () => {
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
    expect(JSON.parse(String(init.body))).toEqual({
      imageBase64: 'base64-value', mimeType: 'image/png', instruction: 'describe',
    })
    expect(url + String(init.body)).not.toContain('test-key')
    expect(capRunner.text()).toContain('caption')
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
    wrapper.unmount()
  })

  it('MM-06 图片检索错误可访问，切换能力销毁旧错误状态', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ message: 'embedding unavailable' }, 503)))
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
    const fetchMock = vi.fn().mockResolvedValue(sseResponse([
      'data: hel',
      'lo\n\ndata: world\n\n',
      'event: done\ndata: {}\n\n',
    ]))
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

  it('MM-08 流式深链只挂一个 runner且识别 multipart-sse', () => {
    const wrapper = mount(MultimodalConsoleView, {
      props: { moduleId: 'multimodal', capId: 'voice.chat.stream' }, ...opts,
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
      props: { moduleId: 'multimodal', capId: 'voice.missing' }, ...opts,
    })
    expect(missingCap.text()).toContain('能力不存在')
    expect(missingCap.findComponent(CapabilityRunner).exists()).toBe(false)
    missingCap.unmount()
  })

  it('MM-11 未登录时所有 runner 执行入口被 gate 禁用且 0 fetch', async () => {
    setupCatalog('')
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    for (const capRunner of wrapper.findAllComponents(CapabilityRunner)) {
      const button = capRunner.findAll('button').find((b) => /执行|开始流式/.test(b.text()))!
      expect(button.attributes('disabled')).toBeDefined()
      await button.trigger('click')
    }
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请先')
    wrapper.unmount()
  })

  it.skip('TODO(issue-16) MM-12 目录移除并恢复旧能力时不应让 stale selected id 夺回选择', async () => {
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    await wrapper.get('[data-cap="chat.vision"]').trigger('click')
    const mod = useCatalogStore().catalog!.modules.find((m) => m.id === 'multimodal')!
    const old = mod.capabilities.find((c) => c.id === 'chat.vision')!
    mod.capabilities = mod.capabilities.filter((c) => c.id !== 'chat.vision')
    await nextTick()
    expect(runner(wrapper, 'vision.caption.file').exists()).toBe(true)
    mod.capabilities.push(old)
    await nextTick()
    expect(runner(wrapper, 'vision.caption.file').exists()).toBe(true)
    wrapper.unmount()
  })

  it('MM-13 pending runner 切 chip 会 abort 旧请求且旧错误不泄漏', async () => {
    let seenSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      seenSignal = init.signal as AbortSignal
      return new Promise<Response>((_resolve, reject) => {
        seenSignal?.addEventListener('abort', () => reject(new DOMException('', 'AbortError')))
      })
    }))
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

  // 【Claude 复核修正】vision/chat.vision/rag.image.* 后端默认已开（2026-07-17 yml 翻转），仅 voice 默认关。
  it.skip('TODO(issue-19) MM-14 voice 三能力必须在目录 fail-closed，其余五项保持 ready', () => {
    for (const id of ['voice.transcribe', 'voice.chat', 'voice.chat.stream']) {
      expect(capability(id)).toMatchObject({ featureFlagDefault: false, state: 'flag-off' })
    }
    for (const id of [
      'vision.caption.file', 'vision.caption.json', 'chat.vision', 'rag.image.ingest', 'rag.image.search',
    ]) {
      expect(capability(id).state).toBe('ready')
    }
  })

  it.skip('TODO(issue-20) MM-15 rag.image.ingest 启用后 Bearer 缺 ingest scope 仍禁用且 0 fetch', async () => {
    setupCatalog('')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      accessToken: 'bearer-token',
      expiresInSeconds: 3600,
      user: { username: 'alice', tenant: 'acme', scopes: ['chat'] },
    })))
    await useAuthStore().login('alice', 'pw')
    const ingest = capability('rag.image.ingest')
    ingest.state = 'ready' // 模拟 feature 已开启后的 live 状态；保留 requiredScopes=['ingest']。
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(MultimodalConsoleView, { props: { moduleId: 'multimodal' }, ...opts })
    await wrapper.get('[data-cap="rag.image.ingest"]').trigger('click')
    const execute = executeButton(runner(wrapper, 'rag.image.ingest'))
    expect(execute.attributes('disabled')).toBeDefined()
    await execute.trigger('click')
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('ingest')
    wrapper.unmount()
  })
})
```

### 3.2 `InteropEvalView.interaction.test.ts`

精确放置路径：`capability-showcase-frontend/src/modules/interop/InteropEvalView.interaction.test.ts`

锁定行为：三步链必须断三次请求的 URL/body 及选中/详情/结果；探测测试断实际渲染字段而非只断请求；retrieval 同时断输入映射、7 类指标、行与 fallback；422 同时断状态码、人话和响应体。乱序、错型、租户切换均为 TODO，防止确认偏差。

```typescript
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
      if (url === '/interop/mcp/tools') return Promise.resolve(jsonResponse({
        tools: [{ name: 'platform/ping 中', description: 'ping tool' }],
      }))
      if (url === '/interop/mcp/tools/platform%2Fping%20%E4%B8%AD') {
        return Promise.resolve(jsonResponse({ name: 'platform/ping 中', inputSchema: { type: 'object' } }))
      }
      if (url === '/interop/mcp/call') return Promise.resolve(jsonResponse({ success: true, result: 'pong' }))
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
      tool: 'platform/ping 中', arguments: { message: 'hello' },
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
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(envelope([
      { tool: 'tool-a', summary: 'summary-a' },
      { id: 'tool-b', title: 'title-b' },
    ]))))
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
    expect(wrapper.find('.ie__fallback').text()).toContain('interop')
    expect(wrapper.find('.ie__fallback').text()).toContain('version')
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
      if (url === '/interop/mcp/tools') return Promise.resolve(jsonResponse([{ name: 'platform.ping' }]))
      if (url === '/interop/mcp/tools/platform.ping') return Promise.resolve(jsonResponse({ name: 'platform.ping' }))
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
    const call = fetchMock.mock.calls.find(([url]) => url === '/interop/mcp/call')! as [string, RequestInit]
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
    await wrapper.get('[aria-label="检索评测用例 JSON"]').setValue(
      '[{"id":"c1","question":"退款","relevantDocIds":["d1"]}]',
    )
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
      'avgRecall', 'meanMrr', 'hitRate', 'avgPrecision', 'ndcg', 'f1', 'map',
    ])
    expect(wrapper.findAll('.stat__value').map((n) => n.text())).toEqual([
      '0.500', '0.250', '1.000', '0.333', '0.400', '0.200', '0.100',
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
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(responses[index++]))))
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await wrapper.get('[aria-label="检索评测用例 JSON"]').setValue(
      '[{"id":"c","question":"q","relevantDocIds":[]}]',
    )
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
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      message: 'recall regression', failed: ['c1'],
    }, 422)))
    const wrapper = mount(InteropEvalView, {
      props: { moduleId: 'interop-eval', capId: 'eval.gate' }, ...opts,
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
    const ids = wrapper.findAllComponents(CapabilityCard).map((c) => c.props('cap').id)
    expect(ids).toEqual([
      'interop.agent-card', 'interop.a2a.agent-card', 'interop.a2a.call',
      'eval.capabilities', 'eval.run', 'eval.suite.run', 'eval.dual-run', 'eval.gate',
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
      props: { moduleId: 'interop-eval', capId: 'eval.missing' }, ...opts,
    })
    expect(cap.text()).toContain('能力不存在')
    cap.unmount()
    const mod = useCatalogStore().catalog!.modules.find((m) => m.id === 'interop-eval')!
    mod.capabilities = []
    const empty = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    expect(empty.text()).toContain('能力待补')
    empty.unmount()
  })

  it.skip('TODO(issue-06/07/08) IE-14 选择/重载/调用乱序只允许最新工具状态落地', async () => {
    const a = deferred<Response>()
    const b = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      if (url === '/interop/mcp/tools') return Promise.resolve(jsonResponse([{ name: 'A' }, { name: 'B' }]))
      if (url.endsWith('/A')) return a.promise
      if (url.endsWith('/B')) return b.promise
      return Promise.resolve(jsonResponse({ result: 'A-result' }))
    }))
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await buttonByText(wrapper, '列出工具').trigger('click')
    await settle()
    void toolButton(wrapper, 'A').trigger('click')
    void toolButton(wrapper, 'B').trigger('click')
    b.resolve(jsonResponse({ marker: 'NEW-B' }))
    await settle()
    a.resolve(jsonResponse({ marker: 'STALE-A' }))
    await settle()
    expect(wrapper.text()).toContain('NEW-B')
    expect(wrapper.text()).not.toContain('STALE-A')
    wrapper.unmount()
  })

  // 【Claude 复核修正】清空 topK 发 '' 的说法不成立（buildJsonBody 丢弃 ''），已移入 IE-07 类通过态；仅保留越界值。
  it.skip('TODO(issue-03/04/05/09/10/14) IE-15 拒绝错型 JSON/越界 topK，清旧结果并处理 null 成功', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    for (const invalid of ['{}', 'null', '1', '[null]', '[]']) {
      await wrapper.get('[aria-label="检索评测用例 JSON"]').setValue(invalid)
      await runRetrievalButton(wrapper).trigger('click')
    }
    await wrapper.get('[aria-label="检索评测用例 JSON"]').setValue(
      '[{"id":"c","question":"q","relevantDocIds":[]}]',
    )
    for (const invalidTopK of ['0', '51', '1.5']) {
      await wrapper.get('.ie__retrieval input[type="number"]').setValue(invalidTopK)
      await runRetrievalButton(wrapper).trigger('click')
    }
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toMatch(/数组|TopK/)
    wrapper.unmount()
  })

  it('IE-20 清空 topK 时请求体正确省略 topK（现状即正确，锁定防回归）', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ avgRecall: 1 }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await wrapper.get('[aria-label="检索评测用例 JSON"]').setValue(
      '[{"id":"c","question":"q","relevantDocIds":[]}]',
    )
    await wrapper.get('.ie__retrieval input[type="number"]').setValue('')
    await runRetrievalButton(wrapper).trigger('click')
    await settle()
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(String(init.body))).not.toHaveProperty('topK')
    wrapper.unmount()
  })

  it.skip('TODO(issue-15) IE-16 envelope 多键冲突按已确认契约选择有效数组', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      tools: [], results: [{ name: 'real-tool' }],
    })))
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await buttonByText(wrapper, '列出工具').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('real-tool')
    wrapper.unmount()
  })

  it.skip('TODO(issue-12) IE-17 同 tick 双击只发一次且乱序 retrieval 只保留最新', async () => {
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

  it.skip('TODO(issue-11/13/17) IE-18 凭证切换清空旧租户数据并拒绝旧响应回写', async () => {
    const pending = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(pending.promise))
    const wrapper = mount(InteropEvalView, { props: { moduleId: 'interop-eval' }, ...opts })
    await buttonByText(wrapper, '列出工具').trigger('click')
    useSessionStore().setApiKey('tenant-b-key')
    pending.resolve(jsonResponse([{ name: 'tenant-a-secret-tool' }]))
    await settle()
    expect(wrapper.text()).not.toContain('tenant-a-secret-tool')
    wrapper.unmount()
  })

  it.skip('TODO(issue-02) IE-19 catalog retrieval 示例必须匹配后端 RetrievalCase record', () => {
    const cap = useCatalogStore().capabilityById('eval.retrieval')!
    const parsed = JSON.parse(cap.examples![0].body) as { cases: Record<string, unknown>[] }
    expect(Object.keys(parsed.cases[0]).sort()).toEqual(['id', 'question', 'relevantDocIds'])
  })
})
```

### 3.3 `ChannelConsoleView.interaction.test.ts`

精确放置路径：`capability-showcase-frontend/src/modules/channel/ChannelConsoleView.interaction.test.ts`

锁定行为：发现测试精确断当前租户请求及探测输出；出站测试先断未确认 0 fetch 再断确认后真实 JSON；回调同时断业务 header 与平台凭证、curl 不泄露 key。回调/inbound body 当前不满足后端合同，只在 skip TODO 里写期望。

```typescript
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

const opts = { global: { stubs: { RouterLink: RouterLinkStub } } }

async function settle(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  await flushPromises()
}

function runner(wrapper: ReturnType<typeof mount>, id: string) {
  const found = wrapper.findAllComponents(CapabilityRunner)
    .find((node) => node.props('cap').id === id)
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
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ channels: [{
      channel: 'feishu', type: 'bot', provider: 'open-feishu', enabled: true,
      target: 'ops', description: 'ops channel',
    }] }))
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
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(envelope([
      { name: 'n1' }, { id: 2 }, { type: 'voice' }, { provider: 'webhook' }, 'plain',
    ]))))
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    await buttonByText(wrapper, '发现渠道').trigger('click')
    await settle()
    expect(wrapper.findAll('.ch__channel-name').map((n) => n.text()))
      .toEqual(['n1', '2', 'voice', 'webhook', 'plain'])
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
      props: { moduleId: 'channel', capId: 'channel.messages.send' }, ...opts,
    })
    const capRunner = runner(wrapper, 'channel.messages.send')
    const execute = executeButton(capRunner)
    expect(execute.attributes('disabled')).toBeDefined()
    await execute.trigger('click')
    expect(fetchMock).not.toHaveBeenCalled()
    await buttonByText(capRunner, '钉钉').trigger('click')
    await capRunner.get('input[type="checkbox"]').setValue(true)
    expect(execute.attributes('disabled')).toBeUndefined()
    await execute.trigger('click')
    await settle()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/channel/messages')
    expect(JSON.parse(String(init.body))).toEqual({
      channel: 'dingtalk', target: 'chat-123', message: '你好',
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
    wrapper.unmount()
  })

  it('CH-08 inbound 卡片深链准确，聚焦 runner 保留签名 header 字段', () => {
    const landing = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    const inbound = landing.findAllComponents(CapabilityCard)
      .find((card) => card.props('cap').id === 'channel.inbound')!
    expect(inbound.exists()).toBe(true)
    expect(inbound.element.getAttribute('data-to')).toBe('/m/channel/channel.inbound')
    landing.unmount()
    const focused = mount(ChannelConsoleView, {
      props: { moduleId: 'channel', capId: 'channel.inbound' }, ...opts,
    })
    const capRunner = runner(focused, 'channel.inbound')
    expect(capRunner.findAll('input[type="text"]')).toHaveLength(1)
    expect(capRunner.text()).toContain('签名')
    focused.unmount()
  })

  it('CH-09 错误 module/cap 与空目录有明确 EmptyState', () => {
    const missing = mount(ChannelConsoleView, { props: { moduleId: 'missing' }, ...opts })
    expect(missing.text()).toContain('模块不存在')
    missing.unmount()
    const cap = mount(ChannelConsoleView, {
      props: { moduleId: 'channel', capId: 'channel.missing' }, ...opts,
    })
    expect(cap.text()).toContain('能力不存在')
    cap.unmount()
    useCatalogStore().catalog!.modules.find((m) => m.id === 'channel')!.capabilities = []
    const empty = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    expect(empty.text()).toContain('能力待补')
    empty.unmount()
  })

  it.skip('TODO(issue-01) CH-10 回调与 inbound 必须按后端 controller 合同发送 JSON body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ accepted: true }, 202))
    vi.stubGlobal('fetch', fetchMock)
    const callback = mount(ChannelConsoleView, {
      props: { moduleId: 'channel', capId: 'channel.callbacks.async-task' }, ...opts,
    })
    await callback.get('textarea').setValue('{"orderNo":"A100"}')
    await executeButton(runner(callback, 'channel.callbacks.async-task')).trigger('click')
    await settle()
    expect(JSON.parse(String((fetchMock.mock.calls[0][1] as RequestInit).body)))
      .toEqual({ orderNo: 'A100' })
    callback.unmount()

    const inbound = mount(ChannelConsoleView, {
      props: { moduleId: 'channel', capId: 'channel.inbound' }, ...opts,
    })
    expect(inbound.findAll('.form-control').length).toBeGreaterThan(1)
    inbound.unmount()
  })

  it.skip('TODO(issue-10/12/14/15) CH-11 null/畸形/重复提交有明确终态且只接受最新结果', async () => {
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
    wrapper.unmount()
  })

  it.skip('TODO(issue-11/13/17) CH-12 凭证切换清空旧租户渠道且旧响应不得回写', async () => {
    const pending = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(pending.promise))
    const wrapper = mount(ChannelConsoleView, { props: { moduleId: 'channel' }, ...opts })
    await buttonByText(wrapper, '发现渠道').trigger('click')
    useSessionStore().setApiKey('tenant-b-key')
    pending.resolve(jsonResponse({ channels: ['tenant-a-secret'] }))
    await settle()
    expect(wrapper.text()).not.toContain('tenant-a-secret')
    wrapper.unmount()
  })
})
```

## 4. 运行与验证

本次是独立前端，不运行 Maven：

```bash
cd capability-showcase-frontend

npx vitest run src/modules/multimodal/MultimodalConsoleView.interaction.test.ts
npx vitest run src/modules/interop/InteropEvalView.interaction.test.ts
npx vitest run src/modules/channel/ChannelConsoleView.interaction.test.ts

npx vitest run \
  src/modules/multimodal/MultimodalConsoleView.interaction.test.ts \
  src/modules/interop/InteropEvalView.interaction.test.ts \
  src/modules/channel/ChannelConsoleView.interaction.test.ts

npm run type-check
```

建议修复疑似问题后再跑相关共享回归：

```bash
npx vitest run src/utils/gate.test.ts src/api/client.test.ts src/api/errors.test.ts
npx vitest run src/components/capability/CapabilityRunner.interaction.test.ts
npx vitest run
```

`npm test -- <file>` 会先执行 `pretest` 重新生成 catalog，可能产生工作树写入；聚焦审查阶段优先使用用户指定的 `npx vitest run <file>`。若实际落地需要验证生成源，先确认允许更新 `public/catalog.json`。

## 5. 待验证信息与覆盖限制

- 已确认疑点：后端条件装配/Noop 实现以缺省 false 关闭多模态 flag，而真实生成目录八项均 default true/ready；修复策略仍需负责人确认（ISSUE-19）。
- 待验证设计决策：`rag.image.ingest` 如何同时表达 feature-off 与 scope-required；当前单一 state 会丢一个维度（ISSUE-20）。
- 待验证：多 envelope 同时存在时是“首 key 优先”还是“首非空优先”；ISSUE-15 不应在契约确认前强断。
- 待验证：指标 key 大小写是否由后端严格固定；真实 `RetrievalSummary` 是 `avgRecall/avgPrecision/meanMrr/hitRate/results`。
- OIDC credentialMode 文案需要构建期配置专项 suite。项目当前默认 Vitest 强制 `VITE_AUTH_MODE=apikey` 且铁律禁止模块 mock，本蓝图不提供会产生双实例风险的伪造方案。
- jsdom 不验证真实浏览器媒体解码、拖放 OS 行为、网络代理/CORS、后端 SSE 时序；这些属于浏览器 E2E/受控集成测试，不应假装由 Vitest 单测覆盖。
- 代码草案基于当前工作树读取；仓库有用户未提交改动，落地 Agent 应先重读三个 SFC 与共享 harness，若签名变化则最小调整并保持断言语义。

## 5.5 Claude 跨模型复核记录（2026-07-17）

对照真实仓库逐条核验后的修正（原因均已回写 03-suspected-issues.md）：

- **ISSUE-19 收窄（高→中）**：vision/chat.vision/rag.image.* 的后端 yml 默认值 2026-07-17 已翻转为开
  （`VISION_ENABLED:true` 等，yml 注释陈旧），仅 `VOICE_ENABLED:false` 默认关。MM-14 已改为只断 voice 三项
  fail-closed + 其余五项 ready；另记 banner 文案“多数能力默认未注册”反向陈旧。
- **ISSUE-05 降级（中高→低）**：清空 topK 不会发送 `topK:""` —— `buildJsonBody` 丢弃空串，现状即正确；
  新增通过态测试 IE-20 锁定该行为。仅保留 0/51/1.5 越界缺口于 IE-15。
- **ISSUE-13 移出模块清单**：全仓无任何 `humanizeError` 调用点传 `credentialMode`，属 app 级统一现状。
- 其余 17 条疑点核验成立：ISSUE-01（catalog 回调/入站缺 body 参数，后端 `@RequestBody` 必填 → 400，已核对
  `ChannelController.java:69-85`）、ISSUE-02（`query/expectedDocIds` vs `RetrievalCase(id,question,relevantDocIds)`，
  错误字段同时存在于 catalog placeholder/example 与视图硬编码 placeholder）、ISSUE-20（目录已有 9 个
  `scope-required` 能力，唯 `rag.image.ingest` 带 scopes 却标 ready，属生成源漏标）、ISSUE-03/04/06/07/08/09/10/
  11/12/14/15/16/17/18 均与源码一致。
- 草案细节核验通过：「钉钉」为 examples 预设 chip（CH-06 成立）；「预览 curl」「危险执行」「已完成」
  「为必填项」文案、`filepreview__thumb`/`stat__label`/`runner__curl-code` 类名、CapabilityCard 根即 RouterLink、
  eval.gate 参数全可选（IE-11 深链空表单可执行）、`chatId` defaultValue 不自动预填（须手动 setValue）均与实现吻合。

## 6. test-judge 反向审查记录

已按“只为通过”的反例逐项复审：

- 删除了只断文案/存在性的成功路径；保留的成功用例都至少验证请求合同或状态迁移。
- 无凭证、required、display-only 未确认均断 `fetch` 为 0；不是只看 disabled 属性。
- 422 同时断提示、HTTP 状态、人话错误、响应 body，避免把所有错误都当相同文案。
- fallback 同时断原始字段，避免只断 `.exists()`。
- 指标测试断 label 顺序、数值格式与 case 表行，不只断“有卡片”。
- headers 测试断业务 header、API key 来源、curl 占位符和明文不泄露。
- 未对 Channel callback/inbound 的当前无 body 行为做 passing 断言；它只作为 ISSUE-01 TODO。
- 未对 JSON 数组/标量误放行、旧结果残留、乱序覆盖、租户串味做 passing 断言；全部 skip 并给修复后期望。
- 未引入 `vi.mock`、Spring/Maven/Java 伪代码、真实外部依赖、随机时间断言或共享 Pinia。

仍需落地 Agent 编译校验的两个小点：Vue Test Utils 当前版本对 `ReturnType<typeof mount>` 辅助函数推断、以及 jsdom 对 `File`/`FormData` 同一对象 identity 的实现。若类型检查只在 helper 泛型处报错，可显式改为 `VueWrapper`；不得削弱请求/内容断言。

## 7. 最终验收清单

- [ ] 三个 `*.interaction.test.ts` 按精确路径落地，未改业务代码。
- [ ] 三个聚焦 Vitest 命令独立全绿，合并命令全绿。
- [ ] `npm run type-check` 全绿。
- [ ] 02 矩阵中非 TODO 的关键缺口闭合。
- [ ] ISSUE-01/02/03/06/07/11/12/19/20 已由业务负责人确认并修复，相关 skip 启用；若决定不修，需书面记录契约理由，不能静默删除测试。
- [ ] 所有疑似 bug 仍在 03 单列，未被写成“当前正确”断言。
- [ ] gate 拒绝/本地校验失败均 0 fetch；display-only 未确认 0 fetch。
- [ ] busy/error/重试无残留；乱序只保留最新；凭证切换无租户串味。
- [ ] multipart-sse 精确包含 FormData + `Accept:text/event-stream` + 无手工 Content-Type，并完成跨 chunk UI 终态。
- [ ] 回调/inbound 最终请求满足后端 body + header 真实合同。
- [ ] 全量前端测试无回归；不要求 Maven、H2 或 `INTERNAL_JWT_SECRET`。
