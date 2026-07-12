import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 收藏 store：仅持久化能力 id（非敏感）到 localStorage(showcase.favorites)。
 * 绝不存请求参数/结果等潜在敏感数据。
 */
const FAV_KEY = 'showcase.favorites'

function readStored(): string[] {
  try {
    const raw = localStorage.getItem(FAV_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (Array.isArray(parsed)) return parsed.filter((x): x is string => typeof x === 'string')
  } catch {
    /* localStorage 不可用 / 脏数据时忽略 */
  }
  return []
}

export const useFavoritesStore = defineStore('favorites', () => {
  const ids = ref<string[]>(readStored())

  function persist(): void {
    try {
      localStorage.setItem(FAV_KEY, JSON.stringify(ids.value))
    } catch {
      /* 忽略 */
    }
  }

  function isFav(id: string): boolean {
    return ids.value.includes(id)
  }

  function toggle(id: string): void {
    const i = ids.value.indexOf(id)
    if (i >= 0) ids.value.splice(i, 1)
    else ids.value.push(id)
    persist()
  }

  return { ids, isFav, toggle }
})
