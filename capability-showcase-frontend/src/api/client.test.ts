import { describe, it, expect } from 'vitest'
import type { Capability, ParamSpec } from '../types/catalog'
import {
  API_KEY_HEADER,
  assembleRequest,
  buildHeaderParams,
  isStreamingKind,
} from './client'

function cap(partial: Partial<Capability> & Pick<Capability, 'requestKind'>): Capability {
  return {
    id: 'x',
    module: 'm',
    title: 't',
    description: '',
    method: 'POST',
    path: '/x',
    params: [],
    requiredScopes: [],
    riskLevel: 'safe',
    state: 'ready',
    executableByDefault: true,
    ...partial,
  }
}

const P = (p: Partial<ParamSpec> & Pick<ParamSpec, 'name' | 'in' | 'type'>): ParamSpec => ({
  required: false,
  ...p,
})

describe('buildHeaderParams', () => {
  it('注入 in:header 参数', () => {
    const params = [P({ name: 'X-Channel-Signature', in: 'header', type: 'string' })]
    expect(buildHeaderParams(params, { 'X-Channel-Signature': 'sig-123' })).toEqual({
      'X-Channel-Signature': 'sig-123',
    })
  })

  it('绝不允许 header 参数覆盖 X-Api-Key（大小写不敏感）', () => {
    const params = [
      P({ name: 'X-Api-Key', in: 'header', type: 'string' }),
      P({ name: 'x-api-key', in: 'header', type: 'string' }),
    ]
    expect(buildHeaderParams(params, { 'X-Api-Key': 'evil', 'x-api-key': 'evil2' })).toEqual({})
  })

  it('空值忽略', () => {
    const params = [P({ name: 'X-Foo', in: 'header', type: 'string' })]
    expect(buildHeaderParams(params, { 'X-Foo': '' })).toEqual({})
  })
})

describe('assembleRequest —— header 注入', () => {
  it('业务 header 与 X-Api-Key 并存，且 X-Api-Key 恒来自 ctx（不被覆盖）', () => {
    const c = cap({
      path: '/channel/inbound',
      params: [
        P({ name: 'X-Channel-Signature', in: 'header', type: 'string' }),
        P({ name: 'X-Api-Key', in: 'header', type: 'string' }), // 恶意企图
      ],
      requestKind: 'json',
    })
    const plan = assembleRequest(c, { 'X-Channel-Signature': 'sig', 'X-Api-Key': 'evil' }, {
      apiKey: 'real-key',
      edgeBaseUrl: '',
    })
    expect(plan.headers['X-Channel-Signature']).toBe('sig')
    expect(plan.headers[API_KEY_HEADER]).toBe('real-key')
  })
})

describe('assembleRequest —— 凭证注入（api-key / 会话 Bearer 互斥）', () => {
  const c = cap({ path: '/chat', requestKind: 'json' })

  it('仅 accessToken 时注入 Authorization: Bearer，且无 X-Api-Key', () => {
    const plan = assembleRequest(c, {}, { apiKey: '', accessToken: 'tok-123', edgeBaseUrl: '' })
    expect(plan.headers['Authorization']).toBe('Bearer tok-123')
    expect(plan.headers[API_KEY_HEADER]).toBeUndefined()
  })

  it('api-key 存在时优先 X-Api-Key，不注入 Bearer（互斥）', () => {
    const plan = assembleRequest(c, {}, { apiKey: 'real-key', accessToken: 'tok-123', edgeBaseUrl: '' })
    expect(plan.headers[API_KEY_HEADER]).toBe('real-key')
    expect(plan.headers['Authorization']).toBeUndefined()
  })

  it('业务 header 参数不得覆盖 Authorization（大小写不敏感）', () => {
    const withHeader = cap({
      path: '/chat',
      requestKind: 'json',
      params: [P({ name: 'authorization', in: 'header', type: 'string' })],
    })
    const plan = assembleRequest(
      withHeader,
      { authorization: 'Bearer evil' },
      { apiKey: '', accessToken: 'tok-123', edgeBaseUrl: '' },
    )
    expect(plan.headers['Authorization']).toBe('Bearer tok-123')
  })
})

describe('assembleRequest —— multipart-sse', () => {
  it('生成 FormData 体 + Accept: text/event-stream，且不手设 Content-Type', () => {
    const file = new File(['dummy'], 'a.wav', { type: 'audio/wav' })
    const c = cap({
      path: '/voice/chat/stream',
      params: [
        P({ name: 'audio', in: 'form-data', type: 'file' }),
        P({ name: 'chatId', in: 'query', type: 'string' }),
      ],
      requestKind: 'multipart-sse',
    })
    const plan = assembleRequest(c, { audio: file, chatId: 'c1' }, { apiKey: 'k', edgeBaseUrl: '' })
    expect(plan.body).toBeInstanceOf(FormData)
    expect((plan.body as FormData).get('audio')).toBeInstanceOf(File)
    expect(plan.headers['Accept']).toBe('text/event-stream')
    expect(plan.headers['Content-Type']).toBeUndefined()
    expect(plan.url).toBe('/voice/chat/stream?chatId=c1')
  })
})

describe('isStreamingKind', () => {
  it('sse 与 multipart-sse 为流式；其余不是', () => {
    expect(isStreamingKind('sse')).toBe(true)
    expect(isStreamingKind('multipart-sse')).toBe(true)
    expect(isStreamingKind('json')).toBe(false)
    expect(isStreamingKind('multipart')).toBe(false)
    expect(isStreamingKind('none')).toBe(false)
  })
})
