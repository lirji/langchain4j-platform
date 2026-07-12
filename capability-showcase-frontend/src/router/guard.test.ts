import { describe, it, expect } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'
import { resolveAuthNavigation, sanitizeRedirect } from './index'

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
