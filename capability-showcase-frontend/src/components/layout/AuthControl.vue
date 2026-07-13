<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { useSessionStore } from '../../stores/session'
import ApiKeyInput from './ApiKeyInput.vue'

const auth = useAuthStore()
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

/**
 * 管理域路由（meta.requiredScopes 非空）时隐藏 api-key 入口——避免"填了 Key 就能做平台级操作"的
 * 身份混淆（管理中心恒用账号会话 Bearer，见 03 §1.3）。
 */
const onAdminRoute = computed(() => {
  const rs = route.meta?.requiredScopes
  return Array.isArray(rs) && rs.length > 0
})

/** 凭证模式徽章：Bearer（账号会话）/ API Key（直连覆盖）/ 未登录；颜色不单独表意，附图标+文字。 */
const credBadge = computed(() => {
  switch (session.credentialMode) {
    case 'bearer':
      return { icon: '🅑', label: 'Bearer', tone: 'bearer' }
    case 'api-key':
      return { icon: '🔑', label: 'API Key', tone: 'apikey' }
    default:
      return { icon: '—', label: '未登录', tone: 'none' }
  }
})

async function doLogout(): Promise<void> {
  await auth.logout()
  void router.push({ name: 'login' })
}
</script>

<template>
  <div class="authctl">
    <template v-if="auth.isAuthenticated">
      <!-- 身份 chip 三段：用户名 · 租户 · 凭证模式 -->
      <span class="authctl__user" :title="`租户 ${auth.user?.tenant ?? ''}`">
        <span aria-hidden="true">👤</span>
        <span class="authctl__name">{{ auth.username }}</span>
        <template v-if="auth.user?.tenant">
          <span class="authctl__sep" aria-hidden="true">·</span>
          <span class="authctl__tenant">{{ auth.user.tenant }}</span>
        </template>
        <span
          class="authctl__cred"
          :data-tone="credBadge.tone"
          :title="`凭证模式：${credBadge.label}`"
        >
          <span aria-hidden="true">{{ credBadge.icon }}</span>{{ credBadge.label }}
        </span>
      </span>

      <details v-if="!onAdminRoute" class="authctl__adv">
        <summary class="authctl__advbtn" title="高级：直连 API Key（可选覆盖登录会话）">高级</summary>
        <div class="authctl__panel">
          <p class="authctl__hint">可选：填写 X-Api-Key 以指定凭证直连（存在时覆盖登录会话）。</p>
          <ApiKeyInput />
        </div>
      </details>

      <button type="button" class="authctl__btn" @click="doLogout">登出</button>

      <!-- API Key 覆盖登录会话：高对比警告 + 一键清除（管理中心仍只用账号会话）。 -->
      <div
        v-if="session.apiKeyOverridesBearer"
        class="authctl__warn"
        role="status"
        aria-live="polite"
      >
        <span class="authctl__warn-text">
          <span aria-hidden="true">⚠</span>
          能力请求将用 API Key，账号权限预判暂停（以服务端结果为准）。管理中心仍只用账号会话。
        </span>
        <button type="button" class="authctl__warn-clear" @click="session.clearApiKey()">
          清除 Key
        </button>
      </div>
    </template>

    <template v-else>
      <RouterLink class="authctl__btn authctl__btn--primary" :to="{ name: 'login' }">登录</RouterLink>
      <details v-if="!onAdminRoute" class="authctl__adv">
        <summary class="authctl__advbtn" title="高级：直连 API Key">API Key</summary>
        <div class="authctl__panel">
          <p class="authctl__hint">未登录也可用 X-Api-Key 直连（老流程）。</p>
          <ApiKeyInput />
        </div>
      </details>
    </template>
  </div>
</template>

<style scoped>
.authctl {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
}
.authctl__user {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  font-size: var(--fs-sm);
  color: var(--text);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.authctl__name {
  font-weight: var(--fw-medium);
}
.authctl__sep {
  color: var(--text-subtle);
}
.authctl__tenant {
  color: var(--text-muted);
}
/* 凭证模式子徽章：随模式着色（图标+文字双标，AA） */
.authctl__cred {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: 2px;
  padding: 1px 7px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  border-radius: var(--radius-pill);
}
.authctl__cred[data-tone='bearer'] {
  color: var(--success);
  background: var(--success-soft);
  border: 1px solid var(--success-border);
}
.authctl__cred[data-tone='apikey'] {
  color: var(--warning);
  background: var(--warning-soft);
  border: 1px solid var(--warning-border);
}
.authctl__cred[data-tone='none'] {
  color: var(--neutral);
  background: var(--neutral-soft);
  border: 1px solid var(--neutral-border);
}
.authctl__btn {
  padding: 6px 10px;
  font-size: var(--fs-sm);
  color: var(--text-muted);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  cursor: pointer;
  text-decoration: none;
}
.authctl__btn:hover {
  color: var(--text);
  background: var(--surface-3);
}
.authctl__btn--primary {
  color: var(--primary-fg);
  background: var(--primary);
  border-color: var(--primary);
}
.authctl__adv {
  position: relative;
}
.authctl__advbtn {
  list-style: none;
  padding: 6px 10px;
  font-size: var(--fs-sm);
  color: var(--text-muted);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  cursor: pointer;
  user-select: none;
}
.authctl__advbtn::-webkit-details-marker {
  display: none;
}
.authctl__panel {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  z-index: var(--z-popover);
  width: 320px;
  max-width: 80vw;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3);
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
}
.authctl__hint {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
/* API Key 覆盖警告条：高对比 warning，落于身份区下方（popover 层，不挤压顶栏布局） */
.authctl__warn {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  z-index: var(--z-popover);
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 360px;
  max-width: 86vw;
  padding: var(--space-2) var(--space-3);
  font-size: var(--fs-xs);
  line-height: 1.5;
  color: var(--warning);
  background: var(--warning-soft);
  border: 1px solid var(--warning-border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
}
.authctl__warn-text {
  flex: 1;
  min-width: 0;
}
.authctl__warn-clear {
  flex-shrink: 0;
  padding: 4px 10px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  color: var(--danger);
  background: var(--surface);
  border: 1px solid var(--danger-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
}
.authctl__warn-clear:hover {
  background: var(--danger-soft);
}
</style>
