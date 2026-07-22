const TENANT_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/

export class TenantSelectionError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'TenantSelectionError'
  }
}

/** 解析构建期租户 allowlist：逗号分隔、去空白、去重，并丢弃不能作为 Casdoor org 的非法项。 */
export function parseTenantAllowlist(raw: string): readonly string[] {
  const tenants: string[] = []
  const seen = new Set<string>()
  for (const part of raw.split(',')) {
    const tenant = part.trim()
    if (!TENANT_PATTERN.test(tenant) || seen.has(tenant)) continue
    seen.add(tenant)
    tenants.push(tenant)
  }
  return Object.freeze(tenants)
}

/** 登录前精确校验租户；绝不把未知输入直接拼进 OIDC clientId。 */
export function validateTenantSelection(rawTenant: string, allowedTenants: readonly string[]): string {
  const tenant = rawTenant.trim()
  if (!tenant) throw new TenantSelectionError('请先输入租户 / 组织名（Casdoor org）')
  if (!TENANT_PATTERN.test(tenant)) {
    throw new TenantSelectionError('租户格式无效，请输入字母、数字、点、下划线或连字符')
  }
  if (!allowedTenants.includes(tenant)) {
    throw new TenantSelectionError(`租户 ${tenant} 不存在或未开放，请从当前可用租户中选择`)
  }
  return tenant
}
