import { describe, it, expect, vi } from 'vitest'
import { SseParser, consumeSseStream, type SseEvent } from './sse'

function streamFromChunks(chunks: string[]): ReadableStream<Uint8Array> {
  const enc = new TextEncoder()
  let i = 0
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (i < chunks.length) {
        controller.enqueue(enc.encode(chunks[i++]))
      } else {
        controller.close()
      }
    },
  })
}

describe('SseParser', () => {
  it('拼接跨 push 的半帧', () => {
    const p = new SseParser()
    expect(p.push('data: hel')).toEqual([])
    const out = p.push('lo\n\n')
    expect(out).toEqual([{ event: 'message', data: 'hello', id: undefined }])
  })

  it('解析命名事件', () => {
    const p = new SseParser()
    const out = p.push('event: done\ndata: {"ok":true}\n\n')
    expect(out).toEqual([{ event: 'done', data: '{"ok":true}', id: undefined }])
  })

  it('忽略 : 心跳/注释行', () => {
    const p = new SseParser()
    expect(p.push(':\n\n')).toEqual([])
    expect(p.push(': keep-alive\n\n')).toEqual([])
  })

  it('剥除 data 值的一个前导空格（且只剥一个）', () => {
    const p = new SseParser()
    expect(p.push('data: hi\n\n')).toEqual([{ event: 'message', data: 'hi', id: undefined }])
    const out = p.push('data:  x\n\n') // 两个空格 → 剥一个，保留一个
    expect(out).toEqual([{ event: 'message', data: ' x', id: undefined }])
  })

  it('无前导空格也能解析', () => {
    const p = new SseParser()
    expect(p.push('data:token\n\n')).toEqual([{ event: 'message', data: 'token', id: undefined }])
  })

  it('多个 data 行以换行连接', () => {
    const p = new SseParser()
    expect(p.push('data: a\ndata: b\n\n')).toEqual([
      { event: 'message', data: 'a\nb', id: undefined },
    ])
  })

  it('归一 CRLF 帧', () => {
    const p = new SseParser()
    expect(p.push('data: x\r\n\r\n')).toEqual([{ event: 'message', data: 'x', id: undefined }])
  })

  it('flush 补发无收尾空行的末帧', () => {
    const p = new SseParser()
    expect(p.push('data: last')).toEqual([])
    expect(p.flush()).toEqual([{ event: 'message', data: 'last', id: undefined }])
  })

  it('携带 id', () => {
    const p = new SseParser()
    expect(p.push('id: 42\ndata: y\n\n')).toEqual([{ event: 'message', data: 'y', id: '42' }])
  })
})

describe('consumeSseStream', () => {
  it('逐 token 与命名事件分派，正常结束调用 onDone(complete)', async () => {
    const tokens: string[] = []
    const named: [string, string][] = []
    const done = vi.fn()
    await consumeSseStream(streamFromChunks(['data: he', 'llo\n\n', 'event: done\ndata: bye\n\n']), {
      onToken: (t) => tokens.push(t),
      onNamed: (n, d) => named.push([n, d]),
      onDone: done,
    })
    expect(tokens.join('')).toBe('hello')
    expect(named).toEqual([['done', 'bye']])
    expect(done).toHaveBeenCalledWith('complete')
  })

  it('AbortError 走 onDone(abort)，不触发 onError', async () => {
    const onError = vi.fn()
    const onDone = vi.fn()
    const abortStream = new ReadableStream<Uint8Array>({
      pull() {
        throw new DOMException('aborted', 'AbortError')
      },
    })
    await consumeSseStream(abortStream, { onError, onDone })
    expect(onDone).toHaveBeenCalledWith('abort')
    expect(onError).not.toHaveBeenCalled()
  })

  it('非中止错误走 onError + onDone(error)', async () => {
    const onError = vi.fn()
    const onDone = vi.fn()
    const errStream = new ReadableStream<Uint8Array>({
      pull() {
        throw new Error('boom')
      },
    })
    await consumeSseStream(errStream, { onError, onDone })
    expect(onError).toHaveBeenCalled()
    expect(onDone).toHaveBeenCalledWith('error')
  })

  it('onEvent 收到全部事件（含默认 message）', async () => {
    const events: SseEvent[] = []
    await consumeSseStream(streamFromChunks(['data: a\n\n', 'event: error\ndata: nope\n\n']), {
      onEvent: (ev) => events.push(ev),
    })
    expect(events.map((e) => e.event)).toEqual(['message', 'error'])
  })
})
