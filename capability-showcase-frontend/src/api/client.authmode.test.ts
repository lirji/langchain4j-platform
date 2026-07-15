import { describe, it, expect, vi } from 'vitest'
import type { Capability } from '../types/catalog'

// 逼进 dual 模式：其余 config 导出保留真实值。
vi.mock('../config', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../config')>()),
  AUTH_MODE: 'dual',
}))

import { assembleRequest, API_KEY_HEADER, AUTH_HEADER } from './client'

function cap(): Capability {
  return {
    id: 'x', module: 'm', title: 't', description: '', method: 'POST', path: '/chat',
    params: [], requiredScopes: [], riskLevel: 'safe', state: 'ready', executableByDefault: true,
    requestKind: 'json',
  }
}

describe('assembleRequest —— dual 模式凭证注入', () => {
  it('api-key 与 Bearer 同时存在时【两者都带】（非互斥）', () => {
    const plan = assembleRequest(cap(), {}, { apiKey: 'real-key', accessToken: 'cas-tok', edgeBaseUrl: '' })
    expect(plan.headers[API_KEY_HEADER]).toBe('real-key')
    expect(plan.headers[AUTH_HEADER]).toBe('Bearer cas-tok')
  })

  it('仅 Bearer 时只带 Authorization', () => {
    const plan = assembleRequest(cap(), {}, { apiKey: '', accessToken: 'cas-tok', edgeBaseUrl: '' })
    expect(plan.headers[AUTH_HEADER]).toBe('Bearer cas-tok')
    expect(plan.headers[API_KEY_HEADER]).toBeUndefined()
  })

  it('仅 api-key 时只带 X-Api-Key', () => {
    const plan = assembleRequest(cap(), {}, { apiKey: 'real-key', accessToken: '', edgeBaseUrl: '' })
    expect(plan.headers[API_KEY_HEADER]).toBe('real-key')
    expect(plan.headers[AUTH_HEADER]).toBeUndefined()
  })
})
