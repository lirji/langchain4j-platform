<script setup lang="ts">
/** 侧栏搜索零结果空态：给出查询回显 + 清除入口。 */
defineProps<{ query: string }>()
defineEmits<{ (e: 'clear'): void }>()
</script>

<template>
  <div class="empty" role="status">
    <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <circle cx="11" cy="11" r="7" />
      <path d="M20 20l-3.4-3.4" />
    </svg>
    <p class="empty__title">无匹配能力</p>
    <p class="empty__desc">没有能力或模块匹配「{{ query }}」。</p>
    <button type="button" class="empty__clear" @click="$emit('clear')">清除筛选</button>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: var(--space-6) var(--space-3);
  text-align: center;
  color: var(--text-subtle);
}
.empty svg {
  color: var(--text-subtle);
  opacity: 0.7;
}
.empty__title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
}
.empty__desc {
  font-size: var(--fs-xs);
  max-width: 22ch;
  line-height: var(--lh-base);
}
.empty__clear {
  margin-top: 4px;
  padding: 5px 12px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
}
.empty__clear:hover {
  background: var(--surface-3);
}
</style>
