<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { useSessionStore } from '../../stores/session'
import { useCatalogStore } from '../../stores/catalog'

const session = useSessionStore()
const catalog = useCatalogStore()

const editing = ref(false)
const draft = ref('')
const inputEl = ref<HTMLInputElement | null>(null)

async function startEdit(): Promise<void> {
  editing.value = true
  draft.value = ''
  await nextTick()
  inputEl.value?.focus()
}

function save(): void {
  session.setApiKey(draft.value)
  draft.value = ''
  editing.value = false
  // 填 Key 后触发一次 live discovery 合并（best-effort）。
  void catalog.refreshLive()
}

function cancel(): void {
  draft.value = ''
  editing.value = false
}

function clearKey(): void {
  session.clearApiKey()
}
</script>

<template>
  <div class="apikey">
    <template v-if="editing">
      <input
        ref="inputEl"
        v-model="draft"
        type="password"
        class="apikey__input"
        autocomplete="off"
        spellcheck="false"
        placeholder="粘贴 X-Api-Key（仅存于内存）"
        aria-label="API Key 输入（仅存于内存，不落盘）"
        @keydown.enter.prevent="save"
        @keydown.esc.prevent="cancel"
      />
      <button type="button" class="apikey__btn apikey__btn--primary" @click="save">保存</button>
      <button type="button" class="apikey__btn" @click="cancel">取消</button>
    </template>

    <template v-else>
      <button
        type="button"
        class="apikey__chip"
        :data-set="session.hasApiKey"
        :title="session.hasApiKey ? '已设置 API Key（仅内存，尾 4 位可见）' : '未设置 API Key'"
        @click="startEdit"
      >
        <span class="apikey__lock" aria-hidden="true">🔑</span>
        <span v-if="session.hasApiKey" class="apikey__masked">{{ session.maskedApiKey }}</span>
        <span v-else>设置 API Key</span>
      </button>
      <button
        v-if="session.hasApiKey"
        type="button"
        class="apikey__btn"
        title="清除 API Key"
        @click="clearKey"
      >
        清除
      </button>
    </template>
  </div>
</template>

<style scoped>
.apikey {
  display: flex;
  align-items: center;
  gap: 6px;
}
.apikey__input {
  width: 220px;
  max-width: 44vw;
  padding: 6px 10px;
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius);
}
.apikey__chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  font-size: var(--fs-sm);
  color: var(--text-muted);
  background: var(--surface-2);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius);
}
.apikey__chip[data-set='true'] {
  color: var(--success);
  background: var(--success-soft);
  border-style: solid;
  border-color: var(--success-border);
}
.apikey__masked {
  font-family: var(--font-mono);
  letter-spacing: 0.06em;
}
.apikey__btn {
  padding: 6px 10px;
  font-size: var(--fs-sm);
  color: var(--text-muted);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.apikey__btn:hover {
  color: var(--text);
  background: var(--surface-3);
}
.apikey__btn--primary {
  color: var(--primary-fg);
  background: var(--primary);
  border-color: var(--primary);
}
.apikey__btn--primary:hover {
  background: var(--primary-hover);
  color: var(--primary-fg);
}
</style>
