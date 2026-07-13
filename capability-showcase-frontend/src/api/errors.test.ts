import { describe, it, expect } from 'vitest'
import { ApiError, humanizeError, apiErrorCode } from './errors'

describe('humanizeError —— 422 业务门禁', () => {
  it('422 展示为门禁结果（非网络/参数错误）', () => {
    const msg = humanizeError(new ApiError(422, 'HTTP 422', { gate: { passed: false } }))
    expect(msg).toContain('门禁未通过')
    expect(msg).toContain('422')
  })

  it('403 提示所需 scope', () => {
    const msg = humanizeError(new ApiError(403, 'HTTP 403', null), {
      id: 'x', module: 'm', title: 't', description: '', method: 'POST', path: '/x',
      requestKind: 'json', params: [], requiredScopes: ['ingest'], riskLevel: 'safe',
      state: 'scope-required', executableByDefault: true,
    })
    expect(msg).toContain('ingest')
  })
})

describe('apiErrorCode', () => {
  it('提取 {error} 判别码', () => {
    expect(apiErrorCode(new ApiError(409, 'x', { error: 'version_conflict', message: 'm' }))).toBe('version_conflict')
  })
  it('非 ApiError / 无 error 字段 → null', () => {
    expect(apiErrorCode(new Error('x'))).toBeNull()
    expect(apiErrorCode(new ApiError(409, 'x', 'plain'))).toBeNull()
  })
})

describe('humanizeError —— 管理域凭证感知', () => {
  it('409 管理冲突透传后端中文 message', () => {
    const msg = humanizeError(new ApiError(409, 'x', { error: 'version_conflict', message: '资源已被其他管理员修改，请刷新后重试' }))
    expect(msg).toBe('资源已被其他管理员修改，请刷新后重试')
  })

  it('401 按凭证模式区分：bearer=登录过期，api-key=Key 无效', () => {
    expect(humanizeError(new ApiError(401, 'x', null), undefined, { credentialMode: 'bearer' })).toContain('登录已过期')
    expect(humanizeError(new ApiError(401, 'x', null), undefined, { credentialMode: 'api-key' })).toContain('API Key')
    // 不传凭证模式 → 回落既有 api-key 文案（保既有调用点行为）
    expect(humanizeError(new ApiError(401, 'x', null))).toContain('API Key')
  })

  it('503 rbac_writes_disabled → 灰度未开文案', () => {
    const msg = humanizeError(new ApiError(503, 'x', { error: 'rbac_writes_disabled', message: 'RBAC 管理写入当前未开启（灰度）' }))
    expect(msg).toContain('未开启')
  })

  it('403 bearer 视角提示需管理员授予角色', () => {
    const cap = {
      id: 'x', module: 'm', title: 't', description: '', method: 'POST', path: '/x',
      requestKind: 'json' as const, params: [], requiredScopes: ['role-admin'], riskLevel: 'safe' as const,
      state: 'scope-required' as const, executableByDefault: true,
    }
    const msg = humanizeError(new ApiError(403, 'x', null), cap, { credentialMode: 'bearer' })
    expect(msg).toContain('角色')
  })
})
