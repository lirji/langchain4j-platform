<script setup lang="ts">
/**
 * 破坏性操作二次确认对话框（居中模态）。
 *
 * 用于删除 / 禁用自己 / 降权 / 共享写等高危操作。requireText 非空时，需在输入框键入匹配文本才可确认
 * （对最高危操作抬门槛，防误删）。焦点陷阱 + Esc = 取消；「取消」为初始焦点（不落在危险按钮上）。
 *
 * mount-always 模式：父组件始终渲染本组件，仅切换 open。useFocusTrap 监听 open false→true 才装配，
 * 故父组件**不要** v-if 整个组件（否则焦点陷阱不生效）。
 */
import { ref, watch } from 'vue'
import { useFocusTrap } from '../../composables/useFocusTrap'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    message: string
    confirmLabel?: string
    /** 非空时需键入匹配文本才允许确认（最高危操作）。 */
    requireText?: string
  }>(),
  { confirmLabel: '确认', requireText: '' },
)
const emit = defineEmits<{ confirm: []; cancel: [] }>()

const panelEl = ref<HTMLElement | null>(null)
const cancelBtn = ref<HTMLButtonElement | null>(null)
const typed = ref('')

/** requireText 为空 → 直接可确认；非空 → 需精确匹配。 */
const canConfirm = () => props.requireText.length === 0 || typed.value === props.requireText

// 每次打开重置键入内容，避免残留上次输入。
watch(
  () => props.open,
  (o) => {
    if (o) typed.value = ''
  },
)

useFocusTrap({
  active: () => props.open,
  container: panelEl,
  onEscape: () => emit('cancel'),
  initialFocus: () => cancelBtn.value,
})

function onConfirm(): void {
  if (canConfirm()) emit('confirm')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="modal">
        <div class="modal__scrim" aria-hidden="true" @click="emit('cancel')" />
        <div
          ref="panelEl"
          class="modal__panel"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
        >
          <header class="modal__head">
            <h2 class="modal__title"><span aria-hidden="true">⚠ </span>{{ title }}</h2>
            <button
              type="button"
              class="modal__close"
              aria-label="关闭"
              @click="emit('cancel')"
            >
              ✕
            </button>
          </header>

          <div class="modal__body">
            <p class="modal__msg">{{ message }}</p>
            <div v-if="requireText" class="modal__confirm-input">
              <label class="modal__label" :for="`dc-${requireText}`">
                如需继续，请输入 <code>{{ requireText }}</code> 确认：
              </label>
              <input
                :id="`dc-${requireText}`"
                v-model="typed"
                class="form-control form-control--mono"
                type="text"
                autocomplete="off"
                spellcheck="false"
              />
            </div>
          </div>

          <footer class="modal__foot">
            <button ref="cancelBtn" type="button" class="btn" @click="emit('cancel')">取消</button>
            <button
              type="button"
              class="btn btn--danger"
              :disabled="!canConfirm()"
              @click="onConfirm"
            >
              {{ confirmLabel }}
            </button>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal, 70);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-4);
}
.modal__scrim {
  position: absolute;
  inset: 0;
  background: rgba(2, 6, 23, 0.5);
}
.modal__panel {
  position: relative;
  width: 100%;
  max-width: 440px;
  display: flex;
  flex-direction: column;
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  border: 1px solid var(--danger-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
}
.modal__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--glass-border);
}
.modal__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-bold);
  color: var(--danger);
}
.modal__close {
  width: var(--control-h-sm);
  height: var(--control-h-sm);
  color: var(--text-muted);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.modal__close:hover {
  color: var(--text);
  background: var(--surface-2);
}
.modal__body {
  padding: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.modal__msg {
  margin: 0;
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
  color: var(--text);
  white-space: pre-line;
}
.modal__confirm-input {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.modal__label {
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.modal__label code {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
}
.modal__foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border-top: 1px solid var(--glass-border);
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity var(--dur) var(--ease);
}
.modal-enter-active .modal__panel,
.modal-leave-active .modal__panel {
  transition: transform var(--dur) var(--ease-out);
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
.modal-enter-from .modal__panel,
.modal-leave-to .modal__panel {
  transform: translateY(8px) scale(0.98);
}
@media (prefers-reduced-motion: reduce) {
  .modal-enter-active .modal__panel,
  .modal-leave-active .modal__panel {
    transition: none;
  }
}
</style>
