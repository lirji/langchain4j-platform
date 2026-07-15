/**
 * Casdoor OIDC 适配层（阶段④）——薄封装 oidc-client-ts 的 {@link UserManager}，不让库 API 渗进业务代码。
 *
 * 关键设计（见计划 DR-5/DR-2/DR-10）：
 * - **令牌存 sessionStorage**（用户拍板，2026-07-15）：userStore 用 `window.sessionStorage`，硬刷新可秒恢复登录态
 *   （无需 prompt=none iframe），过期靠内存/存储里的 refresh_token 轮换。**权衡**：token 为标签页级、关标签即清、
 *   XSS 可读——偏离「绝不落盘」原约束，但换取可靠的「硬刷新不掉登录」+ 消除 iframe 超时卡顿。PKCE 流程态同样在 sessionStorage。
 * - **身份**从 access_token payload 解出（不验签，验签在 edge）：username=name、tenant=owner、scopes=permissions∩allowlist。
 * - `loadUserInfo:false`：不打 userinfo（身份已在 access_token 里）。
 *
 * 本模块只做「无状态适配」：不持有 Pinia store、不做 UI；store 接线与事件订阅在 [[stores/auth]]（双驱动）里。
 */
import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import type { AuthUser } from '../api/auth'
import {
  CASDOOR_ISSUER,
  CASDOOR_CLIENT_ID,
  CASDOOR_SCOPES,
  CASDOOR_REDIRECT_PATH,
  CASDOOR_POST_LOGOUT_PATH,
  CASDOOR_SILENT_PATH,
  SCOPE_ALLOWLIST,
} from '../config'

/** 相对 BASE_URL 拼绝对 URL（BASE_URL 含尾斜杠，path 无前导斜杠）。 */
function absUrl(path: string): string {
  return `${window.location.origin}${import.meta.env.BASE_URL}${path}`
}

let manager: UserManager | null = null

/** 懒加载单例 UserManager（apikey 模式下永不构造，故不影响现状）。 */
export function getUserManager(): UserManager {
  if (manager) return manager
  manager = new UserManager({
    authority: CASDOOR_ISSUER,
    client_id: CASDOOR_CLIENT_ID,
    redirect_uri: absUrl(CASDOOR_REDIRECT_PATH),
    post_logout_redirect_uri: absUrl(CASDOOR_POST_LOGOUT_PATH),
    silent_redirect_uri: absUrl(CASDOOR_SILENT_PATH),
    response_type: 'code', // Authorization Code + PKCE（S256，库默认对 code 流启用 PKCE）
    scope: CASDOOR_SCOPES,
    automaticSilentRenew: true,
    loadUserInfo: false,
    // sessionStorage：硬刷新秒恢复（getUser 直接读回）；过期用 refresh_token 轮换（无 iframe）。
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    silentRequestTimeoutInSeconds: 5, // 仅 iframe 回退才用得到；调短以防偶发卡顿
  })
  return manager
}

/** 解 JWT payload（base64url，**不验签**——验签是 edge 的职责）。失败返回 null。 */
export function decodeJwtPayload(token: string): Record<string, unknown> | null {
  const parts = token.split('.')
  if (parts.length < 2) return null
  try {
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const json = decodeURIComponent(
      atob(b64)
        .split('')
        .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
        .join(''),
    )
    return JSON.parse(json) as Record<string, unknown>
  } catch {
    return null
  }
}

/** 从 Casdoor `permissions` claim 取 scope 名：支持对象数组（取 .name）/字符串数组。与 edge extractCandidates 同构。 */
function extractScopeNames(raw: unknown): string[] {
  if (!Array.isArray(raw)) return []
  const out: string[] = []
  for (const item of raw) {
    if (typeof item === 'string') out.push(item)
    else if (item && typeof item === 'object' && typeof (item as { name?: unknown }).name === 'string') {
      out.push((item as { name: string }).name)
    }
  }
  return out
}

/**
 * 从 access_token 提取平台身份视图：username=name、tenant=owner、
 * scopes=`permissions[].name` ∩ {@link SCOPE_ALLOWLIST}（与 edge 换发的内部 JWT scopes 零漂移）。
 */
export function userFromAccessToken(accessToken: string): AuthUser {
  const p = decodeJwtPayload(accessToken) ?? {}
  const owner = typeof p.owner === 'string' ? p.owner : ''
  const name = typeof p.name === 'string' ? p.name : ''
  const allow = new Set(SCOPE_ALLOWLIST)
  const scopes = extractScopeNames(p.permissions).filter((s) => allow.has(s))
  return { username: name, tenant: owner, scopes }
}

/** 发起 Casdoor 重定向登录；returnTo 经 `state` 随 IdP 往返（回调时取回，避免自建 sessionStorage）。 */
export function startOidcLogin(returnTo?: string): Promise<void> {
  return getUserManager().signinRedirect({ state: returnTo ?? null })
}

/** 顶层重定向回调：换 token、校验 state/nonce，返回登录用户。 */
export function completeLoginCallback(): Promise<User> {
  return getUserManager().signinRedirectCallback()
}

/** 隐藏 iframe（prompt=none）内的静默回调处理（silent-renew.html / 硬刷新恢复用）；在 iframe 内运行、无返回值。 */
export function completeSilentCallback(): Promise<void> {
  return getUserManager().signinSilentCallback()
}

/** 静默恢复/续期：会话期内用 refresh_token；跨硬刷新走 prompt=none iframe（依赖 Casdoor 会话 cookie）。 */
export function silentRecover(): Promise<User | null> {
  return getUserManager().signinSilent()
}

/** 读 sessionStorage userStore 里的当前用户（硬刷新后仍在——DR-5 秒恢复；无登录/已 removeUser 时为 null）。 */
export function getStoredUser(): Promise<User | null> {
  return getUserManager().getUser()
}

/**
 * 清除 sessionStorage 里持久化的 User（含 token）。续期/登出失败时必须调用，
 * 否则失效 User 残留、硬刷新会被 {@link getStoredUser} 读回而"恢复"失效会话（#6）。
 */
export function clearStoredUser(): Promise<void> {
  return getUserManager().removeUser()
}

/** RP-initiated 单点登出：跳 Casdoor end_session（库自动带当前 user 的 id_token_hint + post_logout_redirect_uri）。 */
export function signOutRedirect(): Promise<void> {
  return getUserManager().signoutRedirect()
}

export type { User }
