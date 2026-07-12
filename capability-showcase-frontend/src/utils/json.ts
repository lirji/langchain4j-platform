/** JSON 与格式化相关的小工具。 */

/** 安全解析 JSON，失败返回 undefined（不抛异常）。 */
export function tryParseJson(text: string | null | undefined): unknown {
  if (text == null) return undefined
  const t = text.trim()
  if (t === '') return undefined
  try {
    return JSON.parse(t)
  } catch {
    return undefined
  }
}

/** 美化打印 JSON；不可序列化时回退为字符串。 */
export function prettyJson(value: unknown, space = 2): string {
  try {
    return JSON.stringify(value, null, space)
  } catch {
    return String(value)
  }
}

/** JSON 值的类型分类，供高亮着色。 */
export type JsonValueKind = 'string' | 'number' | 'boolean' | 'null' | 'object' | 'array'

export function classifyJson(value: unknown): JsonValueKind {
  if (value === null) return 'null'
  if (Array.isArray(value)) return 'array'
  const t = typeof value
  if (t === 'string') return 'string'
  if (t === 'number') return 'number'
  if (t === 'boolean') return 'boolean'
  return 'object'
}

/** 人类可读的字节数。 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb.toFixed(1)} KB`
  return `${(kb / 1024).toFixed(1)} MB`
}
