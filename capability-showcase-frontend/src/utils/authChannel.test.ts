import { describe, it, expect } from 'vitest'
import { broadcastLogout, onRemoteLogout } from './authChannel'

/** BroadcastChannel 投递是异步的：给一点时间并加超时兜底，避免 hang。 */
function withTimeout<T>(p: Promise<T>, ms = 500): Promise<T> {
  return Promise.race([
    p,
    new Promise<T>((_, rej) => setTimeout(() => rej(new Error('timeout')), ms)),
  ])
}

describe('authChannel（多标签登出）', () => {
  it('broadcastLogout → 另一个标签(另一 channel)收到 logout 信号', async () => {
    const other = new BroadcastChannel('auth-logout')
    const got = new Promise<string>((resolve) => {
      other.onmessage = (ev) => resolve(String(ev.data))
    })
    broadcastLogout()
    await expect(withTimeout(got)).resolves.toBe('logout')
    other.close()
  })

  it('onRemoteLogout 收到其它标签的登出信号 → 触发回调', async () => {
    const other = new BroadcastChannel('auth-logout')
    const fired = new Promise<void>((resolve) => onRemoteLogout(() => resolve()))
    other.postMessage('logout')
    await expect(withTimeout(fired)).resolves.toBeUndefined()
    other.close()
  })

  it('onRemoteLogout 忽略非 logout 消息', async () => {
    const other = new BroadcastChannel('auth-logout')
    let fired = false
    onRemoteLogout(() => {
      fired = true
    })
    other.postMessage('theme-changed')
    await new Promise((r) => setTimeout(r, 60))
    expect(fired).toBe(false)
    other.close()
  })
})
