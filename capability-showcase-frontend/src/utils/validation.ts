import type { ParamSpec } from '../types/catalog'

/** 参数值容器（表单模型）。 */
export type FormValues = Record<string, unknown>

/** 判断一个值在表单意义上是否为空。 */
export function isEmptyValue(p: ParamSpec, v: unknown): boolean {
  if (p.type === 'file') return !(v instanceof File)
  if (p.type === 'boolean') return v === undefined || v === null
  return v === undefined || v === null || v === ''
}

/**
 * 纯函数：按 ParamSpec 校验一组表单值，返回 { 参数名 → 错误消息 }（无错误则空对象）。
 * 覆盖：required、number/integer 数字性与 min/max、json/array/object 可解析、文本 maxLength。
 */
export function validateParams(params: ParamSpec[], values: FormValues): Record<string, string> {
  const errors: Record<string, string> = {}
  for (const p of params) {
    const label = p.label || p.name
    const v = values[p.name]
    const empty = isEmptyValue(p, v)

    if (p.required && empty) {
      errors[p.name] = `${label} 为必填项`
      continue
    }
    if (empty) continue

    if (p.type === 'number' || p.type === 'integer') {
      const n = typeof v === 'number' ? v : Number(String(v).trim())
      if (Number.isNaN(n)) {
        errors[p.name] = `${label} 必须为数字`
      } else if (p.type === 'integer' && !Number.isInteger(n)) {
        errors[p.name] = `${label} 必须为整数`
      } else if (p.min != null && n < p.min) {
        errors[p.name] = `${label} 不能小于 ${p.min}`
      } else if (p.max != null && n > p.max) {
        errors[p.name] = `${label} 不能大于 ${p.max}`
      }
    } else if (p.type === 'json' || p.type === 'array' || p.type === 'object') {
      try {
        JSON.parse(String(v))
      } catch {
        errors[p.name] = `${label} 不是合法的 JSON`
      }
    } else if (p.maxLength != null && String(v).length > p.maxLength) {
      errors[p.name] = `${label} 长度不能超过 ${p.maxLength}`
    }
  }
  return errors
}
