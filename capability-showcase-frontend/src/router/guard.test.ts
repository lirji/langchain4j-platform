import { describe, it, expect } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'
import { resolveAuthNavigation, resolveRouteAccess, sanitizeRedirect, type RouteAccessContext } from './index'

/** 构造守卫入参（只取用到的字段）。 */
function to(partial: Record<string, unknown>): Pick<RouteLocationNormalized, 'name' | 'fullPath' | 'meta' | 'query'> {
  return {
    name: undefined,
    fullPath: '/',
    meta: {},
    query: {},
    ...partial,
  } as unknown as Pick<RouteLocationNormalized, 'name' | 'fullPath' | 'meta' | 'query'>
}

describe('sanitizeRedirect', () => {
  it('接受站内绝对路径', () => {
    expect(sanitizeRedirect('/m/agent/agent.run')).toBe('/m/agent/agent.run')
  })
  it('拒绝开放重定向与非法值', () => {
    expect(sanitizeRedirect('//evil.com')).toBeNull()
    expect(sanitizeRedirect('http://evil.com')).toBeNull()
    expect(sanitizeRedirect('')).toBeNull()
    expect(sanitizeRedirect(123)).toBeNull()
    expect(sanitizeRedirect(undefined)).toBeNull()
  })
})

describe('resolveAuthNavigation', () => {
  it('未强制登录一律放行（回滚/纯 api-key 模式）', () => {
    expect(
      resolveAuthNavigation(to({ name: 'overview' }), { isAuthenticated: false, requireLogin: false }),
    ).toBe(true)
  })

  it('未登录访问受保护路由 → 跳 /login 带 redirect', () => {
    expect(
      resolveAuthNavigation(to({ name: 'module', fullPath: '/m/agent' }), {
        isAuthenticated: false,
        requireLogin: true,
      }),
    ).toEqual({ name: 'login', query: { redirect: '/m/agent' } })
  })

  it('未登录访问 public 页放行', () => {
    expect(
      resolveAuthNavigation(to({ name: 'login', meta: { public: true } }), {
        isAuthenticated: false,
        requireLogin: true,
      }),
    ).toBe(true)
  })

  it('已登录访问受保护路由放行', () => {
    expect(
      resolveAuthNavigation(to({ name: 'overview' }), { isAuthenticated: true, requireLogin: true }),
    ).toBe(true)
  })

  it('已登录访问 /login → 回 redirect 深链', () => {
    expect(
      resolveAuthNavigation(to({ name: 'login', meta: { public: true }, query: { redirect: '/m/rag' } }), {
        isAuthenticated: true,
        requireLogin: true,
      }),
    ).toBe('/m/rag')
  })

  it('已登录访问 /login 无 redirect → 首页', () => {
    expect(
      resolveAuthNavigation(to({ name: 'login', meta: { public: true } }), {
        isAuthenticated: true,
        requireLogin: true,
      }),
    ).toEqual({ path: '/' })
  })

  it('已登录 /login 的 redirect 为开放重定向 → 忽略回首页', () => {
    expect(
      resolveAuthNavigation(to({ name: 'login', meta: { public: true }, query: { redirect: '//evil' } }), {
        isAuthenticated: true,
        requireLogin: true,
      }),
    ).toEqual({ path: '/' })
  })
})

describe('resolveRouteAccess（组合守卫：登录层 + register 门禁 + admin scope 门禁）', () => {
  const base: RouteAccessContext = {
    isAuthenticated: true,
    requireLogin: true,
    effectiveScopes: [],
    registrationEnabled: null,
    consoleEnabled: true,
  }
  const ctx = (p: Partial<RouteAccessContext> = {}): RouteAccessContext => ({ ...base, ...p })
  const admin = { name: 'admin-users', fullPath: '/admin/users', meta: { requiredScopes: ['role-admin'] } }

  it('已登录 + 有 role-admin → 放行', () => {
    expect(resolveRouteAccess(to(admin), ctx({ effectiveScopes: ['chat', 'role-admin'] }))).toBe(true)
  })

  it('已登录 + 缺 role-admin → /forbidden', () => {
    expect(resolveRouteAccess(to(admin), ctx({ effectiveScopes: ['chat'] }))).toEqual({ name: 'forbidden' })
  })

  it('多值 requiredScopes：缺其一即 forbidden，全含才放行', () => {
    const two = { name: 'x', fullPath: '/x', meta: { requiredScopes: ['a', 'b'] } }
    expect(resolveRouteAccess(to(two), ctx({ effectiveScopes: ['a'] }))).toEqual({ name: 'forbidden' })
    expect(resolveRouteAccess(to(two), ctx({ effectiveScopes: ['a', 'b'] }))).toBe(true)
  })

  it('consoleEnabled=false + admin 路由 → 回首页（双保险）', () => {
    expect(resolveRouteAccess(to(admin), ctx({ effectiveScopes: ['role-admin'], consoleEnabled: false }))).toEqual({
      path: '/',
    })
  })

  it('回滚模式(requireLogin=false) + 未登录访问 admin → 强制登录带 redirect', () => {
    expect(
      resolveRouteAccess(to(admin), ctx({ isAuthenticated: false, requireLogin: false, effectiveScopes: [] })),
    ).toEqual({ name: 'login', query: { redirect: '/admin/users' } })
  })

  it('守卫不受 api-key 影响：ctx 无 api-key 字段，缺 scope 仍 forbidden', () => {
    // 断言：即便顶栏填了 api-key，effectiveScopes 仍取自 Bearer；缺 role-admin → forbidden
    expect(resolveRouteAccess(to(admin), ctx({ effectiveScopes: ['chat'] }))).toEqual({ name: 'forbidden' })
  })

  it('/register：registrationEnabled=true 未登录 → 放行', () => {
    expect(
      resolveRouteAccess(to({ name: 'register', meta: { public: true } }), ctx({ isAuthenticated: false, registrationEnabled: true })),
    ).toBe(true)
  })

  it('/register：registrationEnabled=false 或 null → 回 /login（fail-closed）', () => {
    expect(
      resolveRouteAccess(to({ name: 'register', meta: { public: true } }), ctx({ isAuthenticated: false, registrationEnabled: false })),
    ).toEqual({ name: 'login' })
    expect(
      resolveRouteAccess(to({ name: 'register', meta: { public: true } }), ctx({ isAuthenticated: false, registrationEnabled: null })),
    ).toEqual({ name: 'login' })
  })

  it('/register：已登录 → 回首页', () => {
    expect(
      resolveRouteAccess(to({ name: 'register', meta: { public: true } }), ctx({ registrationEnabled: true })),
    ).toEqual({ path: '/' })
  })

  it('非 requiredScopes 的普通已登录路由 → 放行', () => {
    expect(resolveRouteAccess(to({ name: 'overview' }), ctx({ effectiveScopes: ['chat'] }))).toBe(true)
  })
})
