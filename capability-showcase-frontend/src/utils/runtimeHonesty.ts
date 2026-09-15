import type { Capability, Catalog, CapabilityState } from '../types/catalog'
import type { KnowledgeRuntimeView, RagRuntimeView } from '../types/knowledge'

/**
 * 用 /rag/config 运行时形态覆盖静态目录五态：只降级、不升级。
 * 静态 catalog 标 ready 时，HashEmbedding 应显示 ready-degraded；图谱未开应显示 flag-off。
 */
export function applyKnowledgeHonesty(catalog: Catalog, view: KnowledgeRuntimeView): Catalog {
  const rag = view.rag
  if (!rag) return catalog
  return {
    ...catalog,
    modules: catalog.modules.map((m) => {
      if (m.id !== 'rag') return m
      return {
        ...m,
        capabilities: (m.capabilities ?? []).map((c) => overlayRagCapability(c, rag)),
      }
    }),
  }
}

function overlayRagCapability(cap: Capability, rag: RagRuntimeView): Capability {
  if (cap.id === 'rag.query' && cap.state === 'ready' && !rag.semantic) {
    return withState(cap, 'ready-degraded')
  }
  if (
    (cap.id === 'rag.graph.query' || cap.id === 'rag.graph.entities') &&
    cap.state === 'ready' &&
    !rag.graphEnabled
  ) {
    return {
      ...withState(cap, 'flag-off'),
      unavailableReason: '运行时 GraphRAG 未开启（/rag/config.rag.graphEnabled=false）。',
    }
  }
  return cap
}

function withState(cap: Capability, state: CapabilityState): Capability {
  return { ...cap, state }
}
