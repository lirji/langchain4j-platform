/**
 * 登录端点封装（/auth/*）。
 *
 * 约定：
 * - 走 {@link AUTH_BASE_URL}（默认相对路径 → vite 同源代理 / nginx 同源反代），使刷新令牌 httpOnly
 *   cookie 成为第一方；所有请求 `credentials:'include'` 才能收发该 cookie。
 * - 失败抛 {@link ApiError}（携状态码 + 解析后的错误体），交上层 humanizeError。
 * - 会话访问令牌只在响应体里返回（存内存），刷新令牌只在 cookie 里（前端不碰）。
 */
import { AUTH_BASE_URL } from '../config'
import { ApiError } from './errors'
import { tryParseJson } from '../utils/json'

/** 登录后的用户视图（不含任何密码/令牌）。 */
export interface AuthUser {
  username: string
  tenant: string
  scopes: string[]
}

/** /auth/login 与 /auth/refresh 的响应体。 */
export interface AuthSession {
  accessToken: string
  expiresInSeconds: number
  user: AuthUser
}

/** GET /auth/public-config 响应体（公开、非敏感）。registrationEnabled = 后端 rbac 与 registration 同开时才 true。 */
export interface AuthPublicConfig {
  registrationEnabled: boolean
  passwordMinLength: number
  passwordMaxLength: number
}

/** bootstrap 静默续期的默认超时（ms）：网关不可达时不阻塞应用启动。 */
const REFRESH_TIMEOUT_MS = 6000

function authUrl(path: string): string {
  return `${AUTH_BASE_URL}${path}`
}

async function readJson(res: Response): Promise<unknown> {
  const text = await res.text().catch(() => '')
  return text ? (tryParseJson(text) ?? text) : null
}

async function postAuth(path: string, body?: unknown, signal?: AbortSignal): Promise<Response> {
  const init: RequestInit = {
    method: 'POST',
    credentials: 'include', // 收发刷新令牌 httpOnly cookie 的必要条件
    signal,
  }
  if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(body)
  }
  return fetch(authUrl(path), init)
}

/** 账号密码登录。成功返回会话；失败抛 ApiError（401 凭证错 / 403 禁用 / 429 节流）。 */
export async function loginRequest(username: string, password: string): Promise<AuthSession> {
  const res = await postAuth('/auth/login', { username, password })
  const data = await readJson(res)
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status} ${res.statusText}`.trim(), data)
  return data as AuthSession
}

/**
 * 用 httpOnly 刷新 cookie 换新会话（轮转）。带超时，失败/超时抛 ApiError —— 调用方静默处理，
 * 绝不让它阻塞启动。
 */
export async function refreshRequest(): Promise<AuthSession> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), REFRESH_TIMEOUT_MS)
  try {
    const res = await postAuth('/auth/refresh', undefined, controller.signal)
    const data = await readJson(res)
    if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status} ${res.statusText}`.trim(), data)
    return data as AuthSession
  } finally {
    clearTimeout(timer)
  }
}

/**
 * 自助注册（成功即登录，返回同 login 的 AuthSession）。失败抛 ApiError（403 未开启 / 409 用户名已占用 /
 * 400 密码策略 / 429 节流）。租户/角色由后端规则引擎按用户名域名推导，前端不选。
 */
export async function registerRequest(username: string, password: string): Promise<AuthSession> {
  const res = await postAuth('/auth/register', { username, password })
  const data = await readJson(res)
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status} ${res.statusText}`.trim(), data)
  return data as AuthSession
}

/**
 * GET /auth/public-config（边缘 open 路径，无需凭证）。供未登录前端在渲染登录/注册页前拉取，
 * 决定是否显示注册入口与密码长度提示。失败抛 ApiError（调用方静默处理，注册入口 fail-closed）。
 */
export async function fetchPublicAuthConfig(): Promise<AuthPublicConfig> {
  // 带超时：启动时会 await 它以让路由守卫拿到真实值（修 /register 冷深链竞态），端点慢也不拖死启动。
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), REFRESH_TIMEOUT_MS)
  try {
    const res = await fetch(authUrl('/auth/public-config'), { credentials: 'include', signal: controller.signal })
    const data = await readJson(res)
    if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status}`.trim(), data)
    return data as AuthPublicConfig
  } finally {
    clearTimeout(timer)
  }
}

/** 登出：撤销刷新会话并清 cookie。失败不抛（本地清态由调用方兜底）。 */
export async function logoutRequest(): Promise<void> {
  try {
    await postAuth('/auth/logout')
  } catch {
    // 前端不可信赖服务端登出结果：即便请求失败也本地清态。
  }
}
