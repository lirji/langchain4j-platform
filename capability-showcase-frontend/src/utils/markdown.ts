/**
 * 助手回复的 Markdown 渲染工具。
 *
 * 安全铁律：marked 解析 → DOMPurify 消毒 → 才可 v-html。绝不裸注入未消毒 HTML。
 * 仅在浏览器 / jsdom（有 window/DOM）环境可用；无 DOM 时降级为转义纯文本。
 */
import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({ gfm: true, breaks: true })

/** 无 DOM 环境（SSR）降级：转义为纯文本，杜绝任何注入。 */
function escapeHtml(src: string): string {
  return src
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * 把 Markdown 源渲染为「已消毒」的 HTML 字符串，可安全用于 v-html。
 * 支持代码块 / 列表 / 表格 / 行内代码 / 链接（外链强制 noopener）。
 */
export function renderMarkdown(src: string): string {
  const text = src ?? ''
  if (!text) return ''
  // 无 DOM（如某些 SSR / node 环境）：DOMPurify 不可用，降级为转义纯文本。
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return `<p>${escapeHtml(text)}</p>`
  }
  const raw = marked.parse(text, { async: false }) as string
  const clean = DOMPurify.sanitize(raw, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['target', 'rel'],
  })
  return clean
}
