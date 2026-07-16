import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { usePermission } from './usePermission'
import { useAuthStore } from '../stores/auth'
import { useSessionStore } from '../stores/session'

beforeEach(() => setActivePinia(createPinia()))

function loginAs(scopes: string[]): void {
  const auth = useAuthStore()
  auth.accessToken = 'tok'
  auth.user = { username: 'a', tenant: 't', scopes }
}

describe('usePermission.evaluate', () => {
  it('无凭证 → need-login（不放行）', () => {
    const v = usePermission().evaluate(['chat'])
    expect(v).toMatchObject({ allowed: false, reason: 'need-login' })
  })

  it('api-key 模式 → 放行但 unknown（反应式鉴权）', () => {
    useSessionStore().setApiKey('sk')
    const v = usePermission().evaluate(['role-admin'])
    expect(v).toMatchObject({ allowed: true, reason: 'unknown-apikey' })
  })

  it('bearer 命中所需 scope → ok', () => {
    loginAs(['chat', 'role-admin'])
    expect(usePermission().evaluate(['role-admin'])).toMatchObject({ allowed: true, reason: 'ok' })
  })

  it('bearer 缺 scope → missing-scope（含缺失列表，不放行）', () => {
    loginAs(['chat'])
    const v = usePermission().evaluate(['role-admin', 'ingest'])
    expect(v.allowed).toBe(false)
    expect(v.reason).toBe('missing-scope')
    expect(v.missingScopes).toEqual(['role-admin', 'ingest'])
  })
})
