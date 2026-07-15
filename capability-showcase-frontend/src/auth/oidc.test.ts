import { describe, it, expect } from 'vitest'
import { decodeJwtPayload, userFromAccessToken } from './oidc'

/** 造一个未签名假 JWT（SPA 不验签，验签在 edge）。保留 base64 padding，jsdom atob 更稳。 */
function b64url(obj: unknown): string {
  return btoa(JSON.stringify(obj)).replace(/\+/g, '-').replace(/\//g, '_')
}
function fakeJwt(payload: Record<string, unknown>): string {
  return `${b64url({ alg: 'RS256', typ: 'JWT' })}.${b64url(payload)}.sig`
}

describe('decodeJwtPayload', () => {
  it('解出 payload；坏 token 返回 null', () => {
    const t = fakeJwt({ owner: 'built-in', sub: 'u1', name: 'admin' })
    expect(decodeJwtPayload(t)).toMatchObject({ owner: 'built-in', sub: 'u1', name: 'admin' })
    expect(decodeJwtPayload('not-a-jwt')).toBeNull()
    expect(decodeJwtPayload('a.@@@.c')).toBeNull()
  })
})

describe('userFromAccessToken', () => {
  it('permissions 对象数组取 name，∩ allowlist（未知 scope 丢弃）', () => {
    const t = fakeJwt({
      owner: 'built-in',
      name: 'admin',
      permissions: [{ name: 'chat' }, { name: 'role-admin' }, { name: 'not-in-allowlist' }],
    })
    const u = userFromAccessToken(t)
    expect(u).toEqual({ username: 'admin', tenant: 'built-in', scopes: ['chat', 'role-admin'] })
  })

  it('permissions 字符串数组同样支持', () => {
    const t = fakeJwt({ owner: 'acme', name: 'bob', permissions: ['chat', 'ingest', 'bogus'] })
    expect(userFromAccessToken(t)).toEqual({ username: 'bob', tenant: 'acme', scopes: ['chat', 'ingest'] })
  })

  it('缺字段/坏 token 优雅降级为空视图', () => {
    expect(userFromAccessToken('garbage')).toEqual({ username: '', tenant: '', scopes: [] })
    const noPerms = fakeJwt({ owner: 'built-in', name: 'x' })
    expect(userFromAccessToken(noPerms)).toEqual({ username: 'x', tenant: 'built-in', scopes: [] })
  })
})
