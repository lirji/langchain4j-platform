/** 文本搜索高亮工具：把文本按查询词切成命中 / 非命中片段，供组件用 <mark> 渲染（不走 v-html，天然防注入）。 */

export interface HighlightSegment {
  text: string
  hit: boolean
}

/**
 * 将 text 按 query 拆成命中 / 非命中片段（大小写不敏感）。
 * query 为空（去空白后）时返回单个非命中片段，等价于原样文本。
 */
export function highlightSegments(text: string, query: string): HighlightSegment[] {
  const q = query.trim().toLowerCase()
  if (!q) return [{ text, hit: false }]

  const segments: HighlightSegment[] = []
  const lower = text.toLowerCase()
  let cursor = 0
  while (cursor < text.length) {
    const idx = lower.indexOf(q, cursor)
    if (idx === -1) {
      segments.push({ text: text.slice(cursor), hit: false })
      break
    }
    if (idx > cursor) segments.push({ text: text.slice(cursor, idx), hit: false })
    segments.push({ text: text.slice(idx, idx + q.length), hit: true })
    cursor = idx + q.length
  }
  return segments
}

/** 统计 query 在 text 中的命中次数（大小写不敏感、不重叠）。query 为空时返回 0。 */
export function countMatches(text: string, query: string): number {
  const q = query.trim().toLowerCase()
  if (!q) return 0
  const lower = text.toLowerCase()
  let count = 0
  let idx = lower.indexOf(q)
  while (idx !== -1) {
    count += 1
    idx = lower.indexOf(q, idx + q.length)
  }
  return count
}
