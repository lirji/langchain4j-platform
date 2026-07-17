import { describe, it, expect } from 'vitest'
import { parseReflexionEvents } from './reflexionStream'
import type { SseEvent } from '../../api/sse'

function ev(event: string, data: unknown): SseEvent {
  return { event, data: typeof data === 'string' ? data : JSON.stringify(data) }
}

describe('parseReflexionEvents', () => {
  it('完整两轮序列 → 按 n 合并答案与评分、finalize、收敛', () => {
    const events: SseEvent[] = [
      ev('attempt-start', { n: 1 }),
      ev('answer', { n: 1, answer: '答案一' }),
      ev('critique', {
        n: 1,
        answer: '答案一',
        aggregate: 0.6,
        correctness: 0.6,
        completeness: 0.5,
        clarity: 0.7,
        mainIssue: '不够全面',
      }),
      ev('attempt-start', { n: 2 }),
      ev('answer', { n: 2, answer: '答案二' }),
      ev('critique', {
        n: 2,
        answer: '答案二',
        aggregate: 0.9,
        correctness: 0.9,
        completeness: 0.9,
        clarity: 0.9,
        mainIssue: 'n/a',
      }),
      ev('done', {
        question: 'q',
        finalAnswer: '答案二',
        attempts: [],
        acceptedByThreshold: true,
        tenantId: 't',
      }),
    ]
    const v = parseReflexionEvents(events)
    expect(v.rounds).toHaveLength(2)
    expect(v.rounds[0].answer).toBe('答案一')
    expect(v.rounds[0].scores?.aggregate).toBe(0.6)
    expect(v.rounds[0].scores?.completeness).toBe(0.5)
    expect(v.rounds[0].mainIssue).toBe('不够全面')
    expect(v.rounds[1].scores?.aggregate).toBe(0.9)
    expect(v.rounds[1].status).toBe('scored')
    expect(v.currentAnswer).toBe('答案二')
    expect(v.converged).toBe(true)
    expect(v.finalized).toBe(true)
  })

  it('乱序事件也按 n 排序', () => {
    const v = parseReflexionEvents([
      ev('answer', { n: 2, answer: 'B' }),
      ev('answer', { n: 1, answer: 'A' }),
    ])
    expect(v.rounds.map((r) => r.n)).toEqual([1, 2])
  })

  it('未 finalize：currentAnswer 用最新一轮答案，converged=null', () => {
    const v = parseReflexionEvents([
      ev('attempt-start', { n: 1 }),
      ev('answer', { n: 1, answer: '半成品' }),
    ])
    expect(v.currentAnswer).toBe('半成品')
    expect(v.converged).toBeNull()
    expect(v.finalized).toBe(false)
    expect(v.rounds[0].status).toBe('critiquing')
  })

  it('answer 有 n 但缺 answer 字段 → 挂 rawFallback，不抛、保留该轮', () => {
    const v = parseReflexionEvents([ev('answer', { n: 1 })])
    expect(v.rounds).toHaveLength(1)
    expect(v.rounds[0].rawFallback).toBeDefined()
    expect(v.rounds[0].answer).toBeUndefined()
  })

  it('answer 非法 JSON（无法定位轮次）→ 进 unknown，不抛', () => {
    const v = parseReflexionEvents([
      ev('attempt-start', { n: 1 }),
      { event: 'answer', data: '{不是合法json' },
    ])
    expect(v.rounds).toHaveLength(1)
    expect(v.unknown).toHaveLength(1)
  })

  it('critique 非法 JSON → 进 unknown，不抛', () => {
    const v = parseReflexionEvents([{ event: 'critique', data: 'oops' }])
    expect(v.unknown).toHaveLength(1)
    expect(v.rounds).toHaveLength(0)
  })

  it('空事件 / 游离 message 优雅降级', () => {
    const empty = parseReflexionEvents([])
    expect(empty.rounds).toHaveLength(0)
    expect(empty.finalized).toBe(false)
    expect(empty.currentAnswer).toBe('')
    const stray = parseReflexionEvents([{ event: 'message', data: 'stray' }])
    expect(stray.unknown).toHaveLength(1)
    expect(stray.rounds).toHaveLength(0)
  })

  it('done 带 attempts 且无逐轮 critique → 用 attempts 兜底补齐轮次', () => {
    const v = parseReflexionEvents([
      ev('done', {
        question: 'q',
        finalAnswer: 'F',
        acceptedByThreshold: false,
        tenantId: 't',
        attempts: [
          { n: 1, answer: 'a1', aggregate: 0.5, correctness: 0.5, completeness: 0.5, clarity: 0.5, mainIssue: 'x' },
          { n: 2, answer: 'a2', aggregate: 0.7, correctness: 0.7, completeness: 0.7, clarity: 0.7, mainIssue: 'n/a' },
        ],
      }),
    ])
    expect(v.rounds).toHaveLength(2)
    expect(v.rounds[0].answer).toBe('a1')
    expect(v.rounds[1].scores?.aggregate).toBe(0.7)
    expect(v.currentAnswer).toBe('F')
    expect(v.converged).toBe(false)
    expect(v.finalized).toBe(true)
  })

  it('error 事件填 errorText', () => {
    expect(parseReflexionEvents([ev('error', { error: '崩了' })]).errorText).toBe('崩了')
  })
})
