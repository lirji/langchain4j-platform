import { defineComponent, h, onMounted, type PropType } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Capability, ParamSpec } from '../../types/catalog'
import { useCatalogStore } from '../../stores/catalog'
import { useHistoryStore } from '../../stores/history'
import CapabilityRunner from './CapabilityRunner.vue'
import { cleanup, jsonResponse, setupCatalog, sseResponse } from '../../test/interactionHarness'
import { stubMatchMedia, type ViewportStub } from '../../test/viewport'

/**
 * 57 项能力的传输合同测试：合同表独立硬编码（不从生产 assembleRequest 自证），
 * 每项从用户点击出发核对真实 fetch 的 method/URL/凭证头/请求体；flag-off 与危险能力核对 0 fetch。
 */

type Contract = readonly [method: string, path: string, kind: string, state: string]
const EXPECTED: Record<string, Contract> = {
  'chat.sync': ['POST', '/chat', 'json', 'ready'],
  'chat.stream': ['POST', '/chat/stream', 'sse', 'ready'],
  'chat.extract': ['POST', '/extract', 'json', 'ready'],
  'chat.auto': ['POST', '/chat/auto', 'json', 'ready'],
  'chat.cascade': ['POST', '/chat/cascade', 'json', 'ready'],
  'chat.mcp': ['POST', '/chat/mcp', 'json', 'flag-off'],
  'chat.memory': ['POST', '/chat/memory', 'json', 'ready'],
  'memory.profile.get': ['GET', '/memory/profile', 'none', 'ready'],
  'memory.profile.clear': ['DELETE', '/memory/profile', 'none', 'ready'],
  'chat.cache.clear': ['DELETE', '/chat/cache', 'none', 'ready'],
  'rag.query': ['POST', '/rag/query', 'json', 'ready-degraded'],
  'rag.upload.file': ['POST', '/rag/documents', 'multipart', 'scope-required'],
  'rag.upload.file.shared': ['POST', '/rag/documents?visibility=public', 'multipart', 'scope-required'],
  'rag.upload.json': ['POST', '/rag/documents', 'json', 'scope-required'],
  'rag.upload.json.shared': ['POST', '/rag/documents?visibility=public', 'json', 'scope-required'],
  'rag.documents.list': ['GET', '/rag/documents', 'none', 'ready'],
  'rag.documents.delete': ['DELETE', '/rag/documents/{docId}', 'none', 'ready'],
  'rag.documents.get': ['GET', '/rag/documents/{docId}', 'none', 'ready'],
  'rag.obsidian.import': ['POST', '/rag/obsidian/import', 'multipart', 'scope-required'],
  'rag.graph.query': ['POST', '/rag/graph/query', 'json', 'ready'],
  'rag.graph.entities': ['GET', '/rag/graph/entities', 'none', 'ready'],
  'agent.run': ['POST', '/agent/run', 'json', 'ready'],
  'agent.run.async': ['POST', '/agent/run/async', 'json', 'ready'],
  'agent.tasks.list': ['GET', '/agent/tasks', 'none', 'ready'],
  'agent.tasks.get': ['GET', '/agent/tasks/{taskId}', 'none', 'ready'],
  'agent.tasks.stream': ['GET', '/agent/tasks/{taskId}/stream', 'sse', 'ready'],
  'agent.tasks.cancel': ['DELETE', '/agent/tasks/{taskId}', 'none', 'ready'],
  'agent.dag.run': ['POST', '/agent/dag/run', 'json', 'ready'],
  'agent.dag.plan-run': ['POST', '/agent/dag/plan-run', 'json', 'ready'],
  'agent.dag.run.async': ['POST', '/agent/dag/run/async', 'json', 'ready'],
  'agent.dag.plan-run.async': ['POST', '/agent/dag/plan-run/async', 'json', 'ready'],
  'agent.chain': ['POST', '/agent/chain', 'json', 'ready'],
  'agent.vote': ['POST', '/agent/vote', 'json', 'ready'],
  'agent.reflexive': ['POST', '/agent/reflexive', 'json', 'ready'],
  'agent.reflexive.stream': ['POST', '/agent/reflexive/stream', 'sse', 'ready'],
  'agent.analyst.run': ['POST', '/agent/analyst/run', 'json', 'ready'],
  'agent.analyst.run.async': ['POST', '/agent/analyst/run/async', 'json', 'ready'],
  'agent.process.run': ['POST', '/agent/process/run', 'json', 'ready'],
  'agent.process.run.async': ['POST', '/agent/process/run/async', 'json', 'ready'],
  'async.create': ['POST', '/async/tasks', 'json', 'ready'],
  'async.list': ['GET', '/async/tasks', 'none', 'ready'],
  'async.get': ['GET', '/async/tasks/{taskId}', 'none', 'ready'],
  'async.status.update': ['PATCH', '/async/tasks/{taskId}/status', 'json', 'ready'],
  'async.lease': ['POST', '/async/tasks/{taskId}/lease', 'json', 'ready'],
  'async.cancel': ['DELETE', '/async/tasks/{taskId}', 'none', 'ready'],
  'async.stream': ['GET', '/async/tasks/{taskId}/stream', 'sse', 'ready'],
  'async.deadletter': ['GET', '/async/webhook-outbox/dead', 'none', 'ready'],
  'workflow.refund.start': ['POST', '/workflow/refund/start', 'json', 'ready'],
  'workflow.tasks.list': ['GET', '/workflow/tasks', 'none', 'scope-required'],
  'workflow.tasks.claim': ['POST', '/workflow/tasks/{taskId}/claim', 'none', 'scope-required'],
  'workflow.tasks.unclaim': ['POST', '/workflow/tasks/{taskId}/unclaim', 'none', 'scope-required'],
  'workflow.tasks.complete': ['POST', '/workflow/tasks/{taskId}/complete', 'json', 'scope-required'],
  'workflow.instances.get': ['GET', '/workflow/instances/{instanceId}', 'none', 'ready'],
  'workflow.data.purge': ['DELETE', '/workflow/data', 'none', 'display-only'],
  'analytics.schema.tables': ['GET', '/analytics/schema/tables', 'none', 'ready'],
  'analytics.schema.describe': ['GET', '/analytics/schema/tables/{table}', 'none', 'ready'],
  'analytics.sql': ['POST', '/chat/sql', 'json', 'ready'],
}

