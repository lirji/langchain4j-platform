import { computed, onScopeDispose, reactive, ref, type Ref } from 'vue'
import { humanizeError, isAbortError } from '../api/errors'

/**
 * 通用分页查询：封装筛选防抖、请求中止、**乱序响应保护**（单调序号只认最新一次）。
 * 被 adminUsers store 复用，也可被 RAG 文档列表等视图直接用。
 *
 * 乱序保护是核心：快速切页/改筛选时，慢请求可能后到；用单调 `seq` 只接受最新一次的结果，
 * 并 `AbortController` 中止在途；组件卸载 `onScopeDispose` 中止，杜绝 setState-after-unmount。
 */
export interface PagedQueryOptions<T> {
  fetcher: (p: {
    offset: number
    limit: number
    filters: Record<string, string>
    signal: AbortSignal
  }) => Promise<{ items: T[]; total: number }>
  pageSize?: number
  debounceMs?: number
}

export function usePagedQuery<T>(opts: PagedQueryOptions<T>) {
  const pageSize = ref(opts.pageSize ?? 50)
  const items = ref<T[]>([]) as Ref<T[]>
  const total = ref(0)
  const offset = ref(0)
  const filters = reactive<Record<string, string>>({})
  const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const error = ref<string | null>(null)

  let seq = 0
  let controller: AbortController | null = null
  let debTimer: ReturnType<typeof setTimeout> | null = null

  const hasNext = computed(() => offset.value + pageSize.value < total.value)
  const hasPrev = computed(() => offset.value > 0)

  async function load(): Promise<void> {
    const my = ++seq
    controller?.abort()
    controller = new AbortController()
    status.value = 'loading'
    error.value = null
    try {
      const r = await opts.fetcher({
        offset: offset.value,
        limit: pageSize.value,
        filters: { ...filters },
        signal: controller.signal,
      })
      if (my !== seq) return // 已被更新的请求取代 → 丢弃陈旧结果
      items.value = r.items
      total.value = r.total
      status.value = 'ready'
    } catch (e) {
      if (my !== seq || isAbortError(e)) return
      error.value = humanizeError(e)
      status.value = 'error'
    }
  }

  function scheduleLoad(): void {
    if (debTimer) clearTimeout(debTimer)
    debTimer = setTimeout(() => void load(), opts.debounceMs ?? 300)
  }

  /** 设置/清除一个筛选项并归零 offset，防抖后重载。value 为空串=清除该项。 */
  function setFilter(key: string, value: string): void {
    if (value) filters[key] = value
    else delete filters[key]
    offset.value = 0
    scheduleLoad()
  }

  function nextPage(): void {
    if (hasNext.value) {
      offset.value += pageSize.value
      void load()
    }
  }

  function prevPage(): void {
    if (hasPrev.value) {
      offset.value = Math.max(0, offset.value - pageSize.value)
      void load()
    }
  }

  function reload(): void {
    void load()
  }

  /** 局部失效：就地替换命中项（写成功后避免整表重拉）。 */
  function patchItem(pred: (t: T) => boolean, next: T): void {
    const i = items.value.findIndex(pred)
    if (i >= 0) items.value.splice(i, 1, next)
  }

  function removeItem(pred: (t: T) => boolean): void {
    const i = items.value.findIndex(pred)
    if (i >= 0) items.value.splice(i, 1)
  }

  onScopeDispose(() => {
    controller?.abort()
    if (debTimer) clearTimeout(debTimer)
  })

  return {
    items,
    total,
    offset,
    pageSize,
    filters,
    status,
    error,
    hasNext,
    hasPrev,
    load,
    reload,
    setFilter,
    nextPage,
    prevPage,
    patchItem,
    removeItem,
  }
}
