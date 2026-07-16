import { computed } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useSessionStore } from '../stores/session'
import { loginHintText, missingScopeText } from '../utils/authPrompt'

/**
 * 权限裁决单一入口——供 gate、能力缺权说明共用，避免"通用 runner 与专用页面裁决漂移"。
 *
 * 三种凭证模式：
 * - none：无凭证 → need-login。
 * - api-key：权限不透明 → 放行但标 unknown（反应式鉴权，可能 403）。
 * - bearer：可按有效 scopes 精确预判 → 缺则 missing-scope，具备则 ok。
 */
export type PermissionReason = 'ok' | 'need-login' | 'missing-scope' | 'unknown-apikey'

export interface PermissionVerdict {
  allowed: boolean
  reason: PermissionReason
  /** missing-scope 时为缺失的 scope 列表。 */
  missingScopes: string[]
  /** 直接可渲染的人话。 */
  message: string
}

export function usePermission() {
  const auth = useAuthStore()
  const session = useSessionStore()

  /** 对一组 requiredScopes 裁决（Bearer 预判、api-key unknown、无凭证 need-login）。 */
  function evaluate(requiredScopes: string[] = []): PermissionVerdict {
    const mode = session.credentialMode
    if (mode === 'none') {
      return {
        allowed: false,
        reason: 'need-login',
        missingScopes: requiredScopes,
        message: loginHintText(),
      }
    }
    if (mode === 'api-key') {
      return {
        allowed: true,
        reason: 'unknown-apikey',
        missingScopes: [],
        message: 'API Key 权限不透明，将由后端反应式鉴权（可能 403）。',
      }
    }
    // bearer：可精确预判
    const missing = requiredScopes.filter((s) => !auth.hasScope(s))
    return missing.length
      ? {
          allowed: false,
          reason: 'missing-scope',
          missingScopes: missing,
          message: missingScopeText(missing),
        }
      : { allowed: true, reason: 'ok', missingScopes: [], message: '' }
  }

  const credentialMode = computed(() => session.credentialMode)
  const apiKeyOverridesBearer = computed(() => session.apiKeyOverridesBearer)

  return { evaluate, hasScope: auth.hasScope, credentialMode, apiKeyOverridesBearer }
}
