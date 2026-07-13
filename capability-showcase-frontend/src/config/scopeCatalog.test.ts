import { describe, it, expect } from 'vitest'
import { describeScope, groupScopes, allKnownScopes, UNKNOWN_GROUP_ID } from './scopeCatalog'

describe('describeScope', () => {
  it('已知 scope 返回说明', () => {
    expect(describeScope('role-admin')).toMatchObject({ group: '平台管理' })
    expect(describeScope('chat')?.label).toBe('对话')
  })
  it('未知 scope 返回 null（调用方须保留原值）', () => {
    expect(describeScope('some-future-scope')).toBeNull()
  })
})

describe('groupScopes', () => {
  it('按组归拢已知 scope，保持组顺序', () => {
    const groups = groupScopes(['chat', 'ingest', 'role-admin'])
    const ids = groups.map((g) => g.id)
    expect(ids).toContain('conversation')
    expect(ids).toContain('knowledge')
    expect(ids).toContain('platform')
    expect(ids).not.toContain(UNKNOWN_GROUP_ID)
  })

  it('未知 scope 落"其它"组且 known=false，绝不丢弃', () => {
    const groups = groupScopes(['chat', 'mystery-scope'])
    const other = groups.find((g) => g.id === UNKNOWN_GROUP_ID)
    expect(other).toBeDefined()
    expect(other?.items[0]).toMatchObject({ scope: 'mystery-scope', known: false })
    // 输入的每个 scope 都必须在输出里出现（无丢失）
    const flat = groups.flatMap((g) => g.items.map((i) => i.scope))
    expect(flat).toEqual(expect.arrayContaining(['chat', 'mystery-scope']))
  })

  it('allKnownScopes 覆盖 SeedRoles 的核心 scope', () => {
    const all = allKnownScopes()
    for (const s of ['chat', 'ingest', 'public-ingest', 'role-admin', 'analytics', 'approve']) {
      expect(all).toContain(s)
    }
  })
})
