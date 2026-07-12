import { describe, it, expect } from 'vitest'
import { redact, redactKey } from './redact'
import { toCurl } from './curl'
import { assembleRequest, API_KEY_HEADER } from '../api/client'
import type { Capability } from '../types/catalog'

describe('redactKey', () => {
  it('空值返回空串', () => {
    expect(redactKey('')).toBe('')
    expect(redactKey(null)).toBe('')
    expect(redactKey(undefined)).toBe('')
  })

  it('长度 ≤ 4 全部掩盖，不泄露任何字符', () => {
    expect(redactKey('abcd')).toBe('••••')
    expect(redactKey('ab')).toBe('••')
  })

  it('仅保留尾 4 位', () => {
    const masked = redactKey('super-secret-key-1234')
    expect(masked.endsWith('1234')).toBe(true)
    expect(masked).not.toContain('secret')
  })
})

describe('redact', () => {
  it('把文本中的密钥替换为掩码', () => {
    const out = redact('Authorization: my-key-xyz done', 'my-key-xyz')
    expect(out).not.toContain('my-key-xyz')
  })
  it('secret 为空时原样返回', () => {
    expect(redact('hello', '')).toBe('hello')
  })
})

const KEY = 'super-secret-key-1234'

const jsonCap: Capability = {
  id: 'chat.sync',
  module: 'chat',
  title: '单轮对话',
  description: '',
  method: 'POST',
  path: '/chat',
  requestKind: 'json',
  params: [
    { name: 'chatId', in: 'query', type: 'string', required: false },
    { name: 'message', in: 'body', type: 'text', required: true },
  ],
  requiredScopes: [],
  riskLevel: 'safe',
  state: 'ready',
  executableByDefault: true,
}

describe('client 注入不泄露 key', () => {
  const values = { chatId: 'default', message: '你好' }

  it('X-Api-Key 仅注入到请求头（唯一注入点）', () => {
    const plan = assembleRequest(jsonCap, values, { apiKey: KEY, edgeBaseUrl: '' })
    expect(plan.headers[API_KEY_HEADER]).toBe(KEY)
  })

  it('key 不出现在 URL 或请求体中', () => {
    const plan = assembleRequest(jsonCap, values, { apiKey: KEY, edgeBaseUrl: '' })
    expect(plan.url).not.toContain(KEY)
    expect(String(plan.body)).not.toContain(KEY)
  })

  it('curl 预览用占位符，不含明文 key', () => {
    const curl = toCurl(jsonCap, values, { edgeBaseUrl: '' })
    expect(curl).not.toContain(KEY)
    expect(curl).toContain('$API_KEY')
  })
})
