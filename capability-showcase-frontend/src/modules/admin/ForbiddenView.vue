<script setup lang="ts">
/**
 * 无权访问落点（深链 /forbidden，或 AdminLayout 内联兜底）。
 * 诚实呈现：说明缺 role-admin 或会话已变化，给出明确出路（回总览 / 重新登录），不空白、不 404、不回环。
 */
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { useSessionStore } from '../../stores/session'
import EmptyState from '../../components/common/EmptyState.vue'

const auth = useAuthStore()
const session = useSessionStore()
const router = useRouter()

const CREDENTIAL_LABEL: Record<string, string> = {
  bearer: '🅑 Bearer（账号会话）',
  'api-key': '🔑 API Key',
  none: '— 未登录',
}

async function relogin(): Promise<void> {
  await auth.logout()
  void router.replace({ name: 'login' })
}
function goHome(): void {
  void router.replace({ path: '/' })
}
</script>

<template>
  <div class="page forbidden">
    <EmptyState
      variant="error"
      icon="⛔"
      title="无权访问该页面"
      description="该页面需要平台管理员（role-admin）权限。你的账号当前不具备，或会话权限已发生变化。"
    />
    <ul class="forbidden__diag" aria-label="当前身份诊断">
      <li>当前身份：{{ auth.username || '（未登录）' }}<template v-if="auth.user?.tenant"> · 租户 {{ auth.user.tenant }}</template></li>
      <li>凭证模式：{{ CREDENTIAL_LABEL[session.credentialMode] }}</li>
      <li v-if="session.apiKeyOverridesBearer" class="forbidden__hint">
        管理中心只接受账号会话，请清除 API Key 或用管理员账号登录后再试。
      </li>
    </ul>
    <div class="forbidden__actions">
      <button type="button" class="btn btn--primary" @click="goHome">返回总览</button>
      <button type="button" class="btn" @click="relogin">重新登录</button>
    </div>
  </div>
</template>

<style scoped>
.forbidden {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-4);
  text-align: center;
}
.forbidden__diag {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.forbidden__hint {
  color: var(--warning);
  max-width: 46ch;
}
.forbidden__actions {
  display: flex;
  gap: var(--space-3);
}
</style>
