import { describe, expect, it } from 'vitest'
import { resolvePortalLaunch } from './portalLaunch'

describe('resolvePortalLaunch', () => {
  it('接受显式 portal auto + 安全 tenant/深链', () => {
    expect(resolvePortalLaunch({ source: 'portal', auto: '1', tenant: 'acme-01', redirect: '/m/rag?tab=query' }))
      .toEqual({ tenant: 'acme-01', returnTo: '/m/rag?tab=query' })
  })

  it.each([
    {},
    { source: 'other', auto: '1', tenant: 'acme' },
    { source: 'portal', auto: '0', tenant: 'acme' },
    { source: 'portal', auto: '1' },
    { source: 'portal', auto: '1', tenant: '../evil' },
    { source: 'portal', auto: '1', tenant: ['acme'] },
  ])('缺失或非法参数不自动跳 %#', (query) => {
    expect(resolvePortalLaunch(query)).toBeNull()
  })

  it('开放 redirect 回退首页，不影响合法 tenant 自动登录', () => {
    expect(resolvePortalLaunch({ source: 'portal', auto: '1', tenant: 'acme', redirect: '//evil.com' }))
      .toEqual({ tenant: 'acme', returnTo: '/' })
    expect(resolvePortalLaunch({ source: 'portal', auto: '1', tenant: 'acme', redirect: '/\\evil' }))
      .toEqual({ tenant: 'acme', returnTo: '/' })
  })
})
