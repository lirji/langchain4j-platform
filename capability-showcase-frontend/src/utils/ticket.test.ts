import { describe, expect, it } from 'vitest'
import { parseTicket } from './ticket'

describe('parseTicket', () => {
  it('解析 Ticket 形状并忽略空 nextSteps', () => {
    expect(
      parseTicket({
        title: '登录失败',
        priority: 'HIGH',
        category: 'auth',
        summary: '手机打不开登录页',
        nextSteps: ['查日志', '', 1],
      }),
    ).toEqual({
      title: '登录失败',
      priority: 'HIGH',
      category: 'auth',
      summary: '手机打不开登录页',
      nextSteps: ['查日志'],
    })
  })

  it('无法识别时返回 null，不臆造字段', () => {
    expect(parseTicket(null)).toBeNull()
    expect(parseTicket('ticket')).toBeNull()
    expect(parseTicket({ reply: 'ok' })).toBeNull()
  })
})
