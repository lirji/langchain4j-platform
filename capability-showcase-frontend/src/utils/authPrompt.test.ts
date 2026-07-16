import { describe, it, expect, vi } from 'vitest'
import { needCredentialText, loginHintText, credentialNoun, missingScopeText } from './authPrompt'

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

  it('missingScopeText 按模式出补救词：oidc 指向 Casdoor、不误导去授予角色', () => {
    // 始终带上缺失的 scope 名。
    expect(missingScopeText(['public-ingest'])).toContain('public-ingest')

    // apikey：auth-service 角色模型，保留「由角色授予/授予对应角色」。
    cfg.mode = 'apikey'
    expect(missingScopeText(['public-ingest'])).toContain('由角色授予')
    expect(missingScopeText(['public-ingest'])).toContain('授予对应角色')

    // oidc：Casdoor 授权，绝不把用户指向平台角色控制台。
    cfg.mode = 'oidc'
    const oidc = missingScopeText(['public-ingest'])
    expect(oidc).toContain('Casdoor')
    expect(oidc).not.toContain('授予对应角色')
    expect(oidc).not.toContain('由角色授予')

    // dual：两条路径都提（Casdoor 或平台账号）。
    cfg.mode = 'dual'
    const dual = missingScopeText(['public-ingest'])
    expect(dual).toContain('Casdoor')
    expect(dual).toContain('平台账号')
  })
})
