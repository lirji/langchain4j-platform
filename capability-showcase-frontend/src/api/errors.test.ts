import { describe, it, expect } from 'vitest'
import { ApiError, humanizeError } from './errors'

describe('humanizeError —— 422 业务门禁', () => {
  it('422 展示为门禁结果（非网络/参数错误）', () => {
    const msg = humanizeError(new ApiError(422, 'HTTP 422', { gate: { passed: false } }))
    expect(msg).toContain('门禁未通过')
    expect(msg).toContain('422')
  })

  it('403 提示所需 scope', () => {
    const msg = humanizeError(new ApiError(403, 'HTTP 403', null), {
      id: 'x', module: 'm', title: 't', description: '', method: 'POST', path: '/x',
      requestKind: 'json', params: [], requiredScopes: ['ingest'], riskLevel: 'safe',
      state: 'scope-required', executableByDefault: true,
    })
    expect(msg).toContain('ingest')
  })
})
