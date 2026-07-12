import type { Capability } from '../types/catalog'
import type { FormValues } from './validation'
import {
  API_KEY_HEADER,
  buildHeaderParams,
  buildJsonBody,
  buildTargetUrl,
  isStreamingKind,
} from '../api/client'

/**
 * 生成可复制的 curl 预览。
 *
 * 安全约束：绝不写入真实 API Key —— X-Api-Key 恒用占位符（默认 $API_KEY），
 * 以免密钥泄露到剪贴板 / 日志 / 分享。
 */
/** POSIX shell 单引号转义：值内 ' → '\'' ，防止含引号/换行/shell 片段的用户值把复制的 curl 打断或注入。 */
function shq(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`
}

export function toCurl(
  cap: Capability,
  values: FormValues,
  opts: { edgeBaseUrl?: string; keyPlaceholder?: string } = {},
): string {
  const edgeBaseUrl = opts.edgeBaseUrl ?? ''
  const keyPlaceholder = opts.keyPlaceholder ?? '$API_KEY'
  const url = buildTargetUrl(cap.path, cap.params, values, edgeBaseUrl)

  const lines: string[] = [`curl -X ${cap.method} ${shq(url)}`]
  // X-Api-Key 恒为占位符字面量（保持 $API_KEY 可见，供用户自行替换/设为环境变量）。
  lines.push(`  -H '${API_KEY_HEADER}: ${keyPlaceholder}'`)

  // 业务 header 参数（in:header）；X-Api-Key 恒为占位符，不受 header 参数影响。
  for (const [name, val] of Object.entries(buildHeaderParams(cap.params, values))) {
    lines.push(`  -H ${shq(`${name}: ${val}`)}`)
  }

  if (cap.requestKind === 'json' || cap.requestKind === 'sse') {
    const body = buildJsonBody(cap.params, values)
    if (Object.keys(body).length > 0) {
      lines.push(`  -H 'Content-Type: application/json'`)
      lines.push(`  -d ${shq(JSON.stringify(body))}`)
    }
  } else if (cap.requestKind === 'multipart' || cap.requestKind === 'multipart-sse') {
    for (const p of cap.params) {
      if (p.in !== 'form-data') continue
      const v = values[p.name]
      if (p.type === 'file') {
        const filename = v instanceof File ? v.name : '<file>'
        lines.push(`  -F ${shq(`${p.name}=@${filename}`)}`)
      } else if (v != null && v !== '') {
        lines.push(`  -F ${shq(`${p.name}=${String(v)}`)}`)
      }
    }
  }

  if (isStreamingKind(cap.requestKind)) {
    lines.push(`  -N`) // 关闭缓冲，观测流式输出
  }

  return lines.join(' \\\n')
}
