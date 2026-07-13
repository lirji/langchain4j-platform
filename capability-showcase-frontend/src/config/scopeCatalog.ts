/**
 * scope 人话说明字典（前端本地，非中央 registry）——把后端 `SeedRoles` 里散落的 scope token 归类、配上中文说明，
 * 供 ScopePicker 分组渲染与权限展示。
 *
 * 硬约束：这是**已知 scope 的说明字典**，不是白名单——任何**未知 scope**（不在此表）在 UI 上一律**原样保留、照实回写**，
 * 绝不丢弃（后端可能引入新 scope，前端不得静默吞掉）。取值以后端 `SeedRoles.java` 为准，无通配符。
 */

export interface ScopeDef {
  scope: string
  label: string
  desc: string
}

export interface ScopeGroupDef {
  id: string
  label: string
  scopes: ScopeDef[]
}

/** 未知 scope 的归拢组 id/标签。 */
export const UNKNOWN_GROUP_ID = 'other'
export const UNKNOWN_GROUP_LABEL = '其它 / 未知'

export const SCOPE_GROUPS: ScopeGroupDef[] = [
  {
    id: 'conversation',
    label: '对话',
    scopes: [{ scope: 'chat', label: '对话', desc: '调用 /chat 等对话与检索增强能力' }],
  },
  {
    id: 'knowledge',
    label: '知识',
    scopes: [
      { scope: 'ingest', label: '入库', desc: '写当前租户知识库（上传/删除文档）' },
      { scope: 'public-ingest', label: '共享库写', desc: '写 / 删共享（公共）知识库' },
    ],
  },
  {
    id: 'agent',
    label: '智能体',
    scopes: [{ scope: 'agent', label: '智能体', desc: 'ReAct / 多 Agent DAG 编排' }],
  },
  {
    id: 'approval',
    label: '审批',
    scopes: [{ scope: 'approve', label: '审批', desc: '退款审批工作流' }],
  },
  {
    id: 'analytics',
    label: '分析',
    scopes: [{ scope: 'analytics', label: '数据分析', desc: 'NL2SQL 等数据分析能力' }],
  },
  {
    id: 'channel',
    label: '通道',
    scopes: [{ scope: 'channel', label: '通道', desc: '出站投递 / 回调 / 渠道桥' }],
  },
  {
    id: 'multimodal',
    label: '多模态',
    scopes: [
      { scope: 'vision', label: '视觉', desc: '图像描述' },
      { scope: 'voice', label: '语音', desc: '语音闭环 ASR→TTS' },
    ],
  },
  {
    id: 'platform',
    label: '平台管理',
    scopes: [
      { scope: 'role-admin', label: '平台管理', desc: '管理账号 / 角色（本控制台）' },
      { scope: 'eval', label: '评测', desc: '回归评测客户端' },
    ],
  },
]

/** scope → { label, group(组标签), desc } 的索引（一次构建）。 */
const INDEX: Record<string, { label: string; group: string; desc: string }> = (() => {
  const idx: Record<string, { label: string; group: string; desc: string }> = {}
  for (const g of SCOPE_GROUPS) {
    for (const s of g.scopes) {
      idx[s.scope] = { label: s.label, group: g.label, desc: s.desc }
    }
  }
  return idx
})()

/** 已知 scope → 说明；未知返回 null（调用方仍须保留原值）。 */
export function describeScope(scope: string): { label: string; group: string; desc: string } | null {
  return INDEX[scope] ?? null
}

/** 一个已归类的 scope 项：known=false 表示未在字典中（原样保留展示）。 */
export interface GroupedScopeItem {
  scope: string
  label: string
  desc: string
  known: boolean
}

export interface GroupedScopes {
  id: string
  label: string
  items: GroupedScopeItem[]
}

/**
 * 把任意 scope 列表按组归拢，**未知 scope 落"其它/未知"组且标 known=false，绝不丢弃**。
 * 保持组顺序稳定（SCOPE_GROUPS 顺序，未知组殿后），组内保持传入顺序。
 */
export function groupScopes(scopes: string[]): GroupedScopes[] {
  const set = new Set(scopes)
  const out: GroupedScopes[] = []
  for (const g of SCOPE_GROUPS) {
    const items: GroupedScopeItem[] = g.scopes
      .filter((s) => set.has(s.scope))
      .map((s) => ({ scope: s.scope, label: s.label, desc: s.desc, known: true }))
    if (items.length) {
      out.push({ id: g.id, label: g.label, items })
    }
  }
  const unknown = scopes.filter((s) => !INDEX[s])
  if (unknown.length) {
    out.push({
      id: UNKNOWN_GROUP_ID,
      label: UNKNOWN_GROUP_LABEL,
      items: unknown.map((s) => ({ scope: s, label: s, desc: '未知 scope（前端字典未收录，原样保留）', known: false })),
    })
  }
  return out
}

/** 供 ScopePicker 渲染"全部可选 scope"（已知字典的并集）；编辑时并入当前值里的未知项。 */
export function allKnownScopes(): string[] {
  return SCOPE_GROUPS.flatMap((g) => g.scopes.map((s) => s.scope))
}
