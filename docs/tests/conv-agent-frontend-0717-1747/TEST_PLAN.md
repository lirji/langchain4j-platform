# TEST PLAN — 对话、检索、智能体与编排前端

## 1. 结论与范围

本蓝图覆盖 `capability-showcase-frontend` 六个模块实际生成目录中的 57 项能力，以及它们共同依赖的 Runner、执行状态机、SSE、会话/历史基础设施。详细文件与方法见 [01-scope.md](./01-scope.md)，逐能力缺口见 [02-coverage-matrix.md](./02-coverage-matrix.md)，交互缺陷见 [03-suspected-issues.md](./03-suspected-issues.md)。

这是纯前端范围，因此题面末尾模板中的 Java 代码块、`src/test/java`、Maven、Spring、H2、TenantContext 不适用；按题面前部的明确前端适配要求，草案全部使用 `ts`，测试与被测代码同目录 `*.test.ts`，Vitest/jsdom/VTU/Pinia。草案只记录在本文，当前任务不向 `src/` 落盘。

## 2. 测试策略与分层

### L0：目录与传输契约（57/57）

用真实 `loadCatalog()` 和真实 `CapabilityRunner → useCapabilityRun → client/sse`，参数化遍历 57 项：用户点击、method、URL/path/query、JSON/FormData/none、`X-Api-Key`、SSE Accept、危险确认、flag-off 0 fetch。这个层次防止“每个页面看似能点，实际路径/请求体漂移”。

### L1：共享状态机单元测试

直接测试 `useCapabilityRun`、`useAbortable`、SSE Console、history/favorites：成功、HTTP/业务错误、AbortError、流终态、事件上限、reset/卸载、历史只记一次、存储异常。这一层不挂完整页面，失败定位清楚。

### L2：六个专用视图交互测试

用 `mount` 驱动真实 DOM：自定义 composer 填写、专用 API 映射、结果结构化呈现、空/失败/中断、模式/分页/选择/审批状态流转。API 边界用 mock，不重复测试 fetch parser 内部。

### L3：并发、边界、安全回归

用 deferred Promise 人工控制后到顺序；用 ReadableStream 跨 chunk 推 SSE；验证重复订阅/卸载；验证必填/范围/编码/XSS/租户不串味。已确认的产品缺陷使用 `it.skip('TODO(issue-xx)')` 写“期望行为”，不把错误现状固化。

### L4：默认套件与非目标

- 所有新增测试必须进入默认 `npm test`，不得依赖真实网关、浏览器计时、网络或登录。
- 不新增 E2E/Playwright；路由壳层已有覆盖，本轮风险在组件交互状态机。
- apikey 固定环境下不伪造 OIDC 视图行为；Bearer scope 的 on/off 已由 `gate.test.ts`/session 测试承担。若另开 OIDC 文件，必须 `vi.mock('../../config')` 且独立模块缓存。

## 3. 可验证验收标准

1. `loadCatalog()` 的六模块能力集合恰好 57 项，id/method/path/requestKind/state 与硬编码契约表完全相等。
2. 除当前 `chat.mcp` 外，每项能力至少一次由 Runner 用户点击抵达 fetch；MCP 必须 0 fetch。`workflow.data.purge` 必须先确认才发请求。
3. 所有请求携带 `X-Api-Key: test-key`，无 `Authorization`、无客户端 `tenantId`；multipart 不手设 Content-Type；SSE 有 `Accept: text/event-stream`。
4. 六个专用视图每个都覆盖 success/error；chat/agent/tasks 覆盖 SSE complete/error/abort；所有 busy/disabled/terminal 状态都有 DOM 断言。
5. workflow 的 `approved:false` 必须真实存在于 body，不能因 falsy 被省略；RAG shared visibility、分页与路径编码必须精确。
6. 后到旧响应不能覆盖新选择；reset/unmount 后旧异步回调不能复活状态；事件缓存必须有界。这些在相关 issue 修复前保持 TODO skip，不允许反向断言当前错误。
7. `npm test` 与 `npm run type-check` 全绿；测试无未恢复 fake timer/global fetch/localStorage spy，无残留 DOM。

## 4. 覆盖摘要

现有 7 个视图文件共 38 例全部通过，但 57 项均未完整覆盖“表单→请求→响应→UI”。本轮优先闭合：

- 57/57 传输合同：CONTRACT。
- 通用 JSON/SSE/中断/reset/历史：RUN + Runner focused + INFRA。
- Chat 消息和画像、RAG 文档/检索、Agent 14 模式、Async 任务投影、Workflow 审批、Analytics NL2SQL：六个 interaction 文件。
- 关键未闭合项必须显式 TODO：issue-01/02/03/04/05/06/07/08/09/10/11/12/13/15。

## 5. 疑似问题摘要

完整复现与建议见 [03-suspected-issues.md](./03-suspected-issues.md)。优先修复顺序：

1. Workflow claim/complete 错误和成功消息被 refresh 清空：动作消息与刷新消息拆 state。
2. Analytics selectTable 无序号守卫：加入 seq/abort。
3. `useCapabilityRun.reset` 无 generation：旧流回调可把 idle 改回 aborted。
4. Async 手动重订阅不发 Last-Event-ID；旧 onDone 可关闭新订阅；事件无上限。
5. Agent DAG required 使用 OR、数字无范围校验；模式切换用新模式解释旧结果。
6. Chat 业务 error 抹掉部分 token；清空换 chatId 后旧画像残留/晚到。
7. RAG query 高亮使用当前而非 submitted query；GraphRAG 无条件“未启用”与当前 ready 目录矛盾。
8. 专用请求普遍未在卸载时 abort（产品切换策略待确认，但卸载释放应满足）。

已排除：Async cancel 200 后强写 CANCELLED 符合当前后端 `{taskId,cancelled:true}` 契约；应锁定失败不强写。

## 6. 测试草案总览

| 文件 | 锁定的核心行为 |
|---|---|
| `src/test/interactionHarness.ts` | 唯一共享夹具：真实 catalog、新 Pinia、固定 Key、Response/SSE/deferred、清理 |
| `src/components/capability/CapabilityRunner.interaction.test.ts` | 57 项请求合同、闸门、表单验证、history/result |
| `src/composables/useCapabilityRun.test.ts` | JSON/SSE 状态机、错误/abort、MAX_EVENTS、reset generation |
| `src/composables/useAbortable.test.ts` | fresh/abort/dispose |
| `src/components/capability/SseConsole.test.ts` | token/event/note/error/search 状态 |
| `src/stores/history.test.ts`, `favorites.test.ts` | 容量/重放/内存与 localStorage 异常 |
| 六模块 `*.interaction.test.ts` | 专用交互与并发状态 |
| `AgentStepTimeline.test.ts`, `AsyncTaskTimeline.interaction.test.ts` | 领域渲染与 action emit |

## 7. 完整代码草案

### 7.1 共享测试夹具

放置路径：`capability-showcase-frontend/src/test/interactionHarness.ts`

```ts
import { createPinia, setActivePinia } from 'pinia'
import type { VueWrapper } from '@vue/test-utils'
import { useCatalogStore } from '../stores/catalog'
import { useSessionStore } from '../stores/session'
import { loadCatalog } from './fixtures'
import type { Capability } from '../types/catalog'

export const RouterLinkStub = {
  name: 'RouterLink',
  props: ['to'],
  template: '<a :data-to="String(to)"><slot /></a>',
}

export function setupCatalog(apiKey = 'test-key'): void {
  setActivePinia(createPinia())
  useCatalogStore().catalog = structuredClone(loadCatalog())
  if (apiKey) useSessionStore().setApiKey(apiKey)
}

export function capability(id: string): Capability {
  const value = useCatalogStore().capabilityById(id)
  if (!value) throw new Error(`missing catalog capability: ${id}`)
  return value
}

export function jsonResponse(data: unknown, status = 200, extra: HeadersInit = {}): Response {
  return new Response(status === 204 ? null : JSON.stringify(data), {
    status,
    statusText: status >= 400 ? 'Failure' : 'OK',
    headers: { 'Content-Type': 'application/json', 'X-Trace-Id': 'trace-12345678', ...extra },
  })
}

export function textResponse(text: string, status: number): Response {
  return new Response(text, { status, statusText: 'Failure', headers: { 'Content-Type': 'text/plain' } })
}

export function sseResponse(chunks: string[], status = 200): Response {
  const encoder = new TextEncoder()
  let index = 0
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (index < chunks.length) controller.enqueue(encoder.encode(chunks[index++]))
      else controller.close()
    },
  })
  return new Response(body, {
    status,
    headers: { 'Content-Type': 'text/event-stream', 'X-Trace-Id': 'trace-sse-1234' },
  })
}

export function deferred<T>(): {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
} {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

export function buttonByText(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find((node) => node.text().includes(text))
  if (!button) throw new Error(`button not found: ${text}`)
  return button
}

export function cleanup(wrapper?: VueWrapper): void {
  wrapper?.unmount()
  document.body.replaceChildren()
}
```

关键断言点：所有测试只从同一真实生成目录取 capability，避免手写 fixture 漂移；`structuredClone` 防止单例 JSON 被某例改坏；固定 trace 让 UI 断言确定。

### 7.2 57 项 Runner/HTTP 合同

放置路径：`capability-showcase-frontend/src/components/capability/CapabilityRunner.interaction.test.ts`

