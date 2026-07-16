/**
 * 「需要凭证才能执行」类提示的单一文案来源，按构建期 {@link AUTH_MODE} 出对应措辞（DR-1）。
 *
 * 目的：oidc 模式下 Casdoor 是唯一凭证入口，界面各处不应再出现「填 API Key」的误导文案；
 * dual 保留 api-key 兜底提示；apikey 维持现状。gate/usePermission/各视图统一从这里取词，避免漂移。
 */
import { AUTH_MODE } from '../config'

/** 未持凭证、需先获得凭证才能执行的提示（禁执行原因/缺登录说明）。 */
export function needCredentialText(): string {
  switch (AUTH_MODE) {
    case 'oidc':
      return '请先用 Casdoor 登录。所有业务能力经网关都需登录。'
    case 'dual':
      return '请先用 Casdoor 登录（或在顶栏「高级」填写 API Key）。所有业务能力经网关都需凭证。'
    default:
      return '请先登录（或在顶栏「高级」里填写 API Key）。所有业务能力经网关都需凭证。'
  }
}

/** 登录/凭证入口的简短引导（顶栏/空态用），不含「网关都需凭证」长尾。 */
export function loginHintText(): string {
  switch (AUTH_MODE) {
    case 'oidc':
      return '请先用 Casdoor 登录。'
    case 'dual':
      return '请先用 Casdoor 登录，或在顶栏「高级」填写 API Key。'
    default:
      return '请先登录，或在顶栏「高级」填写 API Key。'
  }
}

/** 当前模式下「手输凭证」的称谓（用于 scope-required 等提示里指代用户所持凭证）。 */
export function credentialNoun(mode: 'bearer' | 'api-key' | 'none' | undefined): string {
  if (mode === 'api-key') return 'API Key'
  if (mode === 'bearer') return AUTH_MODE === 'apikey' ? '登录会话' : 'Casdoor 登录'
  return '凭证'
}

/**
 * 缺失 scope 的「补救」提示，按构建期 {@link AUTH_MODE} 出词（与其它文案同源，避免漂移）。
 *
 * 关键：Casdoor（oidc/dual）身份的 scope 来自 token 的 `permissions` claim，由 Casdoor 授权——
 * 可经角色 / 组或**直接授给用户**，并非必然「由角色授予」；且补救应指向 **Casdoor 后台**，而非本平台的
 * RBAC 角色控制台（后者只管 auth-service 账号，对 Casdoor 身份零作用）。仅 apikey 模式是 auth-service 角色模型。
 */
export function missingScopeText(missing: string[]): string {
  const scopes = missing.join(' / ')
  switch (AUTH_MODE) {
    case 'oidc':
      return `当前账号缺少 ${scopes} scope。该 scope 由 Casdoor 授权，请联系管理员在 Casdoor 为你的账号授予对应权限（可经角色 / 组或直接授予）。`
    case 'dual':
      return `当前账号缺少 ${scopes} scope。若用 Casdoor 登录，请联系管理员在 Casdoor 授予对应权限（角色 / 组或直接授予）；若用平台账号登录，请联系管理员授予含该 scope 的角色。`
    default:
      return `当前账号缺少 ${scopes} scope（由角色授予），请联系管理员授予对应角色。`
  }
}
