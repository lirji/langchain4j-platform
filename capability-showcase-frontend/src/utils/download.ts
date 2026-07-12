/** 客户端下载工具：把文本内容触发为浏览器下载（响应体导出 / 转录导出）。 */

/**
 * 触发浏览器下载一段文本内容。
 * 仅在浏览器环境可用；SSR / 测试环境（无 document / URL.createObjectURL）静默跳过。
 */
export function downloadText(filename: string, content: string, mime = 'text/plain'): void {
  if (typeof document === 'undefined') return
  if (typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') return
  try {
    const blob = new Blob([content], { type: `${mime};charset=utf-8` })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.rel = 'noopener'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch {
    /* 下载不可用时静默降级 */
  }
}