```ts
import { defineComponent, h, onMounted, type PropType } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Capability, ParamSpec } from '../../types/catalog'
import { useCatalogStore } from '../../stores/catalog'
import { useHistoryStore } from '../../stores/history'
import CapabilityRunner from './CapabilityRunner.vue'
import { cleanup, jsonResponse, setupCatalog, sseResponse } from '../../test/interactionHarness'

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
    onMounted(() => emit('update:modelValue', Object.fromEntries(props.params.map((p) => [p.name, valueFor(p)]))))
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
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); cleanup() })

  it('真实 catalog 的六模块恰好 57 项且 method/path/kind/state 无漂移', () => {
    const ids = ['chat', 'rag', 'agent', 'tasks', 'workflow', 'analytics']
    const caps = useCatalogStore().catalog!.modules
      .filter((m) => ids.includes(m.id)).flatMap((m) => m.capabilities)
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
      expect(button.attributes('disabled')).toBeDefined()
      expect(wrapper.find('.runner__reason').text()).toContain(cap.featureFlag!)
      await button.trigger('click')
      expect(fetchMock).not.toHaveBeenCalled()
      wrapper.unmount()
      return
    }
    if (!cap.executableByDefault) {
      expect(button.attributes('disabled')).toBeDefined()
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
    expect(JSON.stringify(init.body ?? '')).not.toContain('tenantId')

    if (cap.requestKind === 'json' || cap.requestKind === 'sse') {
      const bodyParams = cap.params.filter((p) => p.in === 'body')
      if (bodyParams.length) {
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
      setup(_, { expose }) { expose({ validate: () => ({ message: '必填' }) }); return () => h('div') },
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
    wrapper.unmount()
  })
})
```

为什么不是“只为通过”：合同表独立硬编码 57 个 method/path/kind/state，不能从生产 `assembleRequest` 自证；每例从用户点击出发并检查真正的 fetch init，而不是只检查组件存在。flag-off/危险能力明确检查 0 fetch。

### 7.3 核心运行状态机

放置路径：`capability-showcase-frontend/src/composables/useCapabilityRun.test.ts`

```ts
import { effectScope, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCapabilityRun } from './useCapabilityRun'
import { useCatalogStore } from '../stores/catalog'
import { cleanup, jsonResponse, setupCatalog, sseResponse, textResponse } from '../test/interactionHarness'

async function waitFor(check: () => boolean): Promise<void> {
  for (let i = 0; i < 30; i += 1) {
    if (check()) return
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  throw new Error('condition did not become true')
}

describe('useCapabilityRun', () => {
  beforeEach(() => setupCatalog())
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); cleanup() })

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
    expect(run.errorMessage.value).toContain('网络请求失败')
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
    const run = scope.run(() => useCapabilityRun(() => useCatalogStore().capabilityById('agent.reflexive.stream')!))!
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

  it('abort 正在读取的 SSE 后进入 aborted 且不报 transport error', async () => {
    let streamController!: ReadableStreamDefaultController<Uint8Array>
    const body = new ReadableStream<Uint8Array>({ start(c) { streamController = c } })
    const fetchMock = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      init.signal?.addEventListener('abort', () => streamController.error(new DOMException('aborted', 'AbortError')))
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
    const run = scope.run(() => useCapabilityRun(() => useCatalogStore().capabilityById('agent.tasks.stream')!))!
    await run.run({ taskId: 't1' })
    await waitFor(() => run.phase.value === 'done')
    expect(run.sse.events).toHaveLength(2000)
    scope.stop()
  })

  it.skip('TODO(issue-03): reset 后旧流 onDone 不得把 idle 回写为 aborted', async () => {
    let streamController!: ReadableStreamDefaultController<Uint8Array>
    const body = new ReadableStream<Uint8Array>({ start(c) { streamController = c } })
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      init.signal?.addEventListener('abort', () => queueMicrotask(() => streamController.error(new DOMException('', 'AbortError'))))
      return Promise.resolve(new Response(body, { status: 200 }))
    }))
    const scope = effectScope()
    const run = scope.run(() => useCapabilityRun(() => useCatalogStore().capabilityById('chat.stream')!))!
    await run.run({ message: 'x' })
    run.reset()
    await nextTick(); await Promise.resolve()
    expect(run.phase.value).toBe('idle')
    expect(run.sse.status).toBe('idle')
    scope.stop()
  })
})
```

关键断言不是只看 phase：同时锁定 status/body/trace/token/event/note/上限，并用真实 ReadableStream 触发 abort。issue-03 只写期望且 skip。

### 7.4 AbortController 生命周期

放置路径：`capability-showcase-frontend/src/composables/useAbortable.test.ts`

```ts
import { effectScope } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useAbortable } from './useAbortable'

describe('useAbortable', () => {
  it('fresh 中止上一 controller，abort 清引用', () => {
    const scope = effectScope()
    const state = scope.run(useAbortable)!
    const first = state.fresh()
    const spy = vi.spyOn(first, 'abort')
    const second = state.fresh()
    expect(spy).toHaveBeenCalledOnce()
    expect(first.signal.aborted).toBe(true)
    expect(state.controller.value).toBe(second)
    state.abort()
    expect(second.signal.aborted).toBe(true)
    expect(state.controller.value).toBeNull()
    scope.stop()
  })

  it('scope dispose 自动中止当前 controller', () => {
    const scope = effectScope()
    const state = scope.run(useAbortable)!
    const current = state.fresh()
    scope.stop()
    expect(current.signal.aborted).toBe(true)
  })
})
```

### 7.5 SSE Console 展示

放置路径：`capability-showcase-frontend/src/components/capability/SseConsole.test.ts`

```ts
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import SseConsole from './SseConsole.vue'
import { cleanup } from '../../test/interactionHarness'

describe('SseConsole', () => {
  afterEach(() => cleanup())

  it('流式 token、状态、note/error、trace 均真实呈现', () => {
    const wrapper = mount(SseConsole, { props: {
      tokens: 'hello world',
      events: [{ event: 'message', data: 'hello' }, { event: 'blocked', data: 'policy' }],
      status: 'streaming', note: '护栏提示', error: '流错误', elapsedMs: 12, traceId: '1234567890',
    } })
    expect(wrapper.get('.sse__transcript').text()).toContain('hello world')
    expect(wrapper.get('[role="status"]').text()).toContain('护栏提示')
    expect(wrapper.get('[role="alert"]').text()).toContain('流错误')
    expect(wrapper.text()).toContain('2 事件')
    expect(wrapper.text()).toContain('trace 12345678')
    wrapper.unmount()
  })

  it('搜索命中数和高亮来自 token，而非事件 data', async () => {
    const wrapper = mount(SseConsole, { props: {
      tokens: 'refund refund order', events: [{ event: 'message', data: 'refund' }], status: 'done',
    } })
    await wrapper.get('input[type="search"]').setValue('refund')
    expect(wrapper.get('.sse__search-count').text()).toBe('2')
    expect(wrapper.findAll('.sse__hit')).toHaveLength(2)
    wrapper.unmount()
  })

  it('切到事件流后按顺序展示原始命名事件，空 token 时下载禁用', async () => {
    const wrapper = mount(SseConsole, { props: {
      tokens: '', events: [{ event: 'RUNNING', data: '{"n":1}' }], status: 'done',
    } })
    await wrapper.findAll('[role="tab"]')[1].trigger('click')
    expect(wrapper.get('.sse__events').text()).toContain('RUNNING')
    expect(wrapper.get('button[title="下载转录 (.txt)"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
})
```

### 7.6 History 与 favorites

放置路径：`capability-showcase-frontend/src/stores/history.test.ts`

```ts
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useHistoryStore, type HistoryEntry } from './history'

const entry = (n: number): HistoryEntry => ({
  id: String(n), capId: 'chat.sync', method: 'POST', path: '/chat', status: 200,
  elapsedMs: n, traceId: null, at: n, params: { message: `m${n}` }, ok: true,
})

describe('history store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('最新在前且只保留 50 条', () => {
    const store = useHistoryStore()
    for (let i = 0; i < 55; i += 1) store.record(entry(i))
    expect(store.entries).toHaveLength(50)
    expect(store.entries[0].id).toBe('54')
    expect(store.entries.at(-1)?.id).toBe('5')
  })

  it('replay 只由相同 capId 消费一次，错 cap 不清空', () => {
    const store = useHistoryStore()
    store.requestReplay('chat.sync', { message: 'again' })
    expect(store.consumeReplay('rag.query')).toBeNull()
    expect(store.consumeReplay('chat.sync')).toEqual({ message: 'again' })
    expect(store.consumeReplay('chat.sync')).toBeNull()
  })

  it('clear 清空记录但不隐式写持久化存储', () => {
    const store = useHistoryStore(); store.record(entry(1)); store.clear()
    expect(store.entries).toEqual([])
  })
})
```

放置路径：`capability-showcase-frontend/src/stores/favorites.test.ts`

```ts
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useFavoritesStore } from './favorites'

describe('favorites store', () => {
  beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()) })
  afterEach(() => vi.restoreAllMocks())

  it('忽略脏 JSON，只持久化 capability id', () => {
    localStorage.setItem('showcase.favorites', '{bad')
    setActivePinia(createPinia())
    const store = useFavoritesStore()
    expect(store.ids).toEqual([])
    store.toggle('chat.sync')
    expect(JSON.parse(localStorage.getItem('showcase.favorites')!)).toEqual(['chat.sync'])
    expect(localStorage.getItem('showcase.favorites')).not.toContain('params')
  })

  it('toggle 幂等增删，setItem 抛错时内存状态仍可用', () => {
    const store = useFavoritesStore()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    expect(() => store.toggle('rag.query')).not.toThrow()
    expect(store.isFav('rag.query')).toBe(true)
    expect(() => store.toggle('rag.query')).not.toThrow()
    expect(store.isFav('rag.query')).toBe(false)
  })
})
```

这些 store 断言锁定容量、一次性消费和“不把请求参数写入 localStorage”的安全边界，而不是只看 action 可调用。

### 7.7 Chat 专用交互

放置路径：`capability-showcase-frontend/src/modules/chat/ChatConsoleView.interaction.test.ts`

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ChatConsoleView from './ChatConsoleView.vue'
import { buttonByText, cleanup, jsonResponse, setupCatalog, sseResponse } from '../../test/interactionHarness'

