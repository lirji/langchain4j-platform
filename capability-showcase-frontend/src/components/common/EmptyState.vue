<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: 'empty' | 'loading' | 'error'
    title: string
    description?: string
    actionLabel?: string
    icon?: string
  }>(),
  { variant: 'empty' },
)
defineEmits<{ action: [] }>()
</script>

<template>
  <div class="empty" :class="`empty--${variant}`" role="status" aria-live="polite">
    <div v-if="variant === 'loading'" class="empty__spinner" aria-hidden="true" />
    <div v-else class="empty__icon" aria-hidden="true">
      {{ icon ?? (variant === 'error' ? '⚠' : '◍') }}
    </div>
    <p class="empty__title">{{ title }}</p>
    <p v-if="description" class="empty__desc">{{ description }}</p>
    <button v-if="actionLabel" type="button" class="empty__action" @click="$emit('action')">
      {{ actionLabel }}
    </button>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-8) var(--space-4);
  text-align: center;
  color: var(--text-muted);
  min-height: 200px;
}
.empty__icon {
  font-size: 32px;
  opacity: 0.6;
}
/* 空态图标：轻柔浮动 */
.empty--empty .empty__icon {
  animation: float 3.2s var(--ease-in-out) infinite;
}
.empty--error .empty__icon {
  color: var(--danger);
  opacity: 1;
  text-shadow: 0 0 12px var(--danger);
}
@keyframes float {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-6px);
  }
}
.empty__title {
  font-size: var(--fs-md);
  font-weight: 600;
  color: var(--text);
}
.empty__desc {
  font-size: var(--fs-sm);
  max-width: 46ch;
}
.empty__action {
  margin-top: var(--space-2);
  padding: var(--space-2) var(--space-4);
  border: 1px solid var(--primary-border);
  background: var(--primary-soft);
  color: var(--primary);
  border-radius: var(--radius);
  font-weight: 600;
}
.empty__action:hover {
  background: var(--primary);
  color: var(--primary-fg);
}
/* 渐变环 spinner：conic-gradient + mask 挖空 + 柔光 */
.empty__spinner {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: conic-gradient(from 90deg, transparent 0%, var(--primary) 100%);
  -webkit-mask: radial-gradient(farthest-side, transparent calc(100% - 3px), #000 calc(100% - 3px));
  mask: radial-gradient(farthest-side, transparent calc(100% - 3px), #000 calc(100% - 3px));
  filter: drop-shadow(0 0 6px var(--primary));
  animation: spin 0.8s linear infinite;
}
</style>
