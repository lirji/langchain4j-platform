import { describe, it, expect, vi } from 'vitest'
import { needCredentialText, loginHintText, credentialNoun } from './authPrompt'

// AUTH_MODE 用 getter：authPrompt 在函数体内读它，故翻 cfg.mode 即可切模式，无需 resetModules。
const cfg = vi.hoisted(() => ({ mode: 'apikey' as 'apikey' | 'oidc' | 'dual' }))
vi.mock('../config', async (orig) => ({
  ...(await orig<typeof import('../config')>()),
  get AUTH_MODE() {
    return cfg.mode
  },
}))

describe('authPrompt', () => {
  it('needCredentialText 按模式出词：oidc 不提 API Key', () => {
    cfg.mode = 'apikey'
    expect(needCredentialText()).toContain('API Key')
    cfg.mode = 'oidc'
    expect(needCredentialText()).toContain('Casdoor')
    expect(needCredentialText()).not.toContain('API Key')
    cfg.mode = 'dual'
    expect(needCredentialText()).toContain('Casdoor')
    expect(needCredentialText()).toContain('API Key') // dual 保留兜底提示
  })

  it('loginHintText 按模式出词', () => {
    cfg.mode = 'oidc'
    expect(loginHintText()).toBe('请先用 Casdoor 登录。')
    cfg.mode = 'apikey'
    expect(loginHintText()).toContain('API Key')
  })

  it('credentialNoun 指代所持凭证', () => {
    cfg.mode = 'oidc'
    expect(credentialNoun('api-key')).toBe('API Key')
    expect(credentialNoun('bearer')).toBe('Casdoor 登录')
    cfg.mode = 'apikey'
    expect(credentialNoun('bearer')).toBe('登录会话')
    expect(credentialNoun('none')).toBe('凭证')
  })
})
