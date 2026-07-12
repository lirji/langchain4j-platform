import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { Capability, Catalog, Module } from '../types/catalog'
import {
  discoverLive,
  fetchCatalog,
  mergeLive,
  tagCatalogSource,
  type LiveDescriptor,
} from '../api/catalog'
import { useSessionStore } from './session'

export type CatalogStatus = 'idle' | 'loading' | 'ready' | 'error'

/** 能力目录 store：加载静态清单 + best-effort 合并 live discovery。 */
export const useCatalogStore = defineStore('catalog', () => {
  const catalog = ref<Catalog | null>(null)
  const status = ref<CatalogStatus>('idle')
  const error = ref<string | null>(null)
  const liveTools = ref<LiveDescriptor[]>([])

  const modules = computed<Module[]>(() =>
    [...(catalog.value?.modules ?? [])].sort((a, b) => a.order - b.order),
  )
  const allCapabilities = computed<Capability[]>(() =>
    modules.value.flatMap((m) => m.capabilities ?? []),
  )

  function moduleById(id: string): Module | undefined {
    return modules.value.find((m) => m.id === id)
  }
  function capabilityById(id: string): Capability | undefined {
    return allCapabilities.value.find((c) => c.id === id)
  }

  /** 加载目录（必需）；随后 fire-and-forget 合并 live discovery（不阻塞）。 */
  async function load(): Promise<void> {
    if (status.value === 'loading') return
    status.value = 'loading'
    error.value = null
    try {
      const raw = await fetchCatalog()
      catalog.value = tagCatalogSource(raw, 'manifest')
      status.value = 'ready'
      void refreshLive()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      status.value = 'error'
    }
  }

  /** 单独触发 live discovery 合并（如填 Key 后）。失败静默回退，保留静态清单。 */
  async function refreshLive(): Promise<void> {
    if (!catalog.value) return
    const session = useSessionStore()
    if (!session.hasCredential) return
    try {
      const ctx = session.runContext()
      const descriptors = await discoverLive({
        apiKey: ctx.apiKey,
        accessToken: ctx.accessToken,
        edgeBaseUrl: ctx.edgeBaseUrl,
      })
      liveTools.value = descriptors
      catalog.value = mergeLive(catalog.value, descriptors)
    } catch {
      // 静默回退：目录保持 manifest 态。
    }
  }

  return {
    catalog,
    status,
    error,
    liveTools,
    modules,
    allCapabilities,
    moduleById,
    capabilityById,
    load,
    refreshLive,
  }
})
