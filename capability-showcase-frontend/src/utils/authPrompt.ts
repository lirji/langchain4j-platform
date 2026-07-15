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
