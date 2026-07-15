import type { Capability } from '../types/catalog'
import type { FormValues } from './validation'
import {
  API_KEY_HEADER,
  AUTH_HEADER,
  buildHeaderParams,
  buildJsonBody,
  buildTargetUrl,
  isStreamingKind,
} from '../api/client'
import { AUTH_MODE } from '../config'

/**
 * 生成可复制的 curl 预览。
 *
 * 安全约束：绝不写入真实凭证 —— 凭证头恒用占位符（apikey/dual：`X-Api-Key: $API_KEY`；
 * oidc：`Authorization: Bearer $ACCESS_TOKEN`），以免凭证泄露到剪贴板 / 日志 / 分享（DR-1）。
 */
/** POSIX shell 单引号转义：值内 ' → '\'' ，防止含引号/换行/shell 片段的用户值把复制的 curl 打断或注入。 */
function shq(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`
}

export function toCurl(
  cap: Capability,
  values: FormValues,
  opts: { edgeBaseUrl?: string; keyPlaceholder?: string; tokenPlaceholder?: string } = {},
): string {
  const edgeBaseUrl = opts.edgeBaseUrl ?? ''
  const keyPlaceholder = opts.keyPlaceholder ?? '$API_KEY'
  const tokenPlaceholder = opts.tokenPlaceholder ?? '$ACCESS_TOKEN'
  const url = buildTargetUrl(cap.path, cap.params, values, edgeBaseUrl)

  const lines: string[] = [`curl -X ${cap.method} ${shq(url)}`]
  // 凭证头恒为占位符字面量（可见、供用户自行替换/设环境变量），绝不含真实明文。
  // oidc：Casdoor 是唯一凭证 → Bearer 占位符；apikey/dual：X-Api-Key 占位符（dual 以 api-key 为最简示例）。
  if (AUTH_MODE === 'oidc') {
    lines.push(`  -H '${AUTH_HEADER}: Bearer ${tokenPlaceholder}'`)
  } else {
    lines.push(`  -H '${API_KEY_HEADER}: ${keyPlaceholder}'`)
  }

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
