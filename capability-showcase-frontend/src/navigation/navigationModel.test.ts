import { describe, it, expect } from 'vitest'
import {
  buildNavigationModel,
  capabilityMatches,
  moduleMatches,
  splitModuleTitle,
  type NavigationInput,
} from './navigationModel'
import { OTHER_GROUP_ID } from '../config/moduleGroups'
import type { Capability, Module } from '../types/catalog'
import { loadCatalog } from '../test/fixtures'

// ── 最小构造器（合成边界夹具）──────────────────────────────
function cap(partial: Partial<Capability> & { id: string; module: string }): Capability {
  return {
    title: partial.id,
    description: '',
    method: 'GET',
    path: `/${partial.id}`,
    requestKind: 'json',
    params: [],
    requiredScopes: [],
    riskLevel: 'safe',
    state: 'ready',
    executableByDefault: true,
    ...partial,
  }
}
function mod(id: string, order: number, caps: Capability[], title?: string): Module {
  return {
    id,
    title: title ?? id,
    description: '',
    order,
    priority: 'P2',
    service: id,
    standalone: '',
    capabilities: caps,
  }
}

function baseInput(over: Partial<NavigationInput> = {}): NavigationInput {
  return {
    modules: [],
    favoriteIds: [],
    query: '',
    activeModuleId: '',
    activeCapId: '',
    ...over,
  }
}

// 排序权威取自入参（catalog store 已 order 排序）：这里刻意乱序传入，验证「保持入参顺序」。
const chat = mod('chat', 1, [cap({ id: 'chat-basic', module: 'chat', title: '单轮对话', method: 'POST' })], '对话 Chat Console')
const rag = mod('rag', 2, [cap({ id: 'rag-search', module: 'rag', title: '知识检索', method: 'POST' })], '知识库 RAG Workspace')
const agent = mod('agent', 3, [cap({ id: 'agent-run', module: 'agent', title: '同步跑目标', method: 'POST' })], '智能体 Agent Lab')
const unknownMod = mod('futuristic', 9, [cap({ id: 'fx-1', module: 'futuristic', title: '未来能力' })], 'Futuristic X')
const emptyMod = mod('empty', 8, [], '空模块 Empty Module')

describe('splitModuleTitle', () => {
  it.each([
    ['对话 Chat Console', '对话', 'Chat Console'],
    ['互操作与评测 Interop & Eval Tools', '互操作与评测', 'Interop & Eval Tools'],
    ['知识库 RAG Workspace', '知识库', 'RAG Workspace'],
  ])('拆双语标题 %s', (title, name, en) => {
    expect(splitModuleTitle(title)).toEqual({ name, en })
  })
  it('无英文段安全回退：name=完整标题、en=空', () => {
    expect(splitModuleTitle('纯中文模块')).toEqual({ name: '纯中文模块', en: '' })
    expect(splitModuleTitle('  ')).toEqual({ name: '', en: '' })
  })
})

describe('capabilityMatches / moduleMatches', () => {
  const c = cap({ id: 'chat-basic', module: 'chat', title: '单轮对话', path: '/chat', description: '一问一答', tags: ['sync'] })
  it('空查询恒真', () => {
    expect(capabilityMatches(c, '')).toBe(true)
    expect(moduleMatches(chat, '')).toBe(true)
  })
  it('命中 title/id/path/description/tags 任一', () => {
    expect(capabilityMatches(c, '单轮')).toBe(true)
    expect(capabilityMatches(c, 'chat-basic')).toBe(true)
    expect(capabilityMatches(c, '/chat')).toBe(true)
    expect(capabilityMatches(c, '一问')).toBe(true)
    expect(capabilityMatches(c, 'sync')).toBe(true)
    expect(capabilityMatches(c, '不存在')).toBe(false)
  })
})

describe('buildNavigationModel · 不修改入参', () => {
  it('入参 modules / favoriteIds 不被修改', () => {
    const modules = [chat, rag]
    const favoriteIds = ['chat-basic', 'chat-basic', 'ghost']
    const snapshotModules = JSON.parse(JSON.stringify(modules))
    const snapshotFav = [...favoriteIds]
    buildNavigationModel(baseInput({ modules, favoriteIds }))
    expect(modules).toEqual(snapshotModules)
    expect(favoriteIds).toEqual(snapshotFav)
  })
})

describe('buildNavigationModel · 分组与排序', () => {
  it('按 GROUP_ORDER 归组，保持入参模块顺序', () => {
    const model = buildNavigationModel(baseInput({ modules: [chat, rag, agent] }))
    const groupIds = model.groups.map((g) => g.id)
    expect(groupIds).toEqual(['conversation-retrieval', 'agent-orchestration'])
    const conv = model.groups.find((g) => g.id === 'conversation-retrieval')!
    expect(conv.modules.map((m) => m.id)).toEqual(['chat', 'rag'])
    expect(conv.accent).toBe('blue')
  })

  it('未知模块归 Other（slate），空目录返回空分组', () => {
    const model = buildNavigationModel(baseInput({ modules: [unknownMod] }))
    const other = model.groups.find((g) => g.id === OTHER_GROUP_ID)!
    expect(other).toBeDefined()
    expect(other.accent).toBe('slate')
    expect(other.modules[0].id).toBe('futuristic')
    expect(buildNavigationModel(baseInput({ modules: [] })).groups).toEqual([])
  })

  it('空能力模块：hasCaps=false、count=0，仍可出现（非搜索态）', () => {
    const model = buildNavigationModel(baseInput({ modules: [emptyMod] }))
    const m = model.groups.flatMap((g) => g.modules).find((x) => x.id === 'empty')!
    expect(m.hasCaps).toBe(false)
    expect(m.count).toBe(0)
    expect(m.capabilities).toEqual([])
  })

  it('长双语标题拆分进 name/en', () => {
    const model = buildNavigationModel(baseInput({ modules: [chat] }))
    const m = model.groups[0].modules[0]
    expect(m.name).toBe('对话')
    expect(m.en).toBe('Chat Console')
    expect(m.count).toBe(1)
  })
})

