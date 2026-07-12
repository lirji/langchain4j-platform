import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { Catalog } from '../types/catalog'
import { mergeLive, tagCatalogSource } from '../api/catalog'
import { useCatalogStore } from './catalog'
import { useSessionStore } from './session'

function sampleCatalog(): Catalog {
  return {
    version: '1',
    modules: [
      {
        id: 'agent',
        title: '智能体',
        description: '',
        order: 3,
        priority: 'P0',
        service: 'agent-service',
        standalone: 'high',
        capabilities: [
          {
            id: 'agent.run',
            module: 'agent',
            title: '同步跑目标',
            description: '',
            method: 'POST',
            path: '/agent/run',
            requestKind: 'json',
            params: [],
            requiredScopes: [],
            riskLevel: 'safe',
            state: 'ready',
            executableByDefault: true,
          },
          {
            id: 'agent.tasks.list',
            module: 'agent',
            title: '任务列表',
            description: '',
            method: 'GET',
            path: '/agent/tasks',
            requestKind: 'none',
            params: [],
            requiredScopes: [],
            riskLevel: 'safe',
            state: 'ready',
            executableByDefault: true,
          },
        ],
      },
    ],
  }
}

describe('纯函数：tagCatalogSource / mergeLive', () => {
  it('tagCatalogSource 标记全部能力为 manifest', () => {
    const tagged = tagCatalogSource(sampleCatalog(), 'manifest')
    expect(tagged.modules[0].capabilities.every((c) => c.source === 'manifest')).toBe(true)
  })

  it('mergeLive 仅将 id/path 命中的能力标为 live', () => {
    const base = tagCatalogSource(sampleCatalog(), 'manifest')
    const merged = mergeLive(base, [{ id: 'agent.run' }])
    const caps = merged.modules[0].capabilities
    expect(caps.find((c) => c.id === 'agent.run')?.source).toBe('live')
    expect(caps.find((c) => c.id === 'agent.tasks.list')?.source).toBe('manifest')
  })

  it('mergeLive 空描述符原样返回', () => {
    const base = tagCatalogSource(sampleCatalog(), 'manifest')
    const merged = mergeLive(base, [])
    expect(merged.modules[0].capabilities.every((c) => c.source === 'manifest')).toBe(true)
  })
})

function mockFetch(handler: (url: string) => { ok: boolean; body: unknown }) {
  return vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    const { ok, body } = handler(url)
    return Promise.resolve({
      ok,
      status: ok ? 200 : 500,
      headers: new Headers(),
      json: async () => body,
      text: async () => JSON.stringify(body),
    } as unknown as Response)
  })
}

describe('catalog store 加载与 live 回退', () => {
  beforeEach(() => setActivePinia(createPinia()))
  afterEach(() => vi.unstubAllGlobals())

  it('加载静态目录成功 → ready 且全部 manifest', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetch((url) => {
        if (url.includes('catalog.json')) return { ok: true, body: sampleCatalog() }
        return { ok: true, body: [] }
      }),
    )
    const store = useCatalogStore()
    await store.load()
    expect(store.status).toBe('ready')
    expect(store.modules.length).toBe(1)
    expect(store.capabilityById('agent.run')?.source).toBe('manifest')
  })

  it('live discovery 成功 → 命中能力标 live', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetch((url) => {
        if (url.includes('catalog.json')) return { ok: true, body: sampleCatalog() }
        if (url.endsWith('/agent/capabilities')) return { ok: true, body: [{ id: 'agent.run' }] }
        return { ok: true, body: [] }
      }),
    )
    const session = useSessionStore()
    session.setApiKey('k-123456')
    const store = useCatalogStore()
    await store.load()
    await store.refreshLive()
    expect(store.capabilityById('agent.run')?.source).toBe('live')
  })

  it('live discovery 全部失败 → 静默回退，保留 manifest 且不抛错', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetch((url) => {
        if (url.includes('catalog.json')) return { ok: true, body: sampleCatalog() }
        return { ok: false, body: null } // 所有 discovery 端点 500
      }),
    )
    const session = useSessionStore()
    session.setApiKey('k-123456')
    const store = useCatalogStore()
    await store.load()
    await expect(store.refreshLive()).resolves.toBeUndefined()
    expect(store.status).toBe('ready')
    expect(store.capabilityById('agent.run')?.source).toBe('manifest')
  })

  it('目录加载失败 → status=error', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetch(() => ({ ok: false, body: null })),
    )
    const store = useCatalogStore()
    await store.load()
    expect(store.status).toBe('error')
  })
})