async function settle(): Promise<void> {
  await flushPromises(); await new Promise((resolve) => setTimeout(resolve, 0)); await flushPromises()
}

function mode(wrapper: ReturnType<typeof mount>, label: string) {
  const value = wrapper.findAll('.chat__mode').find((b) => b.text().includes(label))
  if (!value) throw new Error(`missing mode ${label}`)
  return value
}

describe('ChatConsoleView interaction', () => {
  beforeEach(() => {
    setupCatalog()
    vi.stubGlobal('crypto', { randomUUID: () => '12345678-abcd-ef00-1111-222222222222' })
  })
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); cleanup() })

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
    const image = wrapper.find('.msg__md img')
    if (image.exists()) expect(image.attributes('onerror')).toBeUndefined()
    expect(wrapper.text()).toContain('trace trace-12')
    wrapper.unmount()
  })

  it('流式：跨 chunk token、护栏 note 与 done 分别进入助手/系统消息', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'data: hel', 'lo\n\nevent: blocked\ndata: unsafe\n\n', 'data: world\n\nevent: done\ndata: {}\n\n',
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

  it('HTTP 失败把助手气泡置 error，不残留“等待响应”或 markdown success', async () => {
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
      start(c) { controller = c; c.enqueue(encoder.encode('data: partial\n\n')) },
    })
    const fetchMock = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      init.signal?.addEventListener('abort', () => controller.error(new DOMException('', 'AbortError')))
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
    for (const [label, path] of [['意图路由', '/chat/auto'], ['级联降级', '/chat/cascade'], ['长期记忆', '/chat/memory']] as const) {
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
    const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock)
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
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    await mode(wrapper, '长期记忆').trigger('click')
    await buttonByText(wrapper, '长期用户画像').trigger('click')
    await buttonByText(wrapper, '查看画像').trigger('click'); await settle()
    expect(fetchMock.mock.calls[0][0]).toBe('/memory/profile?chatId=web-12345678')
    expect(wrapper.get('.chat__memory-pre').text()).toContain('vip')
    await buttonByText(wrapper, '清除画像').trigger('click'); await settle()
    expect((fetchMock.mock.calls[1][1] as RequestInit).method).toBe('DELETE')
    expect(wrapper.get('.chat__memory-pre').text()).toContain('已清除')
    wrapper.unmount()
  })

  it.skip('TODO(issue-10): 业务 error 保留已收 token，并另显示错误', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'data: partial answer\n\n', 'event: error\ndata: tool failed\n\n',
    ])))
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    await wrapper.get('.chat__textarea').setValue('x'); await buttonByText(wrapper, '发送').trigger('click'); await settle()
    expect(wrapper.get('.msg--assistant').text()).toContain('partial answer')
    expect(wrapper.get('.msg--system').text()).toContain('tool failed')
    wrapper.unmount()
  })

  it.skip('TODO(issue-11): 清空换 chatId 后清理旧画像且旧请求不能晚到', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ profile: 'old' })); vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(ChatConsoleView, { props: { moduleId: 'chat' } })
    await mode(wrapper, '长期记忆').trigger('click'); await buttonByText(wrapper, '长期用户画像').trigger('click')
    await buttonByText(wrapper, '查看画像').trigger('click'); await settle()
    await wrapper.get('.chat__textarea').setValue('seed'); await buttonByText(wrapper, '发送').trigger('click'); await settle()
    await buttonByText(wrapper, '清空').trigger('click')
    expect(wrapper.find('.chat__memory-pre').exists()).toBe(false)
    wrapper.unmount()
  })
})
```

关键断言：请求检查 query/body 的精确分离；成功不是只看“有文本”，还检查消息角色、streaming 结束、XSS handler 被剥；停止检查 signal 与部分文本。

### 7.8 RAG 专用交互

放置路径：`capability-showcase-frontend/src/modules/rag/RagWorkspaceView.interaction.test.ts`

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RagWorkspaceView from './RagWorkspaceView.vue'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import { buttonByText, cleanup, deferred, jsonResponse, RouterLinkStub, setupCatalog } from '../../test/interactionHarness'

const doc = (id: string, name = id) => ({
  docId: id, tenantId: 'acme', displayName: name, contentType: 'text/plain', sizeBytes: 1536,
  segmentCount: 2, version: 1, uploadedAt: '2026-07-17T00:00:00Z', category: 'policy',
})
const config = (publicEnabled = false) => ({
  contractVersion: 2, publicEnabled, sharedImagesSupported: false,
  rag: { embeddingProvider: 'ollama', embeddingModel: 'nomic', semantic: true,
    vectorStoreProvider: 'qdrant', esHybridEnabled: true, fusionStrategy: 'rrf', graphEnabled: true,
    keywordHybridEnabled: true, multimodalEnabled: false },
})
const opts = { global: { stubs: { RouterLink: RouterLinkStub } } }

async function settle(): Promise<void> { await flushPromises(); await new Promise((r) => setTimeout(r, 0)); await flushPromises() }

describe('RagWorkspaceView interaction', () => {
  beforeEach(setupCatalog)
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); cleanup() })

  it('检索 trim 参数、按 score 排序、展示服务端 visibility 与命中高亮', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === '/rag/config') return Promise.resolve(jsonResponse(config()))
      if (url === '/rag/query') return Promise.resolve(jsonResponse({ hits: [
        { docId: 'low', score: 0.2, text: '退款 low', visibility: 'tenant' },
        { docId: 'high', score: 0.9, text: '退款 high', visibility: 'public' },
      ] }))
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts })
    await settle()
    await wrapper.get('[aria-label="检索查询"]').setValue(' 退款 ')
    const nums = wrapper.findAll('.rag__params input[type="number"]')
    await nums[0].setValue('7'); await nums[1].setValue('0.3')
    await wrapper.get('.rag__params input[type="text"]').setValue(' policy ')
    await buttonByText(wrapper, '检索').trigger('click'); await settle()
    const call = fetchMock.mock.calls.find(([url]) => url === '/rag/query')! as [string, RequestInit]
    expect(JSON.parse(String(call[1].body))).toEqual({ query: '退款', topK: 7, minScore: 0.3, category: 'policy' })
    expect(wrapper.findAll('.rag__id').map((n) => n.text()).slice(-2)).toEqual(['high', 'low'])
    expect(wrapper.findAll('.rag__vis').some((n) => n.text() === '共享')).toBe(true)
    expect(wrapper.findAll('.rag__mark').map((n) => n.text())).toContain('退款')
    wrapper.unmount()
  })

  it('检索空数组、不可解析响应、HTTP 错误走三个不同 UI 分支', async () => {
    const responses = [jsonResponse({ hits: [] }), jsonResponse({ answer: 'raw-only' }), jsonResponse({ message: 'bad query' }, 400)]
    let index = 0
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) =>
      Promise.resolve(url === '/rag/config' ? jsonResponse(config()) : responses[index++])))
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts }); await settle()
    for (const expected of ['无命中', 'raw-only', 'bad query']) {
      await wrapper.get('[aria-label="检索查询"]').setValue(`q${index}`)
      await buttonByText(wrapper, '检索').trigger('click'); await settle()
      expect(wrapper.text()).toContain(expected)
    }
    wrapper.unmount()
  })

  it('文档 list→分页→详情→删除：URL/状态串联正确', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url === '/rag/config') return Promise.resolve(jsonResponse(config()))
      if (url === '/rag/documents?page=1&size=10') return Promise.resolve(jsonResponse({ items: [doc('a/b ?中#', 'Policy')], page: 1, size: 10, total: 11, totalPages: 2 }))
      if (url === '/rag/documents?page=2&size=10') return Promise.resolve(jsonResponse({ items: [doc('p2')], page: 2, size: 10, total: 11, totalPages: 2 }))
      if (url.startsWith('/rag/documents/a%2Fb%20%3F%E4%B8%AD%23')) {
        return Promise.resolve(init?.method === 'DELETE' ? jsonResponse({ deleted: true }) : jsonResponse(doc('a/b ?中#')))
      }
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts }); await settle()
    await buttonByText(wrapper, '刷新文档').trigger('click'); await settle()
    expect(wrapper.text()).toContain('Policy')
    expect(wrapper.text()).toContain('第 1 / 2 页')
    await buttonByText(wrapper, '详情').trigger('click'); await settle()
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('a%2Fb%20%3F%E4%B8%AD%23'))).toBe(true)
    expect(wrapper.text()).toContain('contentType')
    await buttonByText(wrapper, '删除').trigger('click')
    await buttonByText(wrapper, '确认删除').trigger('click'); await settle()
    const del = fetchMock.mock.calls.find(([url, init]) => String(url).includes('a%2Fb') && (init as RequestInit).method === 'DELETE')
    expect(del).toBeTruthy()
    expect(wrapper.find('.rag__detail').exists()).toBe(false)
    wrapper.unmount()
  })

  it('共享 tab 双控后请求 visibility=public，tenant 慢响应不得覆盖 public 新结果', async () => {
    const tenant = deferred<Response>(); const pub = deferred<Response>()
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === '/rag/config') return Promise.resolve(jsonResponse(config(true)))
      if (url === '/rag/documents?page=1&size=10') return tenant.promise
      if (url === '/rag/documents?visibility=public&page=1&size=10') return pub.promise
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts }); await settle()
    await buttonByText(wrapper, '刷新文档').trigger('click')
    await buttonByText(wrapper, '共享知识库').trigger('click')
    pub.resolve(jsonResponse({ items: [doc('public-new')], page: 1, size: 10, total: 1, totalPages: 1 })); await settle()
    tenant.resolve(jsonResponse({ items: [doc('tenant-old')], page: 1, size: 10, total: 1, totalPages: 1 })); await settle()
    expect(wrapper.text()).toContain('public-new')
    expect(wrapper.text()).not.toContain('tenant-old')
    expect(wrapper.get('.rag__tab.active').text()).toContain('共享')
    wrapper.unmount()
  })

  it('两个详情请求乱序时保留最后点击的文档', async () => {
    const first = deferred<Response>(); const second = deferred<Response>()
    const fetchMock = vi.fn().mockImplementation((url: string) => {
      if (url === '/rag/config') return Promise.resolve(jsonResponse(config()))
      if (url.includes('?page=')) return Promise.resolve(jsonResponse({ items: [doc('a'), doc('b')], page: 1, size: 10, total: 2, totalPages: 1 }))
      if (url === '/rag/documents/a') return first.promise
      if (url === '/rag/documents/b') return second.promise
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts }); await settle()
    await buttonByText(wrapper, '刷新文档').trigger('click'); await settle()
    const details = wrapper.findAll('button').filter((b) => b.text() === '详情')
    await details[0].trigger('click'); await details[1].trigger('click')
    second.resolve(jsonResponse({ docId: 'b', marker: 'NEW' })); await settle()
    first.resolve(jsonResponse({ docId: 'a', marker: 'OLD' })); await settle()
    expect(wrapper.get('.rag__detail').text()).toContain('NEW')
    expect(wrapper.get('.rag__detail').text()).not.toContain('OLD')
    wrapper.unmount()
  })

  it('上传 Runner success 回调将文档页归 1 并刷新', async () => {
    const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(
      url === '/rag/config' ? jsonResponse(config()) : jsonResponse({ items: [], page: 1, size: 10, total: 0, totalPages: 1 }),
    ))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts }); await settle()
    const upload = wrapper.findAllComponents(CapabilityRunner).find((r) => r.props('cap').id === 'rag.upload.json')!
    upload.vm.$emit('result', { cap: upload.props('cap'), data: { docId: 'new' }, status: 200 })
    await settle()
    expect(fetchMock.mock.calls.some(([url]) => url === '/rag/documents?page=1&size=10')).toBe(true)
    wrapper.unmount()
  })

  it.skip('TODO(issue-08): topK/minScore 越界时不发请求并显示字段错误', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(config())); vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts }); await settle()
    await wrapper.get('[aria-label="检索查询"]').setValue('q')
    const nums = wrapper.findAll('.rag__params input[type="number"]'); await nums[0].setValue('0'); await nums[1].setValue('2')
    await buttonByText(wrapper, '检索').trigger('click')
    expect(fetchMock.mock.calls.filter(([url]) => url === '/rag/query')).toHaveLength(0)
    expect(wrapper.text()).toContain('TopK 不能小于 1')
    wrapper.unmount()
  })

  it.skip('TODO(issue-12): 结果高亮绑定 submittedQuery，而非请求期间编辑后的 query', async () => {
    const result = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => url === '/rag/config' ? Promise.resolve(jsonResponse(config())) : result.promise))
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts }); await settle()
    await wrapper.get('[aria-label="检索查询"]').setValue('退款'); await buttonByText(wrapper, '检索').trigger('click')
    await wrapper.get('[aria-label="检索查询"]').setValue('订单')
    result.resolve(jsonResponse({ hits: [{ text: '退款政策', score: 1 }] })); await settle()
    expect(wrapper.get('.rag__mark').text()).toBe('退款')
    wrapper.unmount()
  })

  it.skip('TODO(issue-13): 当前 graph ready 时不得硬编码显示“未启用”', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(config())))
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts }); await settle()
    const graphSection = wrapper.findAll('section').find((s) => s.text().includes('GraphRAG'))!
    expect(graphSection.text()).not.toContain('未启用')
    wrapper.unmount()
  })

  it.skip('TODO(issue-15): 卸载时中止专用检索 fetch，旧响应不得继续占连接', async () => {
    let searchSignal: AbortSignal | undefined
    const never = new Promise<Response>(() => {})
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (url === '/rag/config') return Promise.resolve(jsonResponse(config()))
      searchSignal = init?.signal ?? undefined
      return never
    }))
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts }); await settle()
    await wrapper.get('[aria-label="检索查询"]').setValue('q'); await buttonByText(wrapper, '检索').trigger('click')
    wrapper.unmount()
    expect(searchSignal?.aborted).toBe(true)
  })
})
```