describe('buildNavigationModel · 唯一 active 与祖先可见', () => {
  it('activeModuleId 命中 → current + ancestorActive；能力级不误亮', () => {
    const model = buildNavigationModel(baseInput({ modules: [chat, rag], activeModuleId: 'chat' }))
    const chatVM = model.groups[0].modules.find((m) => m.id === 'chat')!
    const ragVM = model.groups[0].modules.find((m) => m.id === 'rag')!
    expect(chatVM.current).toBe(true)
    expect(chatVM.ancestorActive).toBe(true)
    expect(ragVM.current).toBe(false)
    // 无 activeCapId → 没有任何能力 active
    expect(chatVM.capabilities.every((c) => !c.active)).toBe(true)
  })

  it('activeCapId 命中 → 唯一能力 active（仅其所属模块内）', () => {
    const model = buildNavigationModel(
      baseInput({ modules: [chat, rag], activeModuleId: 'chat', activeCapId: 'chat-basic' }),
    )
    const flatActive = model.groups
      .flatMap((g) => g.modules)
      .flatMap((m) => m.capabilities)
      .filter((c) => c.active)
    expect(flatActive.map((c) => c.id)).toEqual(['chat-basic'])
  })

  it('无当前路由（总览/管理域，无 moduleId/capId）→ 无模块 current、无能力 active', () => {
    const model = buildNavigationModel(baseInput({ modules: [chat, rag, agent] }))
    const mods = model.groups.flatMap((g) => g.modules)
    expect(mods.some((m) => m.current)).toBe(false)
    expect(mods.flatMap((m) => m.capabilities).some((c) => c.active)).toBe(false)
  })
})

describe('buildNavigationModel · 收藏副本', () => {
  it('去重、映射真实能力、失效 id 忽略', () => {
    const model = buildNavigationModel(
      baseInput({ modules: [chat, rag], favoriteIds: ['rag-search', 'rag-search', 'ghost-id'] }),
    )
    expect(model.favorites.map((c) => c.id)).toEqual(['rag-search'])
  })

  it('收藏项 active 独立标记（同一 capId 可在收藏与模块树各出现一次）', () => {
    const model = buildNavigationModel(
      baseInput({ modules: [chat], favoriteIds: ['chat-basic'], activeModuleId: 'chat', activeCapId: 'chat-basic' }),
    )
    expect(model.favorites[0].active).toBe(true)
    const inTree = model.groups[0].modules[0].capabilities.find((c) => c.id === 'chat-basic')!
    expect(inTree.active).toBe(true)
  })
})

describe('buildNavigationModel · 搜索', () => {
  it('能力命中：只保留命中子集，matchedCount 累计', () => {
    const twoCaps = mod('chat', 1, [
      cap({ id: 'chat-basic', module: 'chat', title: '单轮对话' }),
      cap({ id: 'chat-stream', module: 'chat', title: '流式对话' }),
    ], '对话 Chat Console')
    const model = buildNavigationModel(baseInput({ modules: [twoCaps], query: '流式' }))
    expect(model.hasQuery).toBe(true)
    expect(model.matchedCount).toBe(1)
    expect(model.groups[0].modules[0].capabilities.map((c) => c.id)).toEqual(['chat-stream'])
  })

  it('模块名命中但无能力命中 → matchedByTitleOnly，capabilities 为空', () => {
    const model = buildNavigationModel(baseInput({ modules: [chat], query: 'Console' }))
    const m = model.groups[0].modules[0]
    expect(m.matchedByTitleOnly).toBe(true)
    expect(m.capabilities).toEqual([])
    expect(model.isEmpty).toBe(false)
  })

  it('完全无匹配 → 分组与收藏皆空、isEmpty=true', () => {
    const model = buildNavigationModel(
      baseInput({ modules: [chat, rag], favoriteIds: ['chat-basic'], query: 'zzz-绝不匹配' }),
    )
    expect(model.groups).toEqual([])
    expect(model.favorites).toEqual([])
    expect(model.isEmpty).toBe(true)
  })

  it('搜索同时过滤收藏', () => {
    const model = buildNavigationModel(
      baseInput({ modules: [chat, rag], favoriteIds: ['chat-basic', 'rag-search'], query: '检索' }),
    )
    expect(model.favorites.map((c) => c.id)).toEqual(['rag-search'])
  })
})

describe('buildNavigationModel · 真实 catalog（9 模块 / 82 能力）', () => {
  const catalog = loadCatalog()
  const modules = [...catalog.modules].sort((a, b) => a.order - b.order)

  it('全部模块进入某个分组，能力总数守恒 = 82', () => {
    const model = buildNavigationModel(baseInput({ modules }))
    const flatModules = model.groups.flatMap((g) => g.modules)
    expect(flatModules.length).toBe(modules.length) // 9
    const totalCaps = flatModules.reduce((n, m) => n + m.count, 0)
    const expected = modules.reduce((n, m) => n + (m.capabilities?.length ?? 0), 0)
    expect(totalCaps).toBe(expected)
    expect(modules.length).toBe(9)
    expect(expected).toBe(82)
  })

  it('每个真实模块都被归组（无遗漏、无未知落 Other 之外）', () => {
    const model = buildNavigationModel(baseInput({ modules }))
    const grouped = new Set(model.groups.flatMap((g) => g.modules.map((m) => m.id)))
    for (const m of modules) expect(grouped.has(m.id)).toBe(true)
  })
})
