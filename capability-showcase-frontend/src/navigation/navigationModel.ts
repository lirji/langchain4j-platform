/**
 * 导航视图模型（纯函数层）——把 catalog 模块 / 收藏 / 搜索 / 当前路由，
 * 构造成侧栏与其子组件直接渲染的不可变视图模型。
 *
 * 约束（可单测的核心）：
 * - 纯函数：不读 DOM / localStorage / route 对象，不修改入参。
 * - 排序权威来自入参 modules（catalog store 已按 Module.order 排好）。
 * - 唯一主 active：能力级 active 仅当 activeCapId 命中；模块级 current 仅当 activeModuleId 命中。
 * - 当前模块强制可见（ancestorActive），由消费方据此展开——但不写回折叠偏好。
 * - 搜索字段与旧 SideNav 保持一致（title/id/path/description/tags）避免语义漂移。
 * - 未知模块归 Other；失效收藏 id 安全忽略；重复收藏 id 去重。
 */

import type { Capability, CapabilityState, Module } from '../types/catalog'
import {
  GROUP_ORDER,
  groupAccent,
  groupIdForModule,
  type AccentKey,
} from '../config/moduleGroups'

export interface NavCapabilityVM {
  id: string
  moduleId: string
  title: string
  method: string
  state: CapabilityState
  /** 当前能力（唯一主 active）。 */
  active: boolean
}

export interface NavModuleVM {
  id: string
  /** 完整标题（如「对话 Chat Console」）。 */
  title: string
  /** 拆出的中文主名（展示用；拆分失败则等于 title）。 */
  name: string
  /** 拆出的英文副名（展示用；无则空串）。 */
  en: string
  groupId: string
  accent: AccentKey
  /** 该模块能力总数（不受搜索影响）。 */
  count: number
  hasCaps: boolean
  /** 当前展示的能力（搜索时为命中子集，否则为全部）。 */
  capabilities: NavCapabilityVM[]
  /** 当前模块（activeModuleId 命中）——用于高亮模块行与强制展开。 */
  current: boolean
  /** 当前路由祖先（当前模块）——消费方据此强制展开，不写回偏好。 */
  ancestorActive: boolean
  /** 搜索态：模块名命中但无能力命中（消费方给「无匹配能力」提示）。 */
  matchedByTitleOnly: boolean
}

export interface NavGroupVM {
  id: string
  label: string
  accent: AccentKey
  modules: NavModuleVM[]
}

export interface NavigationModel {
  groups: NavGroupVM[]
  favorites: NavCapabilityVM[]
  /** 规范化后的查询（trim + lower）。 */
  query: string
  hasQuery: boolean
  /** 命中的能力总数（不含仅模块名命中）。 */
  matchedCount: number
  /** 有查询但分组与收藏都为空 —— 消费方渲染零结果空态。 */
  isEmpty: boolean
}

export interface NavigationInput {
  /** 已按 Module.order 排序的模块（如 catalog store 的 modules）。 */
  modules: Module[]
  /** 收藏能力 id（可能含失效/重复，模型内部安全处理）。 */
  favoriteIds: string[]
  /** 原始筛选串（未规范化亦可，内部会 trim/lower）。 */
  query: string
  /** 当前路由的 moduleId（无则空串）。 */
  activeModuleId: string
  /** 当前路由的 capId（无则空串）。 */
  activeCapId: string
}

/**
 * 拆分双语模块标题为「中文主名 + 英文副名」（纯展示增强）。
 * 规则：前导的非空白 CJK/字母数字段为主名，其后以 ASCII 字母开头的剩余部分为英文副名。
 * 拆分失败（无英文段）时安全回退：name=完整标题、en=''。
 */
export function splitModuleTitle(title: string): { name: string; en: string } {
  const t = (title ?? '').trim()
  const m = /^(.+?)\s+([A-Za-z][\w &./-]*)$/.exec(t)
  if (m && m[1]) return { name: m[1].trim(), en: m[2].trim() }
  return { name: t, en: '' }
}

/** 能力是否命中查询（q 已规范化）。字段集与旧 SideNav 一致。 */
export function capabilityMatches(c: Capability, q: string): boolean {
  if (!q) return true
  return (
    c.title.toLowerCase().includes(q) ||
    c.id.toLowerCase().includes(q) ||
    c.path.toLowerCase().includes(q) ||
    c.description.toLowerCase().includes(q) ||
    (c.tags ?? []).some((t) => t.toLowerCase().includes(q))
  )
}

/** 模块是否命中查询（标题 / id）。 */
export function moduleMatches(m: Module, q: string): boolean {
  if (!q) return true
  return m.title.toLowerCase().includes(q) || m.id.toLowerCase().includes(q)
}

function toCapabilityVM(c: Capability, activeCapId: string): NavCapabilityVM {
  return {
    id: c.id,
    moduleId: c.module,
    title: c.title,
    method: c.method,
    state: c.state,
    active: !!activeCapId && c.id === activeCapId,
  }
}

/** 构造导航视图模型（纯函数）。 */
export function buildNavigationModel(input: NavigationInput): NavigationModel {
  const query = (input.query ?? '').trim().toLowerCase()
  const hasQuery = query.length > 0
  const { activeModuleId, activeCapId } = input

  let matchedCount = 0
  const modulesByGroup = new Map<string, NavModuleVM[]>()

  for (const m of input.modules) {
    const allCaps = m.capabilities ?? []
    const filtered = hasQuery ? allCaps.filter((c) => capabilityMatches(c, query)) : allCaps
    const nameMatch = moduleMatches(m, query)

    // 搜索态：模块既无能力命中、模块名也不命中 → 整体排除。
    if (hasQuery && filtered.length === 0 && !nameMatch) continue

    if (hasQuery) matchedCount += filtered.length

    const groupId = groupIdForModule(m.id)
    const { name, en } = splitModuleTitle(m.title)
    const vm: NavModuleVM = {
      id: m.id,
      title: m.title,
      name,
      en,
      groupId,
      accent: groupAccent(groupId),
      count: allCaps.length,
      hasCaps: allCaps.length > 0,
      capabilities: filtered.map((c) => toCapabilityVM(c, activeCapId)),
      current: !!activeModuleId && m.id === activeModuleId,
      ancestorActive: !!activeModuleId && m.id === activeModuleId,
      matchedByTitleOnly: hasQuery && nameMatch && filtered.length === 0,
    }
    const arr = modulesByGroup.get(groupId) ?? []
    arr.push(vm)
    modulesByGroup.set(groupId, arr)
  }

  const groups: NavGroupVM[] = []
  for (const g of GROUP_ORDER) {
    const mods = modulesByGroup.get(g.id)
    if (mods && mods.length) groups.push({ id: g.id, label: g.label, accent: g.accent, modules: mods })
  }

  // 收藏：去重、映射真实能力、失效 id 忽略、受搜索过滤。
  const byId = new Map<string, Capability>()
  for (const m of input.modules) for (const c of m.capabilities ?? []) byId.set(c.id, c)
  const seen = new Set<string>()
  const favorites: NavCapabilityVM[] = []
  for (const id of input.favoriteIds) {
    if (seen.has(id)) continue
    seen.add(id)
    const c = byId.get(id)
    if (c && capabilityMatches(c, query)) favorites.push(toCapabilityVM(c, activeCapId))
  }

  const isEmpty = hasQuery && groups.length === 0 && favorites.length === 0

  return { groups, favorites, query, hasQuery, matchedCount, isEmpty }
}
