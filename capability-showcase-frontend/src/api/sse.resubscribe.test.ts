import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import type { Capability } from '../types/catalog'
import { streamCapability } from './sse'

function cap(): Capability {
  return {
    id: 'x', module: 'm', title: 't', description: '', method: 'POST', path: '/chat/stream',
    params: [], requiredScopes: [], riskLevel: 'safe', state: 'ready', executableByDefault: true,
    requestKind: 'sse',
  }
}

const enc = new TextEncoder()
/** 构造一个逐块吐出的 ReadableStream；errorAtEnd=true 时在放完后 error（模拟中途断流）。 */
function streamOf(chunks: string[], errorAtEnd = false): ReadableStream<Uint8Array> {
  let i = 0
  return new ReadableStream({
    pull(ctrl) {
      if (i < chunks.length) ctrl.enqueue(enc.encode(chunks[i++]))
      else if (errorAtEnd) ctrl.error(new Error('network drop'))
      else ctrl.close()
    },
  })
}
function res(status: number, body: ReadableStream<Uint8Array> | null, json?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    headers: new Headers(),
    body,
    text: async () => JSON.stringify(json ?? {}),
    json: async () => json ?? {},
  } as unknown as Response
}

const REFRESH_OK = {
  accessToken: 'tok-new',
  expiresInSeconds: 3600,
  user: { username: 'a', tenant: 't', scopes: [] },
}

beforeEach(() => setActivePinia(createPinia()))
afterEach(() => vi.unstubAllGlobals())

describe('streamCapability —— 中途断流续订（DR-1）', () => {
  it('bearer 流中途 error → 续期 + Last-Event-ID 续订 → 无缝 complete', async () => {
    const calls: Array<{ url: string; headers: Headers }> = []
    const f = vi.fn((url: unknown, init?: RequestInit) => {
      const headers = new Headers(init?.headers as HeadersInit | undefined)
      calls.push({ url: String(url), headers })
      if (String(url).includes('/auth/refresh')) return Promise.resolve(res(200, null, REFRESH_OK))
      // 首次业务请求（无 Last-Event-ID）：发一个带 id 的事件后断流。
      if (!headers.get('Last-Event-ID')) {
        return Promise.resolve(res(200, streamOf(['id: 5\ndata: hello\n\n'], true)))
      }
      // 续订（带 Last-Event-ID）：正常发完并结束。
      return Promise.resolve(res(200, streamOf(['data: world\n\n'])))
    })
    vi.stubGlobal('fetch', f)

    const tokens: string[] = []
    let doneReason = ''
    let errored = false
    await new Promise<void>((resolve) => {
      streamCapability(cap(), {}, { apiKey: '', accessToken: 'tok-old', edgeBaseUrl: '' }, {
        onToken: (t) => tokens.push(t),
        onError: () => {
          errored = true
        },
        onDone: (r) => {
          doneReason = r
          resolve()
        },
      })
    })

    // 断流被透明恢复：两段 token 连续到达、最终 complete、上层未收到 onError。
    expect(tokens).toEqual(['hello', 'world'])
    expect(doneReason).toBe('complete')
    expect(errored).toBe(false)
    // 续订请求带上了 Last-Event-ID 与新 token。
    const resub = calls.find((c) => c.headers.get('Last-Event-ID') === '5')
    expect(resub).toBeTruthy()
    expect(resub?.headers.get('Authorization')).toBe('Bearer tok-new')
  })
})
