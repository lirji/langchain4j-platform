/**
 * 能力目录类型 —— 与后端 record 序列化严格自洽。
 *
 * 所有枚举值均为「小写-kebab」，与后端 @JsonValue 序列化一致（如 form-data、ready-degraded）。
 * 字段名与 CapabilityCatalog / CapabilityModule / CapabilityEndpoint / ParamSpec 完全对应。
 */

/** multipart-sse：multipart 请求体 + SSE 响应流（如 /voice/chat/stream）。 */
export type RequestKind = 'json' | 'multipart' | 'sse' | 'multipart-sse' | 'none'
export type ResponseKind = 'json' | 'sse' | 'text'
export type RiskLevel = 'safe' | 'caution' | 'destructive'
export type CapabilityState =
  | 'ready'
  | 'ready-degraded'
  | 'flag-off'
  | 'scope-required'
  | 'display-only'
/** 注意：连字符 form-data，不是 formData。 */
export type ParamIn = 'body' | 'query' | 'path' | 'form-data' | 'header'
export type ParamType =
  | 'string'
  | 'text'
  | 'number'
  | 'integer'
  | 'boolean'
  | 'select'
  | 'file'
  | 'json'
  | 'array'
  | 'object'

/** 前端运行时附加：能力来源（静态清单 / live discovery 发现）。不在后端 record。 */
export type CapabilitySource = 'manifest' | 'live'

export interface ParamSpec {
  name: string
  in: ParamIn
  type: ParamType
  required: boolean
  label?: string
  help?: string
  defaultValue?: unknown
  placeholder?: string
  enumValues?: string[]
  min?: number
  max?: number
  maxLength?: number
  accept?: string
  example?: string
  group?: string
}

/** 一条命名示例预设：{@link body} 为请求体 JSON 字符串（形状同 {@link Capability.example}）。 */
export interface CapabilityExample {
  label: string
  body: string
  description?: string
}

export interface Capability {
  id: string
  module: string
  title: string
  description: string
  method: string
  path: string
  requestKind: RequestKind
  responseKind?: ResponseKind
  params: ParamSpec[]
  example?: string
  /** 多示例预设：有则运行器渲染成一排一键载入的 chip；单 example 仍走 example 字段。 */
  examples?: CapabilityExample[]
  requiredScopes: string[]
  featureFlag?: string
  featureFlagDefault?: boolean
  riskLevel: RiskLevel
  state: CapabilityState
  executableByDefault: boolean
  sseEvents?: string[]
  docUrl?: string
  tags?: string[]
  /** 运行时标注，非后端字段。默认 manifest。 */
  source?: CapabilitySource
}

export interface Module {
  id: string
  title: string
  description: string
  icon?: string
  order: number
  priority: string
  service: string
  standalone: string
  capabilities: Capability[]
}

export interface Catalog {
  version: string
  generatedAt?: string
  modules: Module[]
}
