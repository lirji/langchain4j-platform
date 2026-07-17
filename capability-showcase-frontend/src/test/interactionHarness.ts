import { createPinia, setActivePinia } from 'pinia'
import type { VueWrapper } from '@vue/test-utils'
import { useCatalogStore } from '../stores/catalog'
import { useSessionStore } from '../stores/session'
import { loadCatalog } from './fixtures'
import type { Capability } from '../types/catalog'

/**
 * 交互测试共享夹具：真实生成目录 + 新 Pinia + 固定凭证 + Response/SSE/deferred 工厂 + 清理。
 * 所有 interaction 测试只从这里取 capability，避免手写 fixture 漂移。
 */

export const RouterLinkStub = {
  name: 'RouterLink',
  props: ['to'],
  template: '<a :data-to="String(to)"><slot /></a>',
}

export function setupCatalog(apiKey = 'test-key'): void {
  // favorites 等 store 初始化会立即读 localStorage——统一清空，杜绝用例间残留。
  localStorage.clear()
  setActivePinia(createPinia())
  // structuredClone：防止某个用例改坏静态导入的目录单例，污染后续用例。
  useCatalogStore().catalog = structuredClone(loadCatalog())
  const session = useSessionStore()
  // 归零网关基址：测试断言相对路径，不受本地 .env 的 VITE_EDGE_BASE_URL 影响。
  session.edgeBaseUrl = ''
  if (apiKey) session.setApiKey(apiKey)
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
  return new Response(text, {
    status,
    statusText: 'Failure',
    headers: { 'Content-Type': 'text/plain' },
  })
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
  const promise = new Promise<T>((ok, fail) => {
    resolve = ok
    reject = fail
  })
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
