import { describe, it, expect, vi } from 'vitest'
import type { Capability } from '../types/catalog'
import { toCurl } from './curl'

// oidc 模式：curl 预览凭证头改为 Bearer 占位符（绝不含真实 token）。toCurl 调用时读 AUTH_MODE(getter)。
const cfg = vi.hoisted(() => ({ mode: 'oidc' as 'apikey' | 'oidc' | 'dual' }))
vi.mock('../config', async (orig) => ({
  ...(await orig<typeof import('../config')>()),
  get AUTH_MODE() {
    return cfg.mode
  },
}))

function cap(): Capability {
  return {
    id: 'x', module: 'm', title: 't', description: '', method: 'POST', path: '/chat',
    params: [], requiredScopes: [], riskLevel: 'safe', state: 'ready', executableByDefault: true,
    requestKind: 'json',
  }
}

describe('toCurl —— oidc 模式凭证头', () => {
  it('oidc：出 Bearer 占位符、不出 X-Api-Key，且无真实明文', () => {
    cfg.mode = 'oidc'
    const out = toCurl(cap(), {}, { edgeBaseUrl: '' })
    expect(out).toContain(`-H 'Authorization: Bearer $ACCESS_TOKEN'`)
    expect(out).not.toContain('X-Api-Key')
  })

  it('apikey/dual：仍出 X-Api-Key 占位符', () => {
    cfg.mode = 'apikey'
    expect(toCurl(cap(), {}, {})).toContain(`-H 'X-Api-Key: $API_KEY'`)
    cfg.mode = 'dual'
    expect(toCurl(cap(), {}, {})).toContain(`-H 'X-Api-Key: $API_KEY'`)
  })
})
