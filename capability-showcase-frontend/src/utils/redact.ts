/**
 * API Key 脱敏工具。
 *
 * 约束：API Key 绝不落 localStorage/sessionStorage/URL/日志；此处仅用于「展示」脱敏，
 * 真实明文仅存在于内存 session store 与请求头注入处。
 */

/** 用于替换明文的掩码符。 */
const MASK = '•'

/**
 * 脱敏 API Key：仅保留尾 4 位，其余用圆点掩盖。
 * - 空串 → 空串
 * - 长度 ≤ 4 → 全部掩盖（不泄露任何字符）
 */
export function redactKey(key: string | null | undefined): string {
  if (!key) return ''
  const trimmed = key.trim()
  if (trimmed.length === 0) return ''
  if (trimmed.length <= 4) return MASK.repeat(trimmed.length)
  return MASK.repeat(6) + trimmed.slice(-4)
}

/**
 * 在任意文本中把出现的密钥明文替换为掩码，避免密钥意外出现在日志/预览/错误信息中。
 * secret 为空时原样返回。
 */
export function redact(text: string, secret: string | null | undefined): string {
  if (!secret) return text
  const s = secret.trim()
  if (s.length === 0) return text
  return text.split(s).join(MASK.repeat(8))
}
