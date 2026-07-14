/**
 * 模块分组：把 catalog 的模块聚合成侧栏 / 命令面板用的语义大类。
 * 未映射的模块落"其它"。分组顺序即 GROUP_ORDER。
 *
 * 每个分组还携带一个「强调色 key」（accent），用于方案③彩色编码导航——
 * component/tokens 把 accent 映射为 --g / --g-soft / --g-line 三件套。
 * 强调色只表「分类」，不表「状态」，与五态状态色语义不交叉。
 */

/** 分组强调色 key（对应 tokens.css 里的 [data-accent] 派生色）。 */
export type AccentKey = 'blue' | 'violet' | 'amber' | 'teal' | 'slate' | 'gold'

export interface GroupDef {
  id: string
  label: string
  accent: AccentKey
}

export const OTHER_GROUP_ID = 'other'

/** 分组展示顺序、中文标签与强调色。 */
export const GROUP_ORDER: GroupDef[] = [
  { id: 'conversation-retrieval', label: '对话与检索', accent: 'blue' },
  { id: 'agent-orchestration', label: '智能体与编排', accent: 'violet' },
  { id: 'multimodal', label: '多模态', accent: 'amber' },
  { id: 'platform-interop', label: '平台工程与互操作', accent: 'teal' },
  { id: OTHER_GROUP_ID, label: '其它', accent: 'slate' },
]

/** 静态映射：moduleId → groupId。 */
const GROUP_OF: Record<string, string> = {
  chat: 'conversation-retrieval',
  rag: 'conversation-retrieval',
  agent: 'agent-orchestration',
  tasks: 'agent-orchestration',
  workflow: 'agent-orchestration',
  analytics: 'agent-orchestration',
  multimodal: 'multimodal',
  'interop-eval': 'platform-interop',
  channel: 'platform-interop',
}

export function groupIdForModule(moduleId: string): string {
  return GROUP_OF[moduleId] ?? OTHER_GROUP_ID
}

export function groupLabel(groupId: string): string {
  return GROUP_ORDER.find((g) => g.id === groupId)?.label ?? '其它'
}

/** 分组强调色 key；未知组回退中性 slate。 */
export function groupAccent(groupId: string): AccentKey {
  return GROUP_ORDER.find((g) => g.id === groupId)?.accent ?? 'slate'
}
