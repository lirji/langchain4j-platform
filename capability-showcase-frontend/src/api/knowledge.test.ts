import { describe, it, expect, beforeEach, vi } from 'vitest'
import type { RunContext } from '../types/api'

const calls: { url: string; init: RequestInit }[] = []
let nextResponse: () => Response
vi.mock('./authorizedFetch', () => ({
  authorizedFetch: (url: string, init: RequestInit = {}) => {
    calls.push({ url, init })
    return Promise.resolve(nextResponse())
  },
}))

import { listDocuments, listDocumentsPaged, deleteDocument, fetchRagConfig } from './knowledge'

function res(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(),
    text: async () => (body == null ? '' : JSON.stringify(body)),
  } as unknown as Response
}
function header(init: RequestInit, name: string): string | undefined {
  return ((init.headers ?? {}) as Record<string, string>)[name]
}

const bearerCtx = (): RunContext => ({ apiKey: '', accessToken: 'tok', edgeBaseUrl: '' })
const apiKeyCtx = (): RunContext => ({ apiKey: 'sk-1', accessToken: 'tok', edgeBaseUrl: '' })

beforeEach(() => {
  calls.length = 0
})

describe('api/knowledge —— 双模凭证 + visibility', () => {
  it('api-key 覆盖 Bearer（能力路径互斥）', async () => {
    nextResponse = () => res(200, [])
    await listDocuments('tenant', apiKeyCtx())
    expect(header(calls[0].init, 'X-Api-Key')).toBe('sk-1')
    expect(header(calls[0].init, 'Authorization')).toBeUndefined()
  })

  it('仅 Bearer 时以 Authorization 注入', async () => {
    nextResponse = () => res(200, [])
    await listDocuments('tenant', bearerCtx())
    expect(header(calls[0].init, 'Authorization')).toBe('Bearer tok')
  })

  it("public 列表带 ?visibility=public；tenant 不带 query（向后兼容）", async () => {
    nextResponse = () => res(200, [])
    await listDocuments('public', bearerCtx())
    expect(calls[0].url).toContain('?visibility=public')
    calls.length = 0
    await listDocuments('tenant', bearerCtx())
    expect(calls[0].url).not.toContain('visibility')
  })

  it('listDocumentsPaged tenant 带 page/size，不带 visibility', async () => {
    nextResponse = () => res(200, { items: [], page: 1, size: 10, total: 0, totalPages: 1 })
    const paged = await listDocumentsPaged('tenant', 2, 10, bearerCtx())
    expect(calls[0].url).toContain('page=2')
    expect(calls[0].url).toContain('size=10')
    expect(calls[0].url).not.toContain('visibility')
    expect(paged.totalPages).toBe(1)
  })

  it('listDocumentsPaged public 带 visibility=public + page/size', async () => {
    nextResponse = () => res(200, { items: [], page: 1, size: 20, total: 0, totalPages: 1 })
    await listDocumentsPaged('public', 1, 20, bearerCtx())
    expect(calls[0].url).toContain('visibility=public')
    expect(calls[0].url).toContain('page=1')
    expect(calls[0].url).toContain('size=20')
  })

  it('deleteDocument public 走 DELETE + visibility', async () => {
    nextResponse = () => res(200, { deleted: true })
    await deleteDocument('doc-1', 'public', bearerCtx())
    expect(calls[0].init.method).toBe('DELETE')
    expect(calls[0].url).toContain('/rag/documents/doc-1?visibility=public')
  })

  it('fetchRagConfig 解析运行时开关', async () => {
    nextResponse = () => res(200, { contractVersion: 1, publicEnabled: true, sharedImagesSupported: false })
    const cfg = await fetchRagConfig(bearerCtx())
    expect(cfg.publicEnabled).toBe(true)
    expect(cfg.sharedImagesSupported).toBe(false)
  })
})
