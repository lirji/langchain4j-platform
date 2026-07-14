/**
 * 能力五态元数据 —— 单一事实源。
 * 供 StateBadge（完整徽章）、侧栏 / 命令面板（compact 状态点）共用，
 * 避免 tone 映射在多处重复而漂移。tone 与 tokens.css 的语义色一致。
 */
import type { CapabilityState } from '../types/catalog'

export interface StateMeta {
  label: string
  icon: string
  tone: 'ok' | 'ok-warn' | 'off' | 'warn' | 'danger'
  hint: string
}

export const STATE_META: Record<CapabilityState, StateMeta> = {
  ready: { label: '就绪', icon: '●', tone: 'ok', hint: '默认可执行。' },
  'ready-degraded': {
    label: '就绪·降级',
    icon: '◐',
    tone: 'ok-warn',
    hint: '可执行，但为内存/确定性降级实现（非真实语义/生产依赖）。',
  },
  'flag-off': {
    label: '未启用',
    icon: '○',
    tone: 'off',
    hint: '需开启对应 feature flag 才注册，默认不可执行。',
  },
  'scope-required': {
    label: '需授权',
    icon: '◆',
    tone: 'warn',
    hint: '需要特定 scope（如 ingest/approve），否则返回 403。',
  },
  'display-only': {
    label: '已锁定',
    icon: '■',
    tone: 'danger',
    hint: '破坏性写/删，默认仅展示不执行。',
  },
}

/** 状态 → tone（语义色 key）。 */
export function stateTone(state: CapabilityState): StateMeta['tone'] {
  return STATE_META[state].tone
}

/** 五态展示顺序（图例用）。 */
export const STATE_ORDER: CapabilityState[] = [
  'ready',
  'ready-degraded',
  'scope-required',
  'flag-off',
  'display-only',
]