关键断言：文档列表不只看“有行”，还精确检查 visibility/page/size、编码后的 id、删除 method、乱序结果；检索检查提交 body、排序、服务端 visibility 和高亮来源。

### 7.9 Agent Lab 与步骤时间线

放置路径：`capability-showcase-frontend/src/modules/agent/AgentLabView.interaction.test.ts`

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AgentLabView from './AgentLabView.vue'
import { buttonByText, cleanup, jsonResponse, RouterLinkStub, setupCatalog, sseResponse } from '../../test/interactionHarness'

const opts = { global: { stubs: { RouterLink: RouterLinkStub } } }
const CASES = [
  ['同步 ReAct', '/agent/run', 'goal'], ['异步', '/agent/run/async', 'goal'],
  ['DAG', '/agent/dag/run', 'goal'], ['自动规划', '/agent/dag/plan-run', 'goal'],
  ['DAG 异步', '/agent/dag/run/async', 'goal'], ['规划异步', '/agent/dag/plan-run/async', 'goal'],
  ['链式', '/agent/chain', 'input'], ['投票', '/agent/vote', 'question'],
  ['反思', '/agent/reflexive', 'question'], ['反思流式', '/agent/reflexive/stream', 'question'],
  ['数据分析', '/agent/analyst/run', 'goal'], ['数据分析·异步', '/agent/analyst/run/async', 'goal'],
  ['业务流程', '/agent/process/run', 'goal'], ['业务流程·异步', '/agent/process/run/async', 'goal'],
] as const

function mode(wrapper: ReturnType<typeof mount>, exact: string) {
  const value = wrapper.findAll('.ag__mode').find((b) => b.text().replace(/SSE|async|未启用/g, '').trim() === exact)
  if (!value) throw new Error(`mode not found: ${exact}`)
  return value
}
async function settle(): Promise<void> { await flushPromises(); await new Promise((r) => setTimeout(r, 0)); await flushPromises() }

describe('AgentLabView interaction', () => {
  beforeEach(setupCatalog)
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); cleanup() })

  it.each(CASES)('%s 映射到 %s 且 primary field 是 %s', async (label, path, primary) => {
    const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(
      url === '/agent/reflexive/stream'
        ? sseResponse(['event: answer\ndata: {"n":1,"answer":"A"}\n\nevent: done\ndata: {"finalAnswer":"A","attempts":[],"acceptedByThreshold":true}\n\n'])
        : jsonResponse(url.endsWith('/async') ? { taskId: 'job-1' } : { answer: 'ok' }),
    ))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    if (label !== '同步 ReAct') await mode(wrapper, label).trigger('click')
    await wrapper.get('[aria-label="目标输入"]').setValue(' 目标文本 ')
    if (wrapper.find('.ag__tasks').exists()) await wrapper.get('.ag__tasks').setValue('[{"id":"t1","goal":"sub"}]')
    if (wrapper.find('.ag__advanced input[type="text"]').exists()) {
      await wrapper.get('.ag__advanced input[type="text"]').setValue('https://example.test/hook')
    }
    if (wrapper.find('.ag__advanced input[type="number"]').exists()) {
      await wrapper.get('.ag__advanced input[type="number"]').setValue('4')
    }
    await buttonByText(wrapper, label === '反思流式' ? '开始流式' : '执行').trigger('click'); await settle()
    const call = fetchMock.mock.calls.find(([url]) => url === path)! as [string, RequestInit]
    expect(call).toBeTruthy()
    const body = JSON.parse(String(call[1].body)) as Record<string, unknown>
    expect(body[primary]).toBe('目标文本')
    for (const other of ['goal', 'input', 'question'].filter((x) => x !== primary)) expect(body[other]).toBeUndefined()
    if (path.includes('/dag/run')) expect(body.tasks).toEqual([{ id: 't1', goal: 'sub' }])
    if (path === '/agent/vote') expect(body.n).toBe(4)
    wrapper.unmount()
  })

  it('同步 steps/finalAnswer 显示领域时间线，原始响应可展开', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      answer: '最终结论', steps: [{ thought: '分析', action: 'order_query', actionInput: { id: 1 }, observation: 'ok' }], extra: 9,
    })))
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    await wrapper.get('[aria-label="目标输入"]').setValue('查订单'); await buttonByText(wrapper, '执行').trigger('click'); await settle()
    expect(wrapper.text()).toContain('最终结论')
    expect(wrapper.text()).toContain('order_query')
    expect(wrapper.findAll('.st__item')).toHaveLength(1)
    await buttonByText(wrapper, '原始响应').trigger('click')
    expect(wrapper.text()).toContain('extra')
    wrapper.unmount()
  })

  it('异步成功必须抽取 taskId 并进入已提交面板，不误画 steps', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ taskId: 'task-42', status: 'PENDING' })))
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    await mode(wrapper, '异步').trigger('click'); await wrapper.get('[aria-label="目标输入"]').setValue('g')
    await buttonByText(wrapper, '执行').trigger('click'); await settle()
    expect(wrapper.get('.ag__async').text()).toContain('task-42')
    expect(wrapper.find('.ag__steps').exists()).toBe(false)
    expect(wrapper.get('a[data-to]').attributes('data-to')).toContain('agent.tasks.stream')
    wrapper.unmount()
  })

  it('反思 SSE 逐阶段渲染答案/评分/最终答案并完成', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event: attempt-start\ndata: {"n":1}\n\n',
      'event: answer\ndata: {"n":1,"answer":"draft"}\n\n',
      'event: critique\ndata: {"n":1,"aggregate":0.8,"correctness":0.8,"completeness":0.8,"clarity":0.8,"mainIssue":"more"}\n\n',
      'event: done\ndata: {"finalAnswer":"final","attempts":[],"acceptedByThreshold":true}\n\n',
    ])))
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    await mode(wrapper, '反思流式').trigger('click'); await wrapper.get('[aria-label="目标输入"]').setValue('q')
    await buttonByText(wrapper, '开始流式').trigger('click'); await settle()
    expect(wrapper.text()).toContain('draft')
    expect(wrapper.text()).toContain('80%')
    expect(wrapper.text()).toContain('final')
    expect(wrapper.text()).toContain('已完成')
    wrapper.unmount()
  })

  it('HTTP 错误走 ResponseViewer 且保留状态码，不出现异步成功面板', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ message: 'agent unavailable' }, 503)))
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    await wrapper.get('[aria-label="目标输入"]').setValue('g'); await buttonByText(wrapper, '执行').trigger('click'); await settle()
    expect(wrapper.text()).toContain('agent unavailable')
    expect(wrapper.text()).toContain('503')
    expect(wrapper.find('.ag__async').exists()).toBe(false)
    wrapper.unmount()
  })

  it('当前 process 能力是 ready：按钮不带 is-off 且确实可调用', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ answer: 'ok' })); vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    const process = mode(wrapper, '业务流程'); expect(process.classes()).not.toContain('is-off')
    await process.trigger('click'); await wrapper.get('[aria-label="目标输入"]').setValue('refund')
    expect(buttonByText(wrapper, '执行').attributes('disabled')).toBeUndefined()
    await buttonByText(wrapper, '执行').trigger('click'); await settle()
    expect(fetchMock.mock.calls.some(([url]) => url === '/agent/process/run')).toBe(true)
    wrapper.unmount()
  })

  it.skip('TODO(issue-07): DAG 的 goal/tasks 任一为空都禁用，非法 JSON 显示字段错误', async () => {
    const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts }); await mode(wrapper, 'DAG').trigger('click')
    await wrapper.get('[aria-label="目标输入"]').setValue('goal only')
    expect(buttonByText(wrapper, '执行').attributes('disabled')).toBeDefined()
    await wrapper.get('.ag__tasks').setValue('{bad')
    expect(wrapper.text()).toContain('不是合法的 JSON')
    expect(fetchMock).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it.skip('TODO(issue-08): vote n=0/10 不发请求', async () => {
    const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts }); await mode(wrapper, '投票').trigger('click')
    await wrapper.get('[aria-label="目标输入"]').setValue('q'); await wrapper.get('input[type="number"]').setValue('0')
    expect(buttonByText(wrapper, '执行').attributes('disabled')).toBeDefined()
    expect(fetchMock).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it.skip('TODO(issue-09): 切模式清理旧结果，不能按新 async 模式解释旧 id', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ id: 'old', steps: ['s'] })))
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    await wrapper.get('[aria-label="目标输入"]').setValue('g'); await buttonByText(wrapper, '执行').trigger('click'); await settle()
    await mode(wrapper, '异步').trigger('click')
    expect(wrapper.find('.ag__async').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('old')
    wrapper.unmount()
  })
})
```

放置路径：`capability-showcase-frontend/src/modules/agent/AgentStepTimeline.test.ts`

```ts
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import AgentStepTimeline from './AgentStepTimeline.vue'
import { cleanup } from '../../test/interactionHarness'

