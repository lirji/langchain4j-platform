import { describe, it, expect, vi } from 'vitest'
import { ApiError, humanizeError, humanizeOidcCallbackError } from './errors'

// oidc 模式：humanizeError 在调用时读 AUTH_MODE（getter），故翻 cfg.mode 即可。
const cfg = vi.hoisted(() => ({ mode: 'oidc' as 'apikey' | 'oidc' | 'dual' }))
vi.mock('../config', async (orig) => ({
  ...(await orig<typeof import('../config')>()),
  get AUTH_MODE() {
    return cfg.mode
  },
}))

describe('humanizeError —— oidc 模式', () => {
  it('401 非 bearer：提示用 Casdoor 登录，不提 API Key', () => {
    cfg.mode = 'oidc'
    const msg = humanizeError(new ApiError(401, 'x', null))
    expect(msg).toContain('Casdoor')
    expect(msg).not.toContain('API Key')
  })

  it('401 bearer：过期请重新用 Casdoor 登录', () => {
    cfg.mode = 'oidc'
    const msg = humanizeError(new ApiError(401, 'x', null), undefined, { credentialMode: 'bearer' })
    expect(msg).toContain('过期')
    expect(msg).toContain('Casdoor')
  })

  it('403：账号缺 scope，不提「更换 Key」', () => {
    cfg.mode = 'oidc'
    const msg = humanizeError(new ApiError(403, 'x', null))
    expect(msg).toContain('scope')
    expect(msg).not.toContain('更换')
  })
})

describe('humanizeOidcCallbackError', () => {
  it('state 不匹配 → 校验未通过', () => {
    expect(humanizeOidcCallbackError(new Error('No matching state found in storage'))).toContain('state')
  })
  it('用户取消/access_denied', () => {
    expect(humanizeOidcCallbackError(new Error('access_denied'))).toContain('取消')
  })
  it('nonce 不匹配', () => {
    expect(humanizeOidcCallbackError(new Error('Invalid nonce'))).toContain('nonce')
  })
  it('未知错误兜底', () => {
    expect(humanizeOidcCallbackError(null)).toBe('登录未能完成，请重试。')
  })
})
