<script setup lang="ts">
import { RouterLink, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import ApiKeyInput from './ApiKeyInput.vue'

const auth = useAuthStore()
const router = useRouter()

async function doLogout(): Promise<void> {
  await auth.logout()
  void router.push({ name: 'login' })
}
</script>

<template>
  <div class="authctl">
    <template v-if="auth.isAuthenticated">
      <span class="authctl__user" :title="`租户 ${auth.user?.tenant ?? ''}`">
        <span aria-hidden="true">👤</span>
        <span class="authctl__name">{{ auth.username }}</span>
      </span>

      <details class="authctl__adv">
        <summary class="authctl__advbtn" title="高级：直连 API Key（可选覆盖登录会话）">高级</summary>
        <div class="authctl__panel">
          <p class="authctl__hint">可选：填写 X-Api-Key 以指定凭证直连（存在时覆盖登录会话）。</p>
          <ApiKeyInput />
        </div>
      </details>

      <button type="button" class="authctl__btn" @click="doLogout">登出</button>
    </template>

    <template v-else>
      <RouterLink class="authctl__btn authctl__btn--primary" :to="{ name: 'login' }">登录</RouterLink>
      <details class="authctl__adv">
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
  display: flex;
  align-items: center;
  gap: 6px;
}
.authctl__user {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  font-size: var(--fs-sm);
  color: var(--success);
  background: var(--success-soft);
  border: 1px solid var(--success-border);
  border-radius: var(--radius);
}
.authctl__name {
  font-weight: var(--fw-medium);
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
</style>
