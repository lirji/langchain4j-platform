import { describe, it, expect } from 'vitest'
import type { Capability, ParamSpec } from '../types/catalog'
import { toCurl } from './curl'

function cap(partial: Partial<Capability> & Pick<Capability, 'requestKind'>): Capability {
  return {
    id: 'x', module: 'm', title: 't', description: '', method: 'POST', path: '/x',
    params: [], requiredScopes: [], riskLevel: 'safe', state: 'ready', executableByDefault: true,
    ...partial,
  }
}
const P = (p: Partial<ParamSpec> & Pick<ParamSpec, 'name' | 'in' | 'type'>): ParamSpec => ({
  required: false, ...p,
})

describe('toCurl', () => {
  it('X-Api-Key 恒为占位符，不含明文', () => {
    const c = cap({ requestKind: 'json', params: [P({ name: 'message', in: 'body', type: 'text' })] })
    const out = toCurl(c, { message: 'hi' }, { edgeBaseUrl: '' })
    expect(out).toContain(`-H 'X-Api-Key: $API_KEY'`)
    expect(out).not.toContain('real-secret')
  })

  it('输出业务 header 参数', () => {
    const c = cap({
      path: '/channel/callbacks/async-task',
      params: [P({ name: 'X-Async-Task-Status', in: 'header', type: 'string' })],
      requestKind: 'json',
    })
    const out = toCurl(c, { 'X-Async-Task-Status': 'SUCCEEDED' }, {})
    expect(out).toContain(`-H 'X-Async-Task-Status: SUCCEEDED'`)
  })

  it('multipart-sse 输出 -F 与 -N', () => {
    const file = new File(['x'], 'a.wav', { type: 'audio/wav' })
    const c = cap({
      path: '/voice/chat/stream',
      params: [P({ name: 'audio', in: 'form-data', type: 'file' })],
      requestKind: 'multipart-sse',
    })
    const out = toCurl(c, { audio: file }, {})
    expect(out).toContain(`-F 'audio=@a.wav'`)
    expect(out).toContain('-N')
  })
})
