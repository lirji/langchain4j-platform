<script setup lang="ts">
import { computed } from 'vue'
import { useFavoritesStore } from '../../stores/favorites'

/**
 * 收藏星标：切换 favorites。空/实心图形区分（不靠颜色），aria-pressed 表达态。
 * 常置于卡片/详情内；@click 阻止冒泡与默认，避免触发外层链接跳转。
 */
const props = defineProps<{ capId: string }>()
const favorites = useFavoritesStore()

const fav = computed(() => favorites.isFav(props.capId))

function toggle(e: MouseEvent): void {
  e.preventDefault()
  e.stopPropagation()
  favorites.toggle(props.capId)
}
</script>

<template>
  <button
    type="button"
    class="fav"
    :class="{ 'is-fav': fav }"
    :aria-pressed="fav"
    :title="fav ? '取消收藏' : '收藏'"
    :aria-label="fav ? '取消收藏' : '收藏'"
    @click="toggle"
  >
    <span aria-hidden="true">{{ fav ? '★' : '☆' }}</span>
  </button>
</template>

<style scoped>
.fav {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: var(--control-h-sm);
  height: var(--control-h-sm);
  font-size: 15px;
  line-height: 1;
  color: var(--text-subtle);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease),
    transform var(--dur-fast) var(--ease-spring);
}
.fav:hover {
  color: var(--warning);
  background: var(--surface-2);
}
.fav:active {
  transform: scale(0.9);
}
.fav.is-fav {
  color: var(--warning);
}
</style>