describe('AgentStepTimeline', () => {
  afterEach(() => cleanup())

  it('string、known aliases 与 primitive 按原顺序编号', () => {
    const wrapper = mount(AgentStepTimeline, { props: { steps: [
      'start', { name: 'tool', reasoning: 'think', toolName: 'lookup', arguments: { id: 1 }, output: 'ok' }, null,
    ] } })
    expect(wrapper.findAll('.st__node').map((n) => n.text())).toEqual(['1', '2', '3'])
    expect(wrapper.text()).toContain('start')
    expect(wrapper.text()).toContain('think')
    expect(wrapper.text()).toContain('lookup')
    expect(wrapper.text()).toContain('{"id":1}')
    wrapper.unmount()
  })

  it('未知对象不臆造字段，交给 JsonView 原样展示', () => {
    const wrapper = mount(AgentStepTimeline, { props: { steps: [{ custom: { nested: true } }] } })
    expect(wrapper.find('.st__json').exists()).toBe(true)
    expect(wrapper.text()).toContain('custom')
    expect(wrapper.text()).toContain('nested')
    wrapper.unmount()
  })

  it('同语义多个 alias 只取优先级最高的第一个', () => {
    const wrapper = mount(AgentStepTimeline, { props: { steps: [{ thought: 'first', reasoning: 'second' }] } })
    expect(wrapper.text()).toContain('first')
    expect(wrapper.text()).not.toContain('second')
    wrapper.unmount()
  })
})
```

关键断言：14 个专用 mode 的 primary 字段独立核对，能抓到 chain 误发 goal、vote 误发 goal、DAG tasks 未 parse 等真实映射错误；ready process 测试限定具体 `.ag__mode` 和真实 fetch，修复现有跨区域伪阳性。

### 7.10 Async Monitor 与 Timeline

放置路径：`capability-showcase-frontend/src/modules/tasks/AsyncMonitorView.interaction.test.ts`

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SseHandlers } from '../../api/sse'
import { ApiError } from '../../api/errors'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import AsyncMonitorView from './AsyncMonitorView.vue'
import { buttonByText, capability, cleanup, jsonResponse, RouterLinkStub, setupCatalog } from '../../test/interactionHarness'

const mocks = vi.hoisted(() => ({ run: vi.fn(), stream: vi.fn() }))
vi.mock('../../api/client', async (original) => ({
  ...(await original<typeof import('../../api/client')>()), runCapability: mocks.run,
}))
vi.mock('../../api/sse', async (original) => ({
  ...(await original<typeof import('../../api/sse')>()), streamCapability: mocks.stream,
}))

const opts = { global: { stubs: { RouterLink: RouterLinkStub } } }
const runResult = (data: unknown) => ({ status: 200, data, headers: new Headers() })
async function settle(): Promise<void> { await flushPromises(); await Promise.resolve(); await flushPromises() }

function seed(wrapper: ReturnType<typeof mount>, status = 'RUNNING'): void {
  wrapper.findComponent(CapabilityRunner).vm.$emit('result', {
    cap: capability('async.create'), data: { taskId: 't-1', kind: 'agent.run', status }, status: 200,
  })
}
function timelineButton(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('.tl__btn').find((b) => b.text().includes(text))
  if (!button) throw new Error(`timeline button missing: ${text}`)
  return button
}

describe('AsyncMonitorView interaction', () => {
  beforeEach(() => { setupCatalog(); mocks.run.mockReset(); mocks.stream.mockReset() })
  afterEach(() => { vi.restoreAllMocks(); cleanup() })

  it('Runner create/update 结果按 taskId upsert，不产生重复 timeline item', async () => {
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.create' }, ...opts })
    seed(wrapper, 'PENDING'); await settle()
    wrapper.findComponent(CapabilityRunner).vm.$emit('result', {
      cap: capability('async.status.update'), data: { taskId: 't-1', kind: 'agent.run', status: 'RUNNING' }, status: 200,
    }); await settle()
    expect(wrapper.findAll('.tl__item')).toHaveLength(1)
    expect(wrapper.get('.tl__item').text()).toContain('RUNNING')
    expect(wrapper.get('.tl__item').text()).toContain('agent.run')
    wrapper.unmount()
  })

  it('刷新详情调用 async.get 并更新状态；失败只挂 error', async () => {
    mocks.run.mockResolvedValueOnce(runResult({ taskId: 't-1', status: 'SUCCEEDED', kind: 'agent.run' }))
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.get' }, ...opts })
    seed(wrapper); await settle(); await timelineButton(wrapper, '刷新').trigger('click'); await settle()
    expect(mocks.run).toHaveBeenCalledWith(capability('async.get'), { taskId: 't-1' }, expect.any(Object))
    expect(wrapper.get('.tl__item').text()).toContain('SUCCEEDED')
    mocks.run.mockRejectedValueOnce(new ApiError(404, 'not found', { message: 'gone' }))
    await timelineButton(wrapper, '刷新').trigger('click'); await settle()
    expect(wrapper.get('.tl__error').text()).toContain('gone')
    wrapper.unmount()
  })

  it('cancel 200 响应无 status 时按后端契约投影 CANCELLED；失败不强写', async () => {
    mocks.run.mockResolvedValueOnce(runResult({ taskId: 't-1', cancelled: true }))
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.cancel' }, ...opts })
    seed(wrapper); await settle(); await timelineButton(wrapper, '取消').trigger('click'); await settle()
    expect(mocks.run).toHaveBeenCalledWith(capability('async.cancel'), { taskId: 't-1' }, expect.any(Object))
    expect(wrapper.get('.tl__item').text()).toContain('CANCELLED')
    wrapper.unmount()

    setupCatalog(); mocks.run.mockRejectedValueOnce(new ApiError(404, 'gone', { message: 'already terminal' }))
    const failed = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.cancel' }, ...opts })
    seed(failed); await settle(); await timelineButton(failed, '取消').trigger('click'); await settle()
    expect(failed.get('.tl__item').text()).toContain('RUNNING')
    expect(failed.get('.tl__error').text()).toContain('already terminal')
    failed.unmount()
  })

  it('SSE 状态事件进入同一 task，记录 event id/error，done 关闭 streaming', async () => {
    let handlers!: SseHandlers
    const abort = vi.fn()
    mocks.stream.mockImplementation((_cap, _values, _ctx, h: SseHandlers) => { handlers = h; return { abort } })
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.stream' }, ...opts })
    seed(wrapper, 'PENDING'); await settle(); await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    expect(mocks.stream).toHaveBeenCalledWith(capability('async.stream'), { taskId: 't-1' }, expect.any(Object), expect.any(Object))
    handlers.onEvent?.({ event: 'RUNNING', id: 'evt-42', data: '{"taskId":"t-1","status":"RUNNING"}' })
    await settle()
    expect(wrapper.get('.tl__item').text()).toContain('RUNNING')
    expect(wrapper.get('.tl__item').text()).toContain('续订点 evt-42')
    expect(wrapper.get('.tl__events').text()).toContain('1 事件')
    handlers.onNamed?.('error', 'worker lost'); handlers.onDone?.('error'); await settle()
    expect(wrapper.get('.tl__error').text()).toContain('worker lost')
    expect(timelineButton(wrapper, 'SSE 订阅').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('卸载中止所有仍登记的流 handle', async () => {
    const abort = vi.fn(); mocks.stream.mockReturnValue({ abort })
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.stream' }, ...opts })
    seed(wrapper); await settle(); await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    wrapper.unmount()
    expect(abort).toHaveBeenCalledOnce()
  })

  it('deadletter 数组/信封渲染表，空数组渲染空态，未知对象走 raw fallback', async () => {
    for (const [data, expected] of [
      [{ items: [{ id: 'd1', taskId: 't1' }] }, 'd1'], [[], '死信队列为空'], [{ count: 9 }, 'count'],
    ] as const) {
      setupCatalog(); mocks.run.mockReset(); mocks.run.mockResolvedValue(runResult(data))
      const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks' }, ...opts })
      await buttonByText(wrapper, '加载死信').trigger('click'); await settle()
      expect(wrapper.text()).toContain(expected)
      wrapper.unmount()
    }
  })

  it.skip('TODO(issue-04): 手动第二次订阅携带上次 lastEventId', async () => {
    const handlers: SseHandlers[] = []
    mocks.stream.mockImplementation((_c, _v, _ctx, h: SseHandlers) => { handlers.push(h); return { abort: vi.fn() } })
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.stream' }, ...opts })
    seed(wrapper); await settle(); await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    handlers[0].onEvent?.({ event: 'RUNNING', id: '42', data: '{}' }); handlers[0].onDone?.('complete'); await settle()
    await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    const futureOptions = (mocks.stream.mock.calls[1] as unknown[])[4] as { lastEventId?: string }
    expect(futureOptions.lastEventId).toBe('42')
    wrapper.unmount()
  })

  it.skip('TODO(issue-05): 旧订阅迟到 onDone 不能关闭新订阅', async () => {
    const handlers: SseHandlers[] = []
    mocks.stream.mockImplementation((_c, _v, _ctx, h: SseHandlers) => { handlers.push(h); return { abort: vi.fn() } })
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.stream' }, ...opts })
    seed(wrapper); await settle(); await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    handlers[0].onDone?.('complete'); await settle(); await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    handlers[0].onDone?.('abort'); await settle()
    expect(timelineButton(wrapper, '流式中').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it.skip('TODO(issue-06): 单任务事件缓存有界并标记丢弃数', async () => {
    let handlers!: SseHandlers
    mocks.stream.mockImplementation((_c, _v, _ctx, h: SseHandlers) => { handlers = h; return { abort: vi.fn() } })
    const wrapper = mount(AsyncMonitorView, { props: { moduleId: 'tasks', capId: 'async.stream' }, ...opts })
    seed(wrapper); await settle(); await timelineButton(wrapper, 'SSE 订阅').trigger('click')
    for (let i = 0; i < 2005; i += 1) handlers.onEvent?.({ event: 'RUNNING', id: String(i), data: '{}' })
    await settle()
    expect(wrapper.get('.tl__events').text()).toContain('2000 事件')
    expect(wrapper.get('.tl__events').text()).toContain('已丢弃 5')
    wrapper.unmount()
  })
})
```

