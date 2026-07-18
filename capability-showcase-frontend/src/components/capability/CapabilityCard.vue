<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { Capability } from '../../types/catalog'
import { isStreamingKind } from '../../api/client'
import MethodBadge from './badges/MethodBadge.vue'
import StateBadge from './badges/StateBadge.vue'
import FavoriteStar from '../common/FavoriteStar.vue'

defineProps<{ cap: Capability; moduleId: string }>()
</script>

<template>
  <RouterLink :to="`/m/${moduleId}/${cap.id}`" class="card">
    <FavoriteStar :cap-id="cap.id" class="card__fav" />
    <div class="card__top">
      <MethodBadge :method="cap.method" />
      <code class="card__path">{{ cap.path }}</code>
    </div>
    <h3 class="card__title">{{ cap.title }}</h3>
    <p class="card__desc">{{ cap.description }}</p>
    <div class="card__foot">
      <StateBadge :state="cap.state" />
      <span v-if="isStreamingKind(cap.requestKind)" class="card__sse">SSE</span>
    </div>
  </RouterLink>
</template>

<style scoped>
.card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: var(--space-4);
  /* 多卡片网格：纯半透明实底，不叠 backdrop-filter（性能护栏） */
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  color: var(--text);
  box-shadow: var(--shadow-sm);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease),
    transform var(--dur) var(--ease-out);
  /* 进入错峰：随 --i 递增延迟；backwards 保证结束后 hover transform 可用 */
  animation: card-in var(--dur-slow) var(--ease-out) backwards;
  animation-delay: calc(var(--i, 0) * 40ms);
}
/* 顶部一线高光 */
.card::after {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 1px;
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
  background: linear-gradient(90deg, transparent, var(--glass-highlight) 50%, transparent);
  pointer-events: none;
}
.card:hover {
  text-decoration: none;
  border-color: var(--primary-border);
  box-shadow: var(--shadow-lg), var(--glow-primary);
  transform: translateY(-3px);
}
@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
/* 收藏星标：hover / 键盘聚焦时显现；已收藏时常驻（不靠颜色单独表意，星形已区分）。 */
.card__fav {
  position: absolute;
  top: var(--space-2);
  right: var(--space-2);
  z-index: 1;
  opacity: 0;
  transition: opacity var(--dur) var(--ease);
}
.card:hover .card__fav,
.card:focus-within .card__fav,
.card__fav.is-fav {
  opacity: 1;
}
/* 触屏无 hover：星标常显，否则未收藏能力在手机上无法收藏 */
@media (hover: none) {
  .card__fav {
    opacity: 1;
  }
}
.card__top {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-right: var(--space-6);
}
.card__path {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card__title {
  font-size: var(--fs-md);
  font-weight: 700;
}
.card__desc {
  font-size: var(--fs-sm);
  color: var(--text-muted);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: auto;
  padding-top: 4px;
}
.card__sse {
  font-size: 11px;
  font-weight: 700;
  color: var(--stream);
  background: linear-gradient(135deg, var(--stream-soft), transparent 86%);
  border: 1px solid var(--stream-border);
  border-radius: var(--radius-sm);
  padding: 1px 6px;
  box-shadow: var(--glow-stream);
}
</style>
