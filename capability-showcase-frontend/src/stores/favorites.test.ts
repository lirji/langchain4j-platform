import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useFavoritesStore } from './favorites'

describe('favorites store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })
  afterEach(() => vi.restoreAllMocks())

  it('忽略脏 JSON，只持久化能力 id', () => {
    localStorage.setItem('showcase.favorites', '{bad')
    setActivePinia(createPinia())
    const store = useFavoritesStore()
    expect(store.ids).toEqual([])
    store.toggle('chat.sync')
    expect(JSON.parse(localStorage.getItem('showcase.favorites')!)).toEqual(['chat.sync'])
    expect(localStorage.getItem('showcase.favorites')).not.toContain('params')
  })

  it('toggle 幂等增删，setItem 抛错时内存状态仍可用', () => {
    const store = useFavoritesStore()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota')
    })
    expect(() => store.toggle('rag.query')).not.toThrow()
    expect(store.isFav('rag.query')).toBe(true)
    expect(() => store.toggle('rag.query')).not.toThrow()
    expect(store.isFav('rag.query')).toBe(false)
  })
})