放置路径：`capability-showcase-frontend/src/modules/tasks/AsyncTaskTimeline.interaction.test.ts`

```ts
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import AsyncTaskTimeline from './AsyncTaskTimeline.vue'
import type { TrackedTask } from './types'
import { cleanup } from '../../test/interactionHarness'

const task = (status: string): TrackedTask => ({ taskId: `id-${status}`, status, updatedAt: '10:00:00', events: [] })

describe('AsyncTaskTimeline actions/stages', () => {
  afterEach(() => cleanup())

  it.each(['PENDING', 'RUNNING', 'LEASED', 'RETRYING'])('%s 归运行中且未到终态', (status) => {
    const wrapper = mount(AsyncTaskTimeline, { props: { tasks: [task(status)] } })
    expect(wrapper.findAll('.tl__filter').find((b) => b.text().includes('运行中'))!.text()).toContain('1')
    expect(wrapper.findAll('.tl__node').at(-1)!.attributes('data-reached')).toBe('false')
    expect(wrapper.find('.tl__btn--danger').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it.each(['SUCCEEDED', 'FAILED', 'CANCELLED'])('%s 到终态并禁用 stream/cancel，refresh 仍可用', (status) => {
    const wrapper = mount(AsyncTaskTimeline, { props: { tasks: [task(status)] } })
    const buttons = wrapper.findAll('.tl__btn')
    expect(buttons[0].attributes('disabled')).toBeDefined()
    expect(buttons[1].attributes('disabled')).toBeUndefined()
    expect(buttons[2].attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('.tl__node').at(-1)!.text()).toBe(status)
    wrapper.unmount()
  })

  it('三个 action emit 精确 task id；全局 disabled 时均不 emit', async () => {
    const wrapper = mount(AsyncTaskTimeline, { props: { tasks: [task('RUNNING')] } })
    const buttons = wrapper.findAll('.tl__btn')
    await buttons[0].trigger('click'); await buttons[1].trigger('click'); await buttons[2].trigger('click')
    expect(wrapper.emitted('stream')).toEqual([['id-RUNNING']])
    expect(wrapper.emitted('refresh')).toEqual([['id-RUNNING']])
    expect(wrapper.emitted('cancel')).toEqual([['id-RUNNING']])
    await wrapper.setProps({ disabled: true })
    await buttons[0].trigger('click'); await buttons[1].trigger('click'); await buttons[2].trigger('click')
    expect(wrapper.emitted('stream')).toHaveLength(1)
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    wrapper.unmount()
  })
})
```

关键断言：cancel 的“成功投影”与“失败不投影”在同一测试里成对出现，避免只为通过；SSE 回调直接人工排序，能稳定复现旧/new handle 竞态而不靠时间。

### 7.11 Workflow 审批工作台

放置路径：`capability-showcase-frontend/src/modules/workflow/WorkflowDeskView.interaction.test.ts`

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/errors'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import WorkflowDeskView from './WorkflowDeskView.vue'
import { buttonByText, capability, cleanup, setupCatalog } from '../../test/interactionHarness'

const mocks = vi.hoisted(() => ({ run: vi.fn() }))
vi.mock('../../api/client', async (original) => ({
  ...(await original<typeof import('../../api/client')>()), runCapability: mocks.run,
}))
const result = (data: unknown, status = 200) => ({ status, data, headers: new Headers() })
const task = (assignee?: string) => ({
  taskId: 't-1', name: '退款审批', instanceId: 'wf-1', priority: 'P0-high', summary: 'refund 100', assignee,
})
async function settle(): Promise<void> { await flushPromises(); await Promise.resolve(); await flushPromises() }

