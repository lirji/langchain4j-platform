<script setup lang="ts">
/**
 * 版本冲突对话框（居中模态）——**仅** 409 `version_conflict` 触发。
 *
 * 逐字段展示「你的草稿」vs「服务端最新」的差异（差异行高亮）。**不提供任何"无脑覆盖"按钮**（业务红线）：
 * 只引导「放弃我的改动，加载最新」→ emit('reload')，或关闭后由用户以最新为基底重做。
 * 焦点陷阱 + Esc = 关闭。
 *
 * mount-always 模式：父组件始终渲染、仅切换 open（useFocusTrap 监听 open false→true 装配）。
 */
import { computed, ref } from 'vue'
import { useFocusTrap } from '../../composables/useFocusTrap'

interface FieldDef {
  key: string
  label: string
}

const props = defineProps<{
  open: boolean
  draft: Record<string, unknown>
  current: Record<string, unknown>
  fields: FieldDef[]
}>()
const emit = defineEmits<{ reload: []; close: [] }>()

const panelEl = ref<HTMLElement | null>(null)
const reloadBtn = ref<HTMLButtonElement | null>(null)

/** 值格式化：数组 → 以「、」连接；对象 → 紧凑 JSON；空 → 「—」。 */
function fmt(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—'
  if (Array.isArray(v)) return v.length ? v.join('、') : '（空）'
  if (typeof v === 'object') {
    try {
      return JSON.stringify(v)
    } catch {
      return String(v)
    }
  }
  return String(v)
}

interface DiffRow extends FieldDef {
  draftText: string
  currentText: string
  changed: boolean
}

const rows = computed<DiffRow[]>(() =>
  props.fields.map((f) => {
    const draftText = fmt(props.draft[f.key])
    const currentText = fmt(props.current[f.key])
    return { ...f, draftText, currentText, changed: draftText !== currentText }
  }),
)
const changedCount = computed(() => rows.value.filter((r) => r.changed).length)

useFocusTrap({
  active: () => props.open,
  container: panelEl,
  onEscape: () => emit('close'),
  initialFocus: () => reloadBtn.value,
})
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="modal">
        <div class="modal__scrim" aria-hidden="true" @click="emit('close')" />
        <div
          ref="panelEl"
          class="modal__panel"
          role="dialog"
          aria-modal="true"
          aria-label="保存冲突：内容已被他人更新"
        >
          <header class="modal__head">
            <h2 class="modal__title"><span aria-hidden="true">⚠ </span>保存冲突：内容已被他人更新</h2>
            <button type="button" class="modal__close" aria-label="关闭" @click="emit('close')">✕</button>
          </header>

          <div class="modal__body">
            <p class="modal__intro">
              你的草稿基于旧版本，服务端已被他人修改。为避免冲掉他人改动，请查看差异后加载最新重做。
            </p>

            <div class="vc-table" role="region" aria-label="草稿与服务端最新的字段差异" tabindex="0">
              <table class="vc-table__table">
                <thead>
                  <tr>
                    <th scope="col">字段</th>
                    <th scope="col">你的草稿</th>
                    <th scope="col">服务端最新</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="r in rows" :key="r.key" :class="{ 'vc-row--diff': r.changed }">
                    <th scope="row" class="vc-cell vc-cell--label">{{ r.label }}</th>
                    <td class="vc-cell">{{ r.draftText }}</td>
                    <td class="vc-cell">
                      {{ r.currentText }}
                      <span v-if="r.changed" class="vc-badge" aria-hidden="true">⚠ 差异</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <p class="modal__summary" role="status" aria-live="polite">
              <template v-if="changedCount">有 {{ changedCount }} 处字段被他人改动（已高亮）。</template>
              <template v-else>字段值一致（可能有其它并发写导致版本变化），加载最新后重试即可。</template>
            </p>
          </div>

          <footer class="modal__foot">
            <button type="button" class="btn" @click="emit('close')">保留草稿，稍后处理</button>
            <button ref="reloadBtn" type="button" class="btn btn--primary" @click="emit('reload')">
              放弃我的改动，加载最新
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
  max-width: 560px;
  display: flex;
  flex-direction: column;
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  border: 1px solid var(--warning-border);
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
  color: var(--warning);
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
.modal__intro {
  margin: 0;
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
  color: var(--text);
}
.modal__summary {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--warning);
}
.vc-table {
  width: 100%;
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.vc-table:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.vc-table__table {
  border-collapse: collapse;
  width: 100%;
  font-size: var(--fs-sm);
}
.vc-table__table th[scope='col'] {
  text-align: left;
  padding: var(--space-2) var(--space-3);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
.vc-cell {
  padding: var(--space-2) var(--space-3);
  color: var(--text);
  border-bottom: 1px solid var(--border);
  vertical-align: top;
}
.vc-cell--label {
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  text-align: left;
  white-space: nowrap;
}
.vc-row--diff .vc-cell {
  background: var(--warning-soft);
}
.vc-badge {
  display: inline-block;
  margin-left: 6px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--warning);
}
.modal__foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border-top: 1px solid var(--glass-border);
  flex-wrap: wrap;
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
