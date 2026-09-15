import { describe, expect, it } from 'vitest'
import type { Capability, Catalog } from '../types/catalog'
import type { KnowledgeRuntimeView } from '../types/knowledge'
import { applyKnowledgeHonesty } from './runtimeHonesty'

function cap(id: string, state: Capability['state'] = 'ready'): Capability {
  return {
    id,
    module: 'rag',
    title: id,
    description: '',
    method: 'POST',
    path: '/',
    requestKind: 'json',
    params: [],
    requiredScopes: [],
    riskLevel: 'safe',
    state,
    executableByDefault: true,
  }
}

function catalog(caps: Capability[]): Catalog {
  return {
    version: '1',
    modules: [
      { id: 'chat', title: '对话', description: '', order: 1, priority: 'P0', service: 'c', standalone: 'high', capabilities: [cap('chat.sync')] },
      { id: 'rag', title: '知识库', description: '', order: 2, priority: 'P0', service: 'k', standalone: 'high', capabilities: caps },
    ],
  }
}

function view(rag: KnowledgeRuntimeView['rag']): KnowledgeRuntimeView {
  return { contractVersion: 2, publicEnabled: false, sharedImagesSupported: false, rag }
}

describe('applyKnowledgeHonesty', () => {
  it('无 rag 运行时块时原样返回', () => {
    const src = catalog([cap('rag.query')])
    expect(applyKnowledgeHonesty(src, view(undefined))).toBe(src)
  })

  it('非语义 embedding 把 rag.query 从 ready 降为 ready-degraded', () => {
    const out = applyKnowledgeHonesty(
      catalog([cap('rag.query')]),
      view({
        embeddingProvider: 'hash',
        embeddingModel: 'hash',
        semantic: false,
        vectorStoreProvider: 'memory',
        esHybridEnabled: false,
        fusionStrategy: 'rrf',
        graphEnabled: false,
        keywordHybridEnabled: false,
        multimodalEnabled: false,
      }),
    )
    expect(out.modules.find((m) => m.id === 'rag')?.capabilities[0].state).toBe('ready-degraded')
  })

  it('语义就绪时不改 rag.query', () => {
    const out = applyKnowledgeHonesty(
      catalog([cap('rag.query')]),
      view({
        embeddingProvider: 'ollama',
        embeddingModel: 'nomic',
        semantic: true,
        vectorStoreProvider: 'qdrant',
        esHybridEnabled: true,
        fusionStrategy: 'rrf',
        graphEnabled: true,
        keywordHybridEnabled: true,
        multimodalEnabled: false,
      }),
    )
    expect(out.modules.find((m) => m.id === 'rag')?.capabilities[0].state).toBe('ready')
  })

  it('图谱关闭时把 graph 能力降为 flag-off，且不升级已关闭项', () => {
    const out = applyKnowledgeHonesty(
      catalog([cap('rag.graph.query'), cap('rag.graph.entities', 'flag-off')]),
      view({
        embeddingProvider: 'hash',
        embeddingModel: 'hash',
        semantic: false,
        vectorStoreProvider: 'memory',
        esHybridEnabled: false,
        fusionStrategy: 'rrf',
        graphEnabled: false,
        keywordHybridEnabled: false,
        multimodalEnabled: false,
      }),
    )
    const caps = out.modules.find((m) => m.id === 'rag')?.capabilities ?? []
    expect(caps[0].state).toBe('flag-off')
    expect(caps[0].unavailableReason).toContain('graphEnabled')
    expect(caps[1].state).toBe('flag-off')
  })

  it('不改动非 rag 模块', () => {
    const out = applyKnowledgeHonesty(
      catalog([cap('rag.query')]),
      view({
        embeddingProvider: 'hash',
        embeddingModel: 'hash',
        semantic: false,
        vectorStoreProvider: 'memory',
        esHybridEnabled: false,
        fusionStrategy: 'rrf',
        graphEnabled: false,
        keywordHybridEnabled: false,
        multimodalEnabled: false,
      }),
    )
    expect(out.modules.find((m) => m.id === 'chat')?.capabilities[0].state).toBe('ready')
  })
})
