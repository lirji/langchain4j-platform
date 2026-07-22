import { describe, expect, it } from 'vitest'
import { parseTenantAllowlist, TenantSelectionError, validateTenantSelection } from './tenantSelection'

describe('Casdoor tenant allowlist', () => {
  it('解析逗号列表时去空白、去重并过滤非法项', () => {
    expect(parseTenantAllowlist(' acme, beta,acme,../evil,,tenantA ')).toEqual(['acme', 'beta', 'tenantA'])
  })

  it('显式空配置保持空列表，登录 fail-closed', () => {
    expect(parseTenantAllowlist('')).toEqual([])
    expect(() => validateTenantSelection('acme', [])).toThrow(TenantSelectionError)
    expect(() => validateTenantSelection('acme', [])).toThrow('租户 acme 不存在或未开放')
  })

  it('只接受精确命中的已开放租户，并返回规范化值', () => {
    expect(validateTenantSelection(' acme ', ['acme', 'beta'])).toBe('acme')
    expect(() => validateTenantSelection('unknown', ['acme', 'beta']))
      .toThrow('租户 unknown 不存在或未开放')
  })

  it('空值和格式非法值在 allowlist 检查前被拒绝', () => {
    expect(() => validateTenantSelection(' ', ['acme'])).toThrow('请先输入租户')
    expect(() => validateTenantSelection('../acme', ['acme'])).toThrow('租户格式无效')
  })
})