function valueFor(p: ParamSpec): unknown {
  if (p.type === 'file') return new File(['fixture'], 'fixture.txt', { type: 'text/plain' })
  if (p.type === 'boolean') return false
  if (p.type === 'number') return 0.5
  if (p.type === 'integer') return p.name === 'topK' ? 5 : 3
  if (p.type === 'select') return p.enumValues?.[0] ?? 'RUNNING'
  if (p.type === 'json' || p.type === 'array' || p.type === 'object') {
    return p.name === 'tasks' ? '[{"id":"t1","goal":"g"}]' : '{"x":1}'
  }
  // path/query 里的资源 id 用保留字符验证 encodeURIComponent。
  if (p.name === 'table' || p.name.endsWith('Id')) return 'a/b ?中#'
  return `${p.name}-value`
}

function valuesFor(cap: Capability): Record<string, unknown> {
  return Object.fromEntries(cap.params.map((p) => [p.name, valueFor(p)]))
}

const DynamicFormStub = defineComponent({
  props: { params: { type: Array as PropType<ParamSpec[]>, required: true } },
  emits: ['update:modelValue'],
  setup(props, { emit, expose }) {
    onMounted(() =>
      emit('update:modelValue', Object.fromEntries(props.params.map((p) => [p.name, valueFor(p)]))),
    )
    expose({ validate: () => ({}) })
    return () => h('div', { 'data-testid': 'dynamic-form-stub' })
  },
})

