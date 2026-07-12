import type { Capability } from '../types/catalog'

export interface GateContext {
  /** 是否具备可执行凭证：已登录会话 或 已填写 API Key（调用方传 session.hasCredential）。 */
  hasApiKey: boolean
  /** 危险端点二次确认闸门（executableByDefault=false 时必须为 true 才放行）。 */
  confirmed?: boolean
}

export interface GateResult {
  allowed: boolean
  /** 不可执行时给出人话原因，用于禁用态提示。 */
  reason?: string
  /** 提示级别（executable=true 但需要注意时，如 scope-required）。 */
  hint?: string
}

/**
 * 纯函数：判断某能力当前是否允许在 UI 执行（"诚实呈现" + 二次闸门的单一裁决点）。
 *
 * 规则优先级：
 * 1. flag-off  → 禁执行，给出确切 feature flag 属性名。
 * 2. executableByDefault=false（危险端点）→ 未二次确认前禁执行，仅可预览/复制 curl。
 * 3. 无可执行凭证（未登录且未填 API Key）→ 禁执行（所有业务能力经网关都需凭证）。
 * 4. scope-required → 允许执行，但给出前置提示（key 不透明，无法预判 scope，403 反应式处理）。
 */
export function executionGate(cap: Capability, ctx: GateContext): GateResult {
  if (cap.state === 'flag-off') {
    const flag = cap.featureFlag ? `需开启 ${cap.featureFlag}=true` : '需开启对应 feature flag'
    return { allowed: false, reason: `该能力未注册：${flag}。` }
  }
  if (!cap.executableByDefault && !ctx.confirmed) {
    return {
      allowed: false,
      reason: '危险能力已默认锁定，仅可预览 / 复制 curl。如确需执行请显式二次确认。',
    }
  }
  if (!ctx.hasApiKey) {
    return { allowed: false, reason: '请先登录（或在顶栏"高级"里填写 API Key）。所有业务能力经网关都需凭证。' }
  }
  if (cap.state === 'scope-required') {
    const scopes = cap.requiredScopes.length ? cap.requiredScopes.join(' / ') : '所需'
    return {
      allowed: true,
      hint: `该能力需要 ${scopes} scope。若当前 API Key 不具备，将返回 403。`,
    }
  }
  return { allowed: true }
}
