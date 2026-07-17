import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useHistoryStore, type HistoryEntry } from './history'

const entry = (n: number): HistoryEntry => ({
  id: String(n),
  capId: 'chat.sync',
  method: 'POST',
  path: '/chat',
  status: 200,
  elapsedMs: n,
  traceId: null,
  at: n,
  params: { message: `m${n}` },
  ok: true,
})

describe('history store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('最新在前且只保留 50 条', () => {
    const store = useHistoryStore()
    for (let i = 0; i < 55; i += 1) store.record(entry(i))
    expect(store.entries).toHaveLength(50)
    expect(store.entries[0].id).toBe('54')
    expect(store.entries.at(-1)?.id).toBe('5')
  })

  it('replay 只由相同 capId 消费一次，错 cap 不清空', () => {
    const store = useHistoryStore()
    store.requestReplay('chat.sync', { message: 'again' })
    expect(store.consumeReplay('rag.query')).toBeNull()
    expect(store.consumeReplay('chat.sync')).toEqual({ message: 'again' })
    expect(store.consumeReplay('chat.sync')).toBeNull()
  })

  it('clear 清空记录但不隐式写持久化存储', () => {
    const store = useHistoryStore()
    store.record(entry(1))
    store.clear()
    expect(store.entries).toEqual([])
  })
})