function expectedUrl(path: string, cap: Capability, values: Record<string, unknown>): string {
  let url = path
  for (const p of cap.params.filter((x) => x.in === 'path')) {
    url = url.replaceAll(`{${p.name}}`, encodeURIComponent(String(values[p.name])))
  }
  const query = new URLSearchParams()
  for (const p of cap.params.filter((x) => x.in === 'query')) query.append(p.name, String(values[p.name]))
  if (!query.size) return url
  return `${url}${url.includes('?') ? '&' : '?'}${query.toString()}`
}

function executeButton(wrapper: ReturnType<typeof mount>) {
  const button = wrapper.findAll('button').find((b) => /执行|开始流式/.test(b.text()))
  if (!button) throw new Error('execute button missing')
  return button
}

describe('CapabilityRunner 57 项合同', () => {
  beforeEach(() => setupCatalog())
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    cleanup()
  })

  it('真实 catalog 的六模块恰好 57 项且 method/path/kind/state 无漂移', () => {
    const ids = ['chat', 'rag', 'agent', 'tasks', 'workflow', 'analytics']
    const caps = useCatalogStore()
      .catalog!.modules.filter((m) => ids.includes(m.id))
      .flatMap((m) => m.capabilities)
    expect(caps).toHaveLength(57)
    expect(Object.keys(EXPECTED)).toHaveLength(57)
    expect(Object.fromEntries(caps.map((c) => [c.id, [c.method, c.path, c.requestKind, c.state]])))
      .toEqual(EXPECTED)
  })

  it.each(Object.entries(EXPECTED))('%s：用户点击产生精确 fetch 计划或被真实闸门拦截', async (id, contract) => {
    const cap = useCatalogStore().capabilityById(id)!
    const fetchMock = vi.fn().mockResolvedValue(
      cap.requestKind === 'sse'
        ? sseResponse(['event: done\ndata: {}\n\n'])
        : jsonResponse({ ok: true }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(CapabilityRunner, {
      props: { cap },
      global: { stubs: { DynamicForm: DynamicFormStub } },
    })
    await flushPromises()
    const button = executeButton(wrapper)

    if (cap.state === 'flag-off') {
      // flag-off：按钮禁用、reason 暴露精确 featureFlag、强行点击也必须 0 fetch。
      expect(button.attributes('disabled')).toBeDefined()
      expect(wrapper.find('.runner__reason').text()).toContain(cap.featureFlag!)
      await button.trigger('click')
      await flushPromises()
      expect(fetchMock).not.toHaveBeenCalled()
      wrapper.unmount()
      return
    }
    if (!cap.executableByDefault) {
      // 危险能力：未确认禁用且强行点击 0 fetch；勾选确认后才放行。
      expect(button.attributes('disabled')).toBeDefined()
      await button.trigger('click')
      await flushPromises()
      expect(fetchMock).not.toHaveBeenCalled()
      await wrapper.get('input[type="checkbox"]').setValue(true)
      expect(button.attributes('disabled')).toBeUndefined()
    }

    await button.trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const values = valuesFor(cap)
    expect(init.method).toBe(contract[0])
    expect(url).toBe(expectedUrl(contract[1], cap, values))
    const headers = new Headers(init.headers)
    expect(headers.get('X-Api-Key')).toBe('test-key')
    expect(headers.has('Authorization')).toBe(false)
    // 租户身份只来自凭证头：URL 与请求体都不得出现 tenantId 串味。
    expect(url).not.toContain('tenantId')
    expect(JSON.stringify(init.body ?? '')).not.toContain('tenantId')

    if (cap.requestKind === 'json' || cap.requestKind === 'sse') {
      const bodyParams = cap.params.filter((p) => p.in === 'body')
      if (bodyParams.length) {
        expect(headers.get('Content-Type')).toBe('application/json')
        const body = JSON.parse(String(init.body)) as Record<string, unknown>
        expect(Object.keys(body).sort()).toEqual(bodyParams.map((p) => p.name).sort())
      } else expect(init.body).toBeUndefined()
      if (cap.requestKind === 'sse') expect(headers.get('Accept')).toBe('text/event-stream')
    } else if (cap.requestKind === 'multipart') {
      expect(init.body).toBeInstanceOf(FormData)
      expect(headers.has('Content-Type')).toBe(false)
      for (const p of cap.params.filter((x) => x.in === 'form-data')) {
        expect((init.body as FormData).has(p.name)).toBe(true)
      }
    } else {
      expect(init.body).toBeUndefined()
    }
    wrapper.unmount()
  })

  it('表单校验失败时不调用 fetch，也不写历史', async () => {
    const InvalidForm = defineComponent({
      setup(_, { expose }) {
        expose({ validate: () => ({ message: '必填' }) })
        return () => h('div')
      },
    })
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(CapabilityRunner, {
      props: { cap: useCatalogStore().capabilityById('chat.sync')! },
      global: { stubs: { DynamicForm: InvalidForm } },
    })
    await executeButton(wrapper).trigger('click')
    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(useHistoryStore().entries).toHaveLength(0)
    wrapper.unmount()
  })

  it('成功只 emit 一次 result，并记录不可变的请求快照', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ reply: 'ok' })))
    const wrapper = mount(CapabilityRunner, {
      props: { cap: useCatalogStore().capabilityById('chat.sync')! },
      global: { stubs: { DynamicForm: DynamicFormStub } },
    })
    await flushPromises()
    await executeButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.emitted('result')).toHaveLength(1)
    expect(useHistoryStore().entries).toHaveLength(1)
    expect(useHistoryStore().entries[0]).toMatchObject({ capId: 'chat.sync', ok: true, status: 200 })
    expect(useHistoryStore().entries[0].params).toMatchObject({ message: 'message-value' })
    // 快照不可变：执行后再改表单值，历史里的入参快照不得跟着变。
    wrapper.findComponent(DynamicFormStub).vm.$emit('update:modelValue', { message: 'EDITED-AFTER' })
    await flushPromises()
    expect(useHistoryStore().entries[0].params).toMatchObject({ message: 'message-value' })
    wrapper.unmount()
  })
})

