import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { redactKey } from '../utils/redact'
import { EDGE_BASE_URL, OIDC_ENABLED } from '../config'
import { useAuthStore } from './auth'
import type { RunContext } from '../types/api'

/** 当前生效的凭证模式：api-key 显式覆盖优先，其次 Bearer 登录，最后无凭证。 */
export type CredentialMode = 'bearer' | 'api-key' | 'none'

/**
 * 会话 store：持有 API Key —— 仅内存。
 *
 * 硬约束：绝不写入 localStorage / sessionStorage / URL / 日志。刷新即清空（符合"试用控制台"心智）。
 *
 * 登录会话令牌不在此，在 [[auth]] store（同为内存态）。本 store 负责把两种凭证收敛进
 * {@link runContext} 单一注入路径，并提供 {@link hasCredential} 供 gate/UI 判"是否可执行"。
 */
export const useSessionStore = defineStore('session', () => {
  const apiKey = ref('')
  // direct mode 网关基址：来自构建期 VITE_EDGE_BASE_URL（同源留空，跨域分离部署为网关地址）。
  const edgeBaseUrl = ref(EDGE_BASE_URL)

  const hasApiKey = computed(() => apiKey.value.trim().length > 0)
  const maskedApiKey = computed(() => redactKey(apiKey.value))

  /**
   * 是否具备可执行凭证：手输 api-key 或已登录会话（二者其一）。gate 与各视图的"可执行"判据。
   * 直接访问 store 属性（非解构）以保留 Pinia 反应性；在 getter 体内延迟 useAuthStore()，无循环依赖时序问题。
   */
  const hasCredential = computed(() => hasApiKey.value || useAuthStore().isAuthenticated)

  /**
   * 凭证模式（Casdoor-aware，#2）。语义随认证模式而异：
   * - **legacy（apikey）**：api-key 显式覆盖会话 Bearer（client.ts 注入 api-key 优先），故 hasApiKey→'api-key'。
   * - **oidc/dual**：edge 的 Casdoor filter(-120) 先跑，**有效 Casdoor Bearer 优先换发、api-key 被忽略/剥离**，
   *   故 Casdoor 已登录时有效凭证是 Bearer（与 legacy 相反）；未登录时 dual 才回退 api-key。
   * getter 内延迟 useAuthStore()，避免循环依赖时序。
   */
  const credentialMode = computed<CredentialMode>(() => {
    const authed = useAuthStore().isAuthenticated
    if (OIDC_ENABLED && authed) return 'bearer' // Casdoor 已登录：edge 里 Casdoor 优先，api-key 不覆盖
    if (hasApiKey.value) return 'api-key'
    return authed ? 'bearer' : 'none'
  })

  /**
   * 两种凭证同时存在且 **api-key 会盖过 Bearer**（顶栏据此高对比警告）。
   * 仅 legacy 语义成立；oidc/dual 下 edge Casdoor 优先，api-key 不覆盖 → 恒 false，不误报警告。
   */
  const apiKeyOverridesBearer = computed(
    () => !OIDC_ENABLED && hasApiKey.value && useAuthStore().isAuthenticated,
  )

  /**
   * 统一 permission 上下文（供 gate/usePermission，避免通用 runner 与专用页面裁决漂移）。
   * effectiveScopes 仅在 Bearer 模式给出；api-key 模式为空（权限不透明，反应式鉴权，可能 403）。
   */
  function permissionContext(): {
    hasApiKey: boolean
    credentialMode: CredentialMode
    effectiveScopes: string[]
  } {
    const auth = useAuthStore()
    const mode = credentialMode.value
    return {
      hasApiKey: hasCredential.value, // 沿用 gate.GateContext 语义：是否有任一可执行凭证
      credentialMode: mode,
      effectiveScopes: mode === 'bearer' ? auth.user?.scopes ?? [] : [],
    }
  }

  function setApiKey(key: string): void {
    apiKey.value = key.trim()
  }

  function clearApiKey(): void {
    apiKey.value = ''
  }

  /** 构造调用上下文（把凭证传给 client/sse 的唯一注入路径）：api-key 显式覆盖优先，否则登录会话 Bearer。 */
  function runContext(signal?: AbortSignal): RunContext {
    return {
      apiKey: apiKey.value.trim(),
      accessToken: useAuthStore().accessToken,
      edgeBaseUrl: edgeBaseUrl.value,
      signal,
    }
  }

  return {
    apiKey,
    edgeBaseUrl,
    hasApiKey,
    hasCredential,
    credentialMode,
    apiKeyOverridesBearer,
    maskedApiKey,
    permissionContext,
    setApiKey,
    clearApiKey,
    runContext,
  }
})
