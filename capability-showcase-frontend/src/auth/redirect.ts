/** 只接受站内单斜杠绝对路径，防开放重定向和路径解析歧义。 */
export function sanitizeInternalPath(raw: unknown): string | null {
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//')) return null
  if (raw.includes('\\') || [...raw].some((character) => {
    const code = character.codePointAt(0) ?? 0
    return code <= 0x1f || code === 0x7f
  })) return null
  return raw
}
