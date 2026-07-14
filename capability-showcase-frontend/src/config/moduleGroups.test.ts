import { describe, it, expect } from 'vitest'
import {
  GROUP_ORDER,
  OTHER_GROUP_ID,
  groupAccent,
  groupIdForModule,
  groupLabel,
} from './moduleGroups'

describe('moduleGroups', () => {
  it('已知模块映射到预期分组', () => {
    expect(groupIdForModule('chat')).toBe('conversation-retrieval')
    expect(groupIdForModule('rag')).toBe('conversation-retrieval')
    expect(groupIdForModule('agent')).toBe('agent-orchestration')
    expect(groupIdForModule('analytics')).toBe('agent-orchestration')
    expect(groupIdForModule('multimodal')).toBe('multimodal')
    expect(groupIdForModule('interop-eval')).toBe('platform-interop')
    expect(groupIdForModule('channel')).toBe('platform-interop')
  })

  it('未知模块归 Other', () => {
    expect(groupIdForModule('some-future-module')).toBe(OTHER_GROUP_ID)
  })

  it('groupLabel 返回中文标签，未知回退「其它」', () => {
    expect(groupLabel('conversation-retrieval')).toBe('对话与检索')
    expect(groupLabel('nope')).toBe('其它')
  })

  it('每个分组都有 accent，且 Other=slate；未知回退 slate', () => {
    for (const g of GROUP_ORDER) expect(g.accent).toBeTruthy()
    expect(groupAccent(OTHER_GROUP_ID)).toBe('slate')
    expect(groupAccent('conversation-retrieval')).toBe('blue')
    expect(groupAccent('agent-orchestration')).toBe('violet')
    expect(groupAccent('multimodal')).toBe('amber')
    expect(groupAccent('platform-interop')).toBe('teal')
    expect(groupAccent('unknown-group')).toBe('slate')
  })

  it('GROUP_ORDER 末位是 Other（未映射模块的兜底组，展示在最后）', () => {
    expect(GROUP_ORDER[GROUP_ORDER.length - 1].id).toBe(OTHER_GROUP_ID)
  })
})
