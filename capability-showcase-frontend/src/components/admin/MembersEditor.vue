<script setup lang="ts">
/**
 * 组成员编辑器：当前成员 chips（可逐个移除）+ 按用户名输入新增。受控 v-model:string[]。
 *
 * 纯前端集合编辑，不做用户存在性校验（提交由 GroupEditor 经独立的成员 PUT 完成，服务端最终裁定）。
 * 去重 + 去空白；回车或「添加」按钮加入。绝不静默丢弃已有成员。
 */
import { computed, ref } from 'vue'

const props = defineProps<{ modelValue: string[] }>()
const emit = defineEmits<{ 'update:modelValue': [string[]] }>()

const input = ref('')

const members = computed(() => props.modelValue)

function add(): void {
  const name = input.value.trim()
  if (!name) return
  if (props.modelValue.includes(name)) {
    input.value = '' // 已在成员中：清空输入即可，不重复添加
    return
  }
  emit('update:modelValue', [...props.modelValue, name])
  input.value = ''
}
function remove(name: string): void {
  emit('update:modelValue', props.modelValue.filter((m) => m !== name))
}
</script>

<template>
  <div class="me">
    <div class="me__chips" role="list" aria-label="当前组成员">
      <p v-if="!members.length" class="me__empty">（暂无成员）</p>
      <span v-for="m in members" :key="m" class="me__chip" role="listitem">
        <span class="me__chip-name">{{ m }}</span>
        <button
          type="button"
          class="me__chip-x"
          :aria-label="`移除成员 ${m}`"
          @click="remove(m)"
        >
          ✕
        </button>
      </span>
    </div>

    <div class="me__add">
      <input
        v-model="input"
        class="form-control me__input"
        type="text"
        placeholder="输入用户名后添加"
        autocomplete="off"
        spellcheck="false"
        aria-label="要添加的成员用户名"
        @keydown.enter.prevent="add"
      />
      <button type="button" class="btn btn--sm" :disabled="!input.trim()" @click="add">＋ 添加</button>
    </div>
  </div>
</template>

<style scoped>
.me {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.me__chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  min-height: var(--control-h);
}
.me__empty {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--text-subtle);
}
.me__chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 4px 2px 10px;
  font-size: var(--fs-sm);
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  border-radius: var(--radius-pill);
}
.me__chip-name {
  font-weight: var(--fw-medium);
}
.me__chip-x {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  font-size: var(--fs-xs);
  color: var(--text-muted);
  background: transparent;
  border: none;
  border-radius: 50%;
  cursor: pointer;
}
.me__chip-x:hover {
  color: var(--danger);
  background: var(--surface-2);
}
.me__add {
  display: flex;
  gap: var(--space-2);
}
.me__input {
  flex: 1;
  min-width: 180px;
}
</style>
