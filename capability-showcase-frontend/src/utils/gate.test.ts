import { describe, it, expect } from 'vitest'
import { executionGate } from './gate'
import type { Capability } from '../types/catalog'

function cap(overrides: Partial<Capability>): Capability {
  return {
    id: 'x',
    module: 'm',
    title: 't',
    description: '',
    method: 'POST',
    path: '/x',
    requestKind: 'json',
    params: [],
    requiredScopes: [],
    riskLevel: 'safe',
    state: 'ready',
    executableByDefault: true,
    ...overrides,
  }
}

describe('executionGate', () => {
  it('ready + 有 key → 允许', () => {
    expect(executionGate(cap({}), { hasApiKey: true }).allowed).toBe(true)
  })

  it('无 API Key → 禁止', () => {
    const r = executionGate(cap({}), { hasApiKey: false })
    expect(r.allowed).toBe(false)
    expect(r.reason).toContain('API Key')
  })

  it('flag-off → 禁止并给出 feature flag 名', () => {
    const r = executionGate(
      cap({ state: 'flag-off', featureFlag: 'app.nl2sql.enabled' }),
      { hasApiKey: true },
    )
    expect(r.allowed).toBe(false)
    expect(r.reason).toContain('app.nl2sql.enabled')
  })

  it('executableByDefault=false（危险端点）未确认 → 禁止', () => {
    const r = executionGate(
      cap({ executableByDefault: false, riskLevel: 'destructive', state: 'display-only' }),
      { hasApiKey: true },
    )
    expect(r.allowed).toBe(false)
    expect(r.reason).toContain('锁定')
  })

  it('executableByDefault=false 但已二次确认 + 有 key → 允许', () => {
    const r = executionGate(
      cap({ executableByDefault: false }),
      { hasApiKey: true, confirmed: true },
    )
    expect(r.allowed).toBe(true)
  })

  it('scope-required → 允许但给出前置提示', () => {
    const r = executionGate(
      cap({ state: 'scope-required', requiredScopes: ['ingest'] }),
      { hasApiKey: true },
    )
    expect(r.allowed).toBe(true)
    expect(r.hint).toContain('ingest')
  })
})
