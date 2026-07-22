import { sanitizeInternalPath } from '../../auth/redirect'

const TENANT_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/

export interface PortalLaunch {
  tenant: string
  returnTo: string
}

/**
 * 公开门户只提供 public tenant 提示；目标站校验后才允许自动发起 OIDC。
 * 非 portal、未显式 auto、数组参数、非法 tenant 均回退普通登录页。
 */
export function resolvePortalLaunch(query: Record<string, unknown>): PortalLaunch | null {
  if (query.source !== 'portal' || query.auto !== '1') return null
  if (typeof query.tenant !== 'string') return null
  const tenant = query.tenant.trim()
  if (!TENANT_PATTERN.test(tenant)) return null
  return {
    tenant,
    returnTo: sanitizeInternalPath(query.redirect) ?? '/',
  }
}
