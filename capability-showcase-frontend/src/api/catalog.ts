import type { Capability, Catalog, CapabilitySource } from '../types/catalog'
import { API_KEY_HEADER } from './client'
import { CATALOG_URL as CONFIG_CATALOG_URL } from '../config'

/** 能力目录来源（默认打包进前端的静态 catalog.json；无需 API Key）。见 config.ts。 */
export const CATALOG_URL = CONFIG_CATALOG_URL

/** live discovery 端点（best-effort，经网关，需 X-Api-Key）。 */
export const LIVE_DISCOVERY_ENDPOINTS = [
  '/agent/capabilities',
  '/interop/mcp/tools',
  '/channel/capabilities',
  '/eval/capabilities',
] as const

/** 归一化后的 live 描述符（不同端点结构各异，只取能对齐目录的字段）。 */
export interface LiveDescriptor {
  id?: string
  name?: string
  path?: string
  description?: string
}

/** 拉取静态能力目录。 */
export async function fetchCatalog(url: string = CATALOG_URL): Promise<Catalog> {
  const res = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!res.ok) throw new Error(`加载能力目录失败：HTTP ${res.status}`)
  return (await res.json()) as Catalog
}

/** 给目录内所有能力打上来源标记（纯函数，返回新对象）。 */
export function tagCatalogSource(catalog: Catalog, source: CapabilitySource): Catalog {
  return {
    ...catalog,
    modules: catalog.modules.map((m) => ({
      ...m,
      capabilities: (m.capabilities ?? []).map((c) => ({ ...c, source })),
    })),
  }
}

/**
 * 纯函数：把 live discovery 结果并入目录 —— 保守策略。
 * 仅将「已在静态清单中」且被 live 端点确认存在（id/path/name/tag 命中）的能力标记为 source='live'，
 * 不臆造新的可执行表单。传入空描述符时原样返回。
 */
export function mergeLive(catalog: Catalog, descriptors: LiveDescriptor[]): Catalog {
  if (!descriptors.length) return catalog
  const keys = new Set<string>()
  for (const d of descriptors) {
    if (d.id) keys.add(d.id)
    if (d.name) keys.add(d.name)
    if (d.path) keys.add(d.path)
  }
  const matches = (c: Capability): boolean =>
    keys.has(c.id) || keys.has(c.path) || (c.tags ?? []).some((t) => keys.has(t))

  return {
    ...catalog,
    modules: catalog.modules.map((m) => ({
      ...m,
      capabilities: (m.capabilities ?? []).map((c) =>
        matches(c) ? { ...c, source: 'live' as const } : c,
      ),
    })),
  }
}

function coerceDescriptors(payload: unknown): LiveDescriptor[] {
  // 端点可能返回数组，或 { capabilities|tools|items: [...] } 包裹。
  let arr: unknown[] = []
  if (Array.isArray(payload)) arr = payload
  else if (payload && typeof payload === 'object') {
    const obj = payload as Record<string, unknown>
    for (const key of ['capabilities', 'tools', 'items', 'data']) {
      if (Array.isArray(obj[key])) {
        arr = obj[key] as unknown[]
        break
      }
    }
  }
  const out: LiveDescriptor[] = []
  for (const item of arr) {
    if (typeof item === 'string') {
      out.push({ name: item })
    } else if (item && typeof item === 'object') {
      const o = item as Record<string, unknown>
      out.push({
        id: typeof o.id === 'string' ? o.id : undefined,
        name: typeof o.name === 'string' ? o.name : undefined,
        path: typeof o.path === 'string' ? o.path : undefined,
        description: typeof o.description === 'string' ? o.description : undefined,
      })
    }
  }
  return out
}

/**
 * best-effort live discovery：并行拉取各协议端点，任何失败静默忽略，不阻塞页面。
 * 需凭证（api-key 或登录会话；都没有时直接返回空）。凭证互斥：api-key 优先，否则 Bearer。
 */
export async function discoverLive(ctx: {
  apiKey: string
  accessToken?: string
  edgeBaseUrl: string
}): Promise<LiveDescriptor[]> {
  if (!ctx.apiKey && !ctx.accessToken) return []
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (ctx.apiKey) headers[API_KEY_HEADER] = ctx.apiKey
  else if (ctx.accessToken) headers['Authorization'] = `Bearer ${ctx.accessToken}`
  const results = await Promise.allSettled(
    LIVE_DISCOVERY_ENDPOINTS.map(async (path) => {
      const res = await fetch(`${ctx.edgeBaseUrl}${path}`, { headers })
      if (!res.ok) throw new Error(`live ${path} → ${res.status}`)
      return coerceDescriptors(await res.json())
    }),
  )
  const merged: LiveDescriptor[] = []
  for (const r of results) {
    if (r.status === 'fulfilled') merged.push(...r.value)
  }
  return merged
}
