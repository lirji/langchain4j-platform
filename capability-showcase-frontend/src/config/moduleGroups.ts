/**
 * 模块分组：把 catalog 的模块聚合成侧栏 / 命令面板用的语义大类。
 * 未映射的模块落"其它"。分组顺序即 GROUP_ORDER。
 */

export interface GroupDef {
  id: string
  label: string
}

export const OTHER_GROUP_ID = 'other'

/** 分组展示顺序与中文标签。 */
export const GROUP_ORDER: GroupDef[] = [
  { id: 'conversation-retrieval', label: '对话与检索' },
  { id: 'agent-orchestration', label: '智能体与编排' },
  { id: 'multimodal', label: '多模态' },
  { id: 'platform-interop', label: '平台工程与互操作' },
  { id: OTHER_GROUP_ID, label: '其它' },
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
