/**
 * 多标签登出同步（DR-1，评审 M5）：用 BroadcastChannel 在同源标签间广播「登出信号」。
 *
 * 硬约束：**只广播信号、绝不广播 token**；不写 localStorage（不污染 `localStorage.length===0` 不变量）。
 * 一个标签登出 → 其它标签收到信号即本地清态（下次请求 401 → 会话过期模态）。
 * BroadcastChannel 规范：发送方**不会**收到自己发出的消息，故当前登出的标签不会自触发。
 */
const CHANNEL_NAME = 'auth-logout'
const LOGOUT_SIGNAL = 'logout'

let channel: BroadcastChannel | null = null

/** 懒取单例 channel；环境不支持（老浏览器/部分测试环境）返回 null，调用方优雅降级。 */
function getChannel(): BroadcastChannel | null {
  if (typeof BroadcastChannel === 'undefined') return null
  if (!channel) channel = new BroadcastChannel(CHANNEL_NAME)
  return channel
}

/** 广播「已登出」信号给其它标签（best-effort，失败静默）。 */
export function broadcastLogout(): void {
  try {
    getChannel()?.postMessage(LOGOUT_SIGNAL)
  } catch {
    /* 环境不支持或已关闭：忽略，多标签同步只是增强、非必需 */
  }
}

/** 订阅其它标签的登出信号；收到即回调 cb。返回取消订阅函数。 */
export function onRemoteLogout(cb: () => void): () => void {
  const ch = getChannel()
  if (!ch) return () => {}
  const handler = (ev: MessageEvent): void => {
    if (ev.data === LOGOUT_SIGNAL) cb()
  }
  ch.addEventListener('message', handler)
  return () => ch.removeEventListener('message', handler)
}