describe('WorkflowDeskView interaction', () => {
  beforeEach(() => { setupCatalog(); mocks.run.mockReset() })
  afterEach(() => { vi.restoreAllMocks(); cleanup() })

  it('StartResult 只接受对象，展示 dedupe/instance/task/status/reply', async () => {
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    const runner = wrapper.findAllComponents(CapabilityRunner).find((r) => r.props('cap').id === 'workflow.refund.start')!
    runner.vm.$emit('result', { cap: capability('workflow.refund.start'), status: 200, data: {
      instanceId: 'wf-1', taskId: 't-1', status: 'PENDING_APPROVAL', reply: '待审批', deduplicated: true,
    } }); await settle()
    expect(wrapper.get('.wf__started').text()).toContain('wf-1')
    expect(wrapper.get('.wf__started').text()).toContain('t-1')
    expect(wrapper.get('.wf__started').text()).toContain('去重命中')
    expect(wrapper.get('.wf__started').text()).toContain('待审批')
    wrapper.unmount()
  })

  it('刷新待办解析数组、过滤无 taskId、自动选首项并映射优先级', async () => {
    mocks.run.mockResolvedValueOnce(result([task(), { name: 'invalid' }, null]))
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    await buttonByText(wrapper, '刷新待办').trigger('click'); await settle()
    expect(mocks.run).toHaveBeenCalledWith(capability('workflow.tasks.list'), {}, expect.any(Object))
    expect(wrapper.findAll('.wf__task')).toHaveLength(1)
    expect(wrapper.get('.wf__task').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('.wf__prio').text()).toBe('P0')
    expect(wrapper.get('.wf__prio').attributes('data-tone')).toBe('danger')
    expect(wrapper.get('.wf__detail-card').text()).toContain('refund 100')
    wrapper.unmount()
  })

  it('claim/unclaim 发送精确 taskId，成功后刷新并呈现服务端 assignee', async () => {
    mocks.run
      .mockResolvedValueOnce(result([task()]))
      .mockResolvedValueOnce(result(task('alice')))
      .mockResolvedValueOnce(result([task('alice')]))
      .mockResolvedValueOnce(result(null, 204))
      .mockResolvedValueOnce(result([task()]))
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    await buttonByText(wrapper, '刷新待办').trigger('click'); await settle()
    await buttonByText(wrapper, '认领').trigger('click'); await settle()
    expect(mocks.run.mock.calls[1].slice(0, 2)).toEqual([capability('workflow.tasks.claim'), { taskId: 't-1' }])
    expect(wrapper.get('.wf__detail-card').text()).toContain('@alice')
    await buttonByText(wrapper, '取消认领').trigger('click'); await settle()
    expect(mocks.run.mock.calls[3].slice(0, 2)).toEqual([capability('workflow.tasks.unclaim'), { taskId: 't-1' }])
    expect(wrapper.get('.wf__detail-card').text()).toContain('未认领')
    wrapper.unmount()
  })

  it('驳回保留 approved:false、trim comment，成功清输入并刷新移除已完成任务', async () => {
    mocks.run
      .mockResolvedValueOnce(result([task('alice')]))
      .mockResolvedValueOnce(result({ instanceId: 'wf-1', status: 'REJECTED', approved: false }))
      .mockResolvedValueOnce(result([]))
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    await buttonByText(wrapper, '刷新待办').trigger('click'); await settle()
    const comment = wrapper.get('[aria-label="任务 t-1 审批意见"]')
    await comment.setValue('  evidence missing  ')
    await buttonByText(wrapper, '驳回').trigger('click'); await settle()
    expect(mocks.run.mock.calls[1].slice(0, 2)).toEqual([
      capability('workflow.tasks.complete'), { taskId: 't-1', approved: false, comment: 'evidence missing' },
    ])
    expect((comment.element as HTMLTextAreaElement).value).toBe('')
    expect(wrapper.text()).toContain('没有待办任务')
    wrapper.unmount()
  })

  it('无凭证时自定义动作按钮禁用且不会调用 API', async () => {
    setupCatalog('')
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    expect(buttonByText(wrapper, '刷新待办').attributes('disabled')).toBeDefined()
    await buttonByText(wrapper, '刷新待办').trigger('click')
    expect(mocks.run).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it.skip('TODO(issue-01): claim 409 后即使 inbox 刷新成功，动作错误仍保留', async () => {
    mocks.run
      .mockResolvedValueOnce(result([task()]))
      .mockRejectedValueOnce(new ApiError(409, 'conflict', { message: '已被 bob 认领' }))
      .mockResolvedValueOnce(result([task('bob')]))
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    await buttonByText(wrapper, '刷新待办').trigger('click'); await settle()
    await buttonByText(wrapper, '认领').trigger('click'); await settle()
    expect(wrapper.get('[role="alert"]').text()).toContain('已被 bob 认领')
    expect(wrapper.get('.wf__detail-card').text()).toContain('@bob')
    wrapper.unmount()
  })

  it.skip('TODO(issue-01): complete 成功提示不被随后 refresh 清掉', async () => {
    mocks.run.mockResolvedValueOnce(result([task()])).mockResolvedValueOnce(result({ approved: true })).mockResolvedValueOnce(result([]))
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    await buttonByText(wrapper, '刷新待办').trigger('click'); await settle(); await buttonByText(wrapper, '通过').trigger('click'); await settle()
    expect(wrapper.text()).toContain('任务 t-1 已通过')
    wrapper.unmount()
  })
})
```

关键断言：`approved:false` 用深相等检查，能抓到 falsy 字段丢失；claim/unclaim 的 204/null 成功路径与服务端刷新投影都覆盖。错误 TODO 同时要求错误保留和新 assignee 更新，避免用“不刷新”掩盖问题。

### 7.12 Analytics Schema/NL2SQL

放置路径：`capability-showcase-frontend/src/modules/analytics/AnalyticsLabView.interaction.test.ts`

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/errors'
import AnalyticsLabView from './AnalyticsLabView.vue'
import { buttonByText, capability, cleanup, deferred, RouterLinkStub, setupCatalog } from '../../test/interactionHarness'

const mocks = vi.hoisted(() => ({ run: vi.fn() }))
vi.mock('../../api/client', async (original) => ({
  ...(await original<typeof import('../../api/client')>()), runCapability: mocks.run,
}))
const opts = { global: { stubs: { RouterLink: RouterLinkStub } } }
const result = (data: unknown) => ({ status: 200, data, headers: new Headers() })
async function settle(): Promise<void> { await flushPromises(); await Promise.resolve(); await flushPromises() }

describe('AnalyticsLabView interaction', () => {
  beforeEach(() => { setupCatalog(); mocks.run.mockReset() })
  afterEach(() => { vi.restoreAllMocks(); cleanup() })

  it.each([
    [['orders', 'customers'], ['orders', 'customers']],
    [{ tables: [{ table: 'orders' }, { TABLE_NAME: 'legacy' }] }, ['orders', 'legacy']],
  ])('表清单兼容 string/object/envelope：%j', async (payload, names) => {
    mocks.run.mockResolvedValueOnce(result(payload))
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await buttonByText(wrapper, '加载表清单').trigger('click'); await settle()
    expect(mocks.run).toHaveBeenCalledWith(capability('analytics.schema.tables'), {}, expect.any(Object))
    expect(wrapper.findAll('.al__table-name').map((n) => n.text())).toEqual(names)
    wrapper.unmount()
  })

  it('选择表发送原 table 值并把 array-of-objects 渲染成列结构表', async () => {
    mocks.run.mockResolvedValueOnce(result(['order/items 中'])).mockResolvedValueOnce(result([
      { name: 'id', type: 'BIGINT' }, { name: 'tenant_id', type: 'VARCHAR' },
    ]))
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await buttonByText(wrapper, '加载表清单').trigger('click'); await settle()
    await wrapper.get('.al__table-item').trigger('click'); await settle()
    expect(mocks.run.mock.calls[1].slice(0, 2)).toEqual([
      capability('analytics.schema.describe'), { table: 'order/items 中' },
    ])
    expect(wrapper.get('.al__describe').text()).toContain('tenant_id')
    expect(wrapper.findAll('.rt__tr')).toHaveLength(2)
    wrapper.unmount()
  })

  it('NL2SQL trim question，展示 generated SQL、object rows、空值和 raw 字段', async () => {
    mocks.run.mockResolvedValueOnce(result({
      question: 'q', sql: 'SELECT category, COUNT(*) c FROM docs GROUP BY category', rowCount: 2,
      rows: [{ category: 'a', c: 2 }, { category: null, c: 1 }], answer: 'done', guardBlocked: false,
    }))
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await wrapper.get('[aria-label="NL2SQL 自然语言问题"]').setValue('  count by category  ')
    await buttonByText(wrapper, '生成并执行').trigger('click'); await settle()
    expect(mocks.run).toHaveBeenCalledWith(capability('analytics.sql'), { question: 'count by category' }, expect.any(Object))
    expect(wrapper.get('.al__sql-code').text()).toContain('SELECT category')
    expect(wrapper.findAll('.rt__tr')).toHaveLength(2)
    expect(wrapper.get('.rt__table').text()).toContain('—')
    await buttonByText(wrapper, '原始响应').trigger('click')
    expect(wrapper.text()).toContain('guardBlocked')
    wrapper.unmount()
  })

  it.each([
    [{ columns: ['name', 'count'], rows: [['a', 2]] }, ['name', 'count', 'a', '2']],
    [[1, null, 'x'], ['value', '1', '—', 'x']],
    [{ rows: [] }, ['查询成功，结果集为空']],
    [{ opaque: { x: 1 } }, ['opaque']],
  ])('NL2SQL 行集/兜底形态 %j', async (payload, expected) => {
    mocks.run.mockResolvedValueOnce(result(payload))
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await wrapper.get('[aria-label="NL2SQL 自然语言问题"]').setValue('q')
    await buttonByText(wrapper, '生成并执行').trigger('click'); await settle()
    for (const text of expected) expect(wrapper.text()).toContain(text)
    wrapper.unmount()
  })

  it('schema/sql 错误各自显示，不清空另一分区的成功结果', async () => {
    mocks.run
      .mockResolvedValueOnce(result(['orders']))
      .mockRejectedValueOnce(new ApiError(404, 'missing', { message: 'table hidden' }))
      .mockRejectedValueOnce(new ApiError(503, 'down', { message: 'model offline' }))
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await buttonByText(wrapper, '加载表清单').trigger('click'); await settle(); await wrapper.get('.al__table-item').trigger('click'); await settle()
    expect(wrapper.get('.al__describe [role="alert"]').text()).toContain('table hidden')
    await wrapper.get('[aria-label="NL2SQL 自然语言问题"]').setValue('q'); await buttonByText(wrapper, '生成并执行').trigger('click'); await settle()
    expect(wrapper.text()).toContain('model offline')
    expect(wrapper.text()).toContain('orders')
    wrapper.unmount()
  })

  it('curl 预览只有占位凭证和当前 question，不泄露 test-key', async () => {
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await wrapper.get('[aria-label="NL2SQL 自然语言问题"]').setValue('safe q')
    await buttonByText(wrapper, '预览 curl').trigger('click')
    const curl = wrapper.get('.al__curl-code').text()
    expect(curl).toContain('/chat/sql')
    expect(curl).toContain('safe q')
    expect(curl).not.toContain('test-key')
    wrapper.unmount()
  })

  it.skip('TODO(issue-02): 后到的旧 describe 响应不能覆盖新表', async () => {
    const old = deferred<ReturnType<typeof result>>(); const fresh = deferred<ReturnType<typeof result>>()
    mocks.run.mockResolvedValueOnce(result(['orders', 'customers'])).mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await buttonByText(wrapper, '加载表清单').trigger('click'); await settle()
    const tables = wrapper.findAll('.al__table-item'); await tables[0].trigger('click'); await tables[1].trigger('click')
    fresh.resolve(result([{ name: 'customer_id' }])); await settle()
    old.resolve(result([{ name: 'order_id' }])); await settle()
    expect(wrapper.get('.al__describe-title').text()).toContain('customers')
    expect(wrapper.get('.al__describe').text()).toContain('customer_id')
    expect(wrapper.get('.al__describe').text()).not.toContain('order_id')
    wrapper.unmount()
  })
})
```

关键断言：NL2SQL 同时校验提交 question、生成 SQL、表格行/列/空值和 raw，避免仅看到“200”就通过；乱序测试用显式 deferred，不使用脆弱 timeout。

## 8. Flaky 风险与稳健写法

| 风险 | 本仓实际触发点 | 稳健写法 |
|---|---|---|
| catalog 构建产物漂移 | `loadCatalog()` 静态 import `public/catalog.json` | 所有命令先 `npm run gen:catalog`；不在测试内改共享对象，setup 时 `structuredClone` |
| Pinia/store 顺序依赖 | catalog/session/history/favorites 都是全局 active Pinia | 每个 `beforeEach` `setActivePinia(createPinia())`；不要在 describe 顶层调用 store |
| DOM/组件未卸载 | watcher、scope dispose、SSE handle | 每例显式 `wrapper.unmount()`；`afterEach document.body.replaceChildren()` 作为兜底 |
| global fetch/mock 泄漏 | 所有 API/SSE | `vi.unstubAllGlobals()` + `vi.restoreAllMocks()`；每例重新定义 route-aware fetch |
| 时间/locale | chat/history elapsed、Workflow `toLocaleTimeString`、Async updatedAt | 不断言本地化精确字符串；需要 elapsed 时 stub `Date.now`；不要依赖机器时区 |
| crypto/random | chatId/history id | 只在需要断言 chatId 的文件 stub `crypto.randomUUID`，afterEach 恢复；其它测试断言形态而非随机值 |
| fake timers 卡住 ReadableStream | SSE 消费是 reader Promise/microtask，不是定时器 | 用可控 stream controller/deferred；优先 condition polling，若用 fake timer 必须 `useRealTimers` |
| 响应顺序 mock 易碎 | onMounted 的 `/rag/config` 会先调用 fetch | 按 URL/method 路由 mock；仅业务本身要求顺序时使用 `mockResolvedValueOnce`，并断言 call cap id |
| 模块缓存污染 feature flag | `SHARED_KB_UI_ENABLED`、`AUTH_MODE` 是 import-time 常量 | 构建 flag-off 用单独测试文件 + `vi.resetModules()`/顶层 `vi.mock('../../config')`，不要同文件动态改 env |
| Response body 只能读一次 | `client/readBody`、SSE handshake error | 每次请求创建新的 Response；不要复用同一 Response 实例 |
| race 测试依赖毫秒延迟 | Analytics/RAG/Async known races | deferred Promise + 手工 resolve 顺序；禁止 `setTimeout(100)` 猜时序 |
| 本地存储污染 | favorites 初始化时立即读取 localStorage | 每例先 `localStorage.clear()` 再创建 Pinia/store；异常 spy 恢复 |
| SSE 大量事件导致测试慢 | 2005 帧逐 Response pull | 共享状态机只测上限边界 2005；Async 未修前 TODO skip；不做 100k DOM 集成 |
| selector 跨区误命中 | 现有 Agent 测试整页查“未启用”造成伪阳性 | 限定 `.ag__mode/.ag__gate/.runner__reason/[role=alert]`，请求行为再由 fetch 佐证 |

Java 项目的 TenantContext ThreadLocal、H2 库名、Spring static context 在本范围不存在；其前端等价风险是 active Pinia、module mock cache、DOM 与 global fetch 泄漏，已在上表处理。

## 9. 待验证信息

1. **共享 KB 构建期开关 off 文件拆分**：当前 `vitest.config.ts` 未固定 `VITE_SHARED_KB_UI_ENABLED`，默认 true。若要自动覆盖 build-off，建议新增独立 `RagWorkspaceView.shared-off.test.ts`，顶层 mock config 后动态 import SFC；不能与普通 RAG 文件共享模块缓存。此项草案未纳入主文件以避免 mock 污染，落地时待验证 Vite SFC 动态 import 缓存。
2. **专用请求卸载策略**：卸载必须 abort 是推荐基线；“切换 tab/表时是否立即 abort 旧请求”与“允许后台完成但丢弃结果”需产品确认。无论选择哪种，旧响应不得写 UI。
3. **SSE 手动续订 API 形态**：issue-04 的 TODO 假定未来给 `streamCapability` 增加第 5 个 options 参数；也可改为 RunContext 扩展或 Monitor 层去重。测试的行为目标确定，具体签名待实现选择。
4. **RAG 数字约束**：UI 已写 topK 1..50/minScore 0..1，故蓝图把这些当验收；若后端允许其它范围，应先更新目录 ParamSpec 和 UI，而不是弱化测试。
5. **Agent DAG tasks 形状**：目录标 `type:json, required:true`，UI placeholder 是数组。蓝图至少要求合法 JSON 且字段必填；是否强制 Array 需与后端 record 再确认后补断言。
6. **GraphRAG 当前状态**：本次 `npm run gen:catalog` 事实为 ready；部署若通过 live discovery 改为 off，UI 应依 capability state 动态呈现。测试不能固定永远 ready，只固定 manifest 基线并另测 gate off clone。

## 10. 运行与验证命令

全部从仓库根执行：

```bash
cd capability-showcase-frontend
npm run gen:catalog

# 先跑共享状态机/57 合同
npx vitest run src/components/capability/CapabilityRunner.interaction.test.ts
npx vitest run src/composables/useCapabilityRun.test.ts src/composables/useAbortable.test.ts
npx vitest run src/components/capability/SseConsole.test.ts src/stores/history.test.ts src/stores/favorites.test.ts

# 再跑领域工作台
npx vitest run src/modules/chat/ChatConsoleView.interaction.test.ts
npx vitest run src/modules/rag/RagWorkspaceView.interaction.test.ts
npx vitest run src/modules/agent/AgentLabView.interaction.test.ts src/modules/agent/AgentStepTimeline.test.ts
npx vitest run src/modules/tasks/AsyncMonitorView.interaction.test.ts src/modules/tasks/AsyncTaskTimeline.interaction.test.ts
npx vitest run src/modules/workflow/WorkflowDeskView.interaction.test.ts
npx vitest run src/modules/analytics/AnalyticsLabView.interaction.test.ts

# 全量与类型检查
npm test
npm run type-check
```

聚焦单例：

```bash
npx vitest run src/modules/workflow/WorkflowDeskView.interaction.test.ts -t "claim 409"
npx vitest run src/modules/analytics/AnalyticsLabView.interaction.test.ts -t "后到的旧 describe"
npx vitest run src/composables/useCapabilityRun.test.ts -t "reset 后旧流"
```

本前端模块不使用 `mvn -pl ...`，也不需要 `INTERNAL_JWT_SECRET`。

## 11. Test-judge 复审记录

已按“克服只为通过”重新审查并作以下修正：

1. 重新运行生成器并按实际目录计数，确认是 57，不依赖题面手工列表计数。
2. 发现 `rag.graph.*`、`agent.process.*`、`analytics.sql` 当前 ready；删除“它们必然 flag-off”的错误断言。特别指出现有 Agent 测试的整页文本伪阳性。
3. 追到后端 `AsyncTaskController.cancel`，确认成功响应不含 status，未把必要的 CANCELLED 投影误报为 bug；新增成功/失败成对断言。
4. 所有疑似 bug 的草案改为 `it.skip('TODO(issue-xx)')` 并断言期望，不反向锁定当前错误结果。
5. CONTRACT 不使用 `assembleRequest` 生成 expected method/path 自证，而是维护独立 57 项表；fetch init 同时检查 method/URL/header/body。
6. 将“组件存在/文字出现”的弱断言替换为作用域选择器、请求 call、响应 DOM 和终态组合断言。
7. SSE 使用真实 `ReadableStream` 分 chunk；abort 检查实际 signal，竞态用 deferred/handler 顺序而非 sleep。
8. 增加每例 Pinia/DOM/global/localStorage 清理，规避现有部分测试未 unmount 的顺序污染。
9. 明确 Java 铁律不适用并使用 `ts`，避免按模板误产 `src/test/java`/Maven 命令。
10. 未声明草案当前已在 `src/` 编译：本任务禁止向 `src/` 写入。代码按当前导出签名编写；真正落地 Agent 必须先逐文件落盘，再执行 `npm run type-check` 和聚焦 Vitest，若 SFC wrapper 推断出现窄类型差异，只允许类型修正，不得弱化行为断言。

## 12. 最终验收清单

- [ ] 只新增/修改测试与测试辅助文件，业务代码修复另开变更并对应 issue；本蓝图本身未改业务代码。
- [ ] `npm run gen:catalog` 后 57 项合同表完全匹配。
- [ ] CONTRACT 中 56 个当前可执行能力实际产生一次预期 fetch；`chat.mcp` 0 fetch；purge 未确认 0 fetch、确认 1 fetch。
- [ ] JSON/multipart/none/SSE 的 method/path/query/body/header 均有真实断言，且无 `tenantId` 串味。
- [ ] 六模块 success/error/abort/empty/fallback/状态切换关键路径闭合。
- [ ] RAG/Analytics/Async/useCapabilityRun 的乱序、重订阅、reset、卸载均有 deterministic 回归；未修 issue 保持 TODO，不伪绿。
- [ ] Workflow false boolean/comment/409 消息、Async cancel success/failure、Agent DAG required/mode reset、Chat partial token/画像隔离均按预期验收。
- [ ] 每个 wrapper unmount，afterEach 清 DOM、global mock、timer、storage spy；全套可重复运行两次结果一致。
- [ ] `npm test` 全绿（允许有明确 issue 编号的 skip，数量必须受控并在缺陷修复 PR 中清零）。
- [ ] `npm run type-check` 全绿。
- [ ] `02-coverage-matrix.md` 的关键缺口全部转为已有测试，未覆盖项均写“待验证”及责任人/后续动作。
- [ ] `03-suspected-issues.md` 已单列上报，任何疑似 bug 都没有被写成“正确现状”断言。
