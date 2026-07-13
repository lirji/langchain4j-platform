import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useSessionStore } from './session'
import { useAuthStore } from './auth'

beforeEach(() => setActivePinia(createPinia()))

describe('session store —— 凭证模式', () => {
  it('无凭证 → none', () => {
    expect(useSessionStore().credentialMode).toBe('none')
  })

  it('仅登录 → bearer；permissionContext 给出 Bearer 有效 scopes', () => {
    const auth = useAuthStore()
    auth.accessToken = 'tok'
    auth.user = { username: 'a', tenant: 't', scopes: ['chat', 'role-admin'] }
    const s = useSessionStore()
    expect(s.credentialMode).toBe('bearer')
    expect(s.apiKeyOverridesBearer).toBe(false)
    expect(s.permissionContext().effectiveScopes).toEqual(['chat', 'role-admin'])
  })

  it('api-key 覆盖登录 → api-key 模式；apiKeyOverridesBearer=true；effectiveScopes 为空（不透明）', () => {
    const auth = useAuthStore()
    auth.accessToken = 'tok'
    auth.user = { username: 'a', tenant: 't', scopes: ['chat'] }
    const s = useSessionStore()
    s.setApiKey('sk-x')
    expect(s.credentialMode).toBe('api-key')
    expect(s.apiKeyOverridesBearer).toBe(true)
    expect(s.permissionContext().effectiveScopes).toEqual([]) // api-key 模式不预判
    expect(s.permissionContext().credentialMode).toBe('api-key')
  })

  it('仅 api-key（未登录）→ api-key 模式，不算覆盖', () => {
    const s = useSessionStore()
    s.setApiKey('sk-x')
    expect(s.credentialMode).toBe('api-key')
    expect(s.apiKeyOverridesBearer).toBe(false)
  })
})
