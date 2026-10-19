import { computed } from 'vue'
import { useAuthStore } from '../../stores/auth'
import { useSessionStore } from '../../stores/session'

/** 仅作内存请求隔离；不输出、记录或持久化凭证。刷新同身份令牌不清空任务。 */
export function useKnowledgeScope() {
  const auth = useAuthStore()
  const session = useSessionStore()
  return computed(() => JSON.stringify([
    session.edgeBaseUrl, session.apiKey, session.hasCredential, auth.user?.tenant,
    auth.user?.username, [...(auth.user?.scopes ?? [])].sort(),
  ]))
}
