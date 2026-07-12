<script setup lang="ts">
import { computed } from 'vue'
import type { Capability } from '../../types/catalog'
import { isStreamingKind } from '../../api/client'
import MethodBadge from './badges/MethodBadge.vue'
import StateBadge from './badges/StateBadge.vue'
import RiskBadge from './badges/RiskBadge.vue'
import ScopeBadge from './badges/ScopeBadge.vue'
import SourceBadge from './badges/SourceBadge.vue'
import FavoriteStar from '../common/FavoriteStar.vue'

const props = defineProps<{ cap: Capability }>()

const isSse = computed(() => isStreamingKind(props.cap.requestKind))
const isMultipart = computed(
  () => props.cap.requestKind === 'multipart' || props.cap.requestKind === 'multipart-sse',
)
</script>

<template>
  <header class="caphead">
    <div class="caphead__top">
      <MethodBadge :method="cap.method" />
      <code class="caphead__path">{{ cap.path }}</code>
      <span v-if="isMultipart" class="caphead__tag">multipart</span>
      <span v-if="isSse" class="caphead__tag caphead__tag--sse">SSE</span>
      <span class="caphead__spacer" />
      <FavoriteStar :cap-id="cap.id" />
    </div>

    <h1 class="caphead__title">{{ cap.title }}</h1>
    <p class="caphead__desc">{{ cap.description }}</p>

    <div class="caphead__badges">
      <StateBadge :state="cap.state" />
      <RiskBadge :risk="cap.riskLevel" />
      <ScopeBadge :scopes="cap.requiredScopes" />
      <SourceBadge :source="cap.source" />
      <a v-if="cap.docUrl" class="caphead__doc" :href="cap.docUrl" target="_blank" rel="noopener">
        文档 ↗
      </a>
    </div>

    <!-- 诚实呈现的前置提示 -->
    <div v-if="cap.state === 'flag-off'" class="caphead__notice caphead__notice--off">
      该能力未注册：需开启
      <code>{{ cap.featureFlag ?? '（未知 feature flag）' }}=true</code>
      后端才会挂载此端点。当前不可执行，仅可预览请求。
    </div>
    <div v-else-if="cap.state === 'ready-degraded'" class="caphead__notice caphead__notice--warn">
      当前为内存 / 确定性降级实现（非真实语义或生产依赖）。可执行，结果仅供演示。
    </div>
    <div v-else-if="cap.state === 'scope-required'" class="caphead__notice caphead__notice--warn">
      需要
      <strong>{{ cap.requiredScopes.join(' / ') }}</strong>
      scope。若当前 API Key 不具备，将返回 403（届时会翻译为人话提示）。
    </div>
    <div v-else-if="!cap.executableByDefault" class="caphead__notice caphead__notice--danger">
      破坏性能力，默认锁定。仅提供请求预览 / 复制 curl；如确需执行需显式二次确认。
    </div>
  </header>
</template>

<style scoped>
/* 详情页「能力横幅」：单层玻璃卡（不叠 backdrop-filter），与 ModuleHeader 视觉协调。 */
.caphead {
  padding: var(--card-pad);
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
.caphead__top {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: var(--space-2);
}
.caphead__spacer {
  flex: 1;
}
.caphead__path {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  color: var(--text-muted);
  background: var(--surface-2);
  padding: 3px 8px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
}
.caphead__tag {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: var(--radius-sm);
  color: var(--text-subtle);
  background: var(--surface-2);
  border: 1px solid var(--border);
}
.caphead__tag--sse {
  color: var(--stream);
  background: linear-gradient(135deg, var(--stream-soft), transparent 86%);
  border-color: var(--stream-border);
  box-shadow: var(--glow-stream);
}
/* 内容标题一律实色（渐变只留品牌），紧字距。 */
.caphead__title {
  font-size: var(--fs-xl);
  font-weight: var(--fw-bold);
  letter-spacing: var(--ls-tight);
  margin-bottom: 4px;
  color: var(--text);
}
.caphead__desc {
  color: var(--text-muted);
  font-size: var(--fs-sm);
  max-width: 80ch;
  line-height: 1.55;
}
.caphead__badges {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: var(--space-3);
}
.caphead__doc {
  font-size: var(--fs-xs);
  color: var(--primary);
}
.caphead__notice {
  margin-top: var(--space-3);
  padding: var(--space-2) var(--space-3);
  font-size: var(--fs-sm);
  line-height: 1.5;
  border-radius: var(--radius);
  border: 1px solid transparent;
}
.caphead__notice code {
  font-size: var(--fs-xs);
  padding: 1px 5px;
  border-radius: var(--radius-sm);
  background: rgba(127, 127, 127, 0.14);
}
.caphead__notice--off {
  color: var(--neutral);
  background: var(--neutral-soft);
  border-color: var(--neutral-border);
}
.caphead__notice--warn {
  color: var(--warning);
  background: var(--warning-soft);
  border-color: var(--warning-border);
}
.caphead__notice--danger {
  color: var(--danger);
  background: var(--danger-soft);
  border-color: var(--danger-border);
}
</style>