describe('CapabilityRunner · 手机档执行后自动滚到响应区', () => {
  let viewport: ViewportStub | null = null
  const hadScrollIntoView = 'scrollIntoView' in Element.prototype

  beforeEach(() => setupCatalog())
  afterEach(() => {
    viewport?.restore()
    viewport = null
    // jsdom 原生无 scrollIntoView，测试注入后清理，避免污染其它用例
    if (!hadScrollIntoView) delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    cleanup()
  })

  async function runOnce() {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ ok: true })))
    const wrapper = mount(CapabilityRunner, {
      props: { cap: useCatalogStore().capabilityById('chat.sync')! },
      global: { stubs: { DynamicForm: DynamicFormStub } },
    })
    await flushPromises()
    await executeButton(wrapper).trigger('click')
    await flushPromises()
    return wrapper
  }

  it('phone 视口：执行返回后对响应区调用 scrollIntoView', async () => {
    viewport = stubMatchMedia({ desktop: false, phone: true })
    const scrollSpy = vi.fn()
    ;(Element.prototype as { scrollIntoView?: unknown }).scrollIntoView = scrollSpy
    const wrapper = await runOnce()
    expect(scrollSpy).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('桌面视口（双栏并排）：不触发 scrollIntoView', async () => {
    viewport = stubMatchMedia({ desktop: true, phone: false })
    const scrollSpy = vi.fn()
    ;(Element.prototype as { scrollIntoView?: unknown }).scrollIntoView = scrollSpy
    const wrapper = await runOnce()
    expect(scrollSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
