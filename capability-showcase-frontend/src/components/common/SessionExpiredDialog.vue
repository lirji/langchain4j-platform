<script setup lang="ts">
/**
 * 会话过期模态（DR-1）：authorizedFetch 续期失败时由 ui.openAuthModal() 打开。
 * 引导重新登录且**不丢当前 deep-link**（returnTo 存于 ui.authReturnTo，重登后还原）。
 * 复用 useFocusTrap + ShortcutsDialog 同款 scrim/panel 语汇；与其它全局浮层互斥。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUiStore } from '../../stores/ui'
import { useAuthStore } from '../../stores/auth'
import { useFocusTrap } from '../../composables/useFocusTrap'
import { OIDC_ENABLED } from '../../config'

const ui = useUiStore()
const auth = useAuthStore()
const router = useRouter()
const panelEl = ref<HTMLElement | null>(null)
const reloginBtn = ref<HTMLButtonElement | null>(null)

useFocusTrap({
  active: () => ui.authModalOpen,
  container: panelEl,
  onEscape: () => ui.closeAuthModal(),
  initialFocus: () => reloginBtn.value,
})

/** 重新登录：oidc/dual 跳 Casdoor（returnTo 经 state 往返）；legacy 跳登录页带 redirect。整页跳转，不回此处。 */
async function relogin(): Promise<void> {
  const returnTo = ui.authReturnTo
  ui.closeAuthModal()
  if (OIDC_ENABLED) {
    await auth.startOidcLogin(returnTo)
  } else {
    await router.replace({ name: 'login', query: { redirect: returnTo } })
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="ui.authModalOpen" class="sed" role="presentation">
      <div class="sed__scrim" aria-hidden="true" @click="ui.closeAuthModal()" />
      <div
        ref="panelEl"
        class="sed__panel"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="sed-title"
        aria-describedby="sed-desc"
      >
        <h2 id="sed-title" class="sed__title"><span aria-hidden="true">🔒</span> 登录已过期</h2>
        <p id="sed-desc" class="sed__desc">
          你的登录会话已过期或续期失败。请重新登录以继续——当前页面会在登录后自动还原。
        </p>
        <div class="sed__actions">
          <button type="button" class="sed__btn" @click="ui.closeAuthModal()">稍后</button>
          <button ref="reloginBtn" type="button" class="sed__btn sed__btn--primary" @click="relogin">
            重新登录
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.sed {
  position: fixed;
  inset: 0;
  z-index: var(--z-cmdk);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-4);
}
.sed__scrim {
  position: absolute;
  inset: 0;
  background: rgba(2, 6, 23, 0.5);
}
.sed__panel {
  position: relative;
  width: 100%;
  max-width: 400px;
  padding: var(--space-4);
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  animation: sed-in var(--dur-base) var(--ease-out);
}
@keyframes sed-in {
  from {
    opacity: 0;
    transform: translateY(-8px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}
.sed__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-bold);
}
.sed__desc {
  margin-top: var(--space-2);
  color: var(--text-muted);
  font-size: var(--fs-sm);
  line-height: 1.6;
}
.sed__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-4);
}
.sed__btn {
  padding: var(--row-py) var(--space-3);
  font-size: var(--fs-sm);
  color: var(--text-muted);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  cursor: pointer;
}
.sed__btn:hover {
  color: var(--text);
  background: var(--surface-3);
}
.sed__btn--primary {
  color: var(--primary-fg);
  background: var(--primary);
  border-color: var(--primary);
}
.sed__btn--primary:hover {
  background: var(--primary);
  filter: brightness(1.05);
}
</style>
