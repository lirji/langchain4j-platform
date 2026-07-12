<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import JsonView from './JsonView.vue'
import CopyButton from '../common/CopyButton.vue'
import EmptyState from '../common/EmptyState.vue'
import { prettyJson } from '../../utils/json'
import { countMatches, highlightSegments } from '../../utils/highlight'
import { downloadText } from '../../utils/download'
import type { RunPhase } from '../../composables/useCapabilityRun'

const props = defineProps<{
  phase: RunPhase
  data?: unknown
  text?: string
  httpStatus?: number | null
  traceId?: string | null
  elapsedMs?: number
  error?: string | null
}>()

const viewMode = ref<'tree' | 'raw'>('tree')
const query = ref('')
const wrap = ref(true)

const hasData = computed(() => props.data !== undefined && props.data !== null)
const isTextOnly = computed(() => !hasData.value && typeof props.text === 'string' && props.text !== '')
const hasBody = computed(() => hasData.value || isTextOnly.value)

const statusTone = computed(() => {
  const s = props.httpStatus ?? 0
  if (s >= 200 && s < 300) return 'ok'
  if (s >= 400 && s < 500) return 'warn'
  if (s >= 500) return 'danger'
  return 'neutral'
})

const rawText = computed(() =>
  hasData.value ? prettyJson(props.data) : (props.text ?? ''),
)

const matchCount = computed(() => countMatches(rawText.value, query.value))
const segments = computed(() => highlightSegments(rawText.value, query.value))

// 搜索时切到原始视图，命中高亮才可见（树视图无法逐字符高亮）。
watch(query, (q) => {
  if (q.trim() && hasData.value) viewMode.value = 'raw'
})

function download(): void {
  const ext = hasData.value ? 'json' : 'txt'
  const mime = hasData.value ? 'application/json' : 'text/plain'
  downloadText(`response-${props.httpStatus ?? 'na'}.${ext}`, rawText.value, mime)
}
</script>

<template>
  <div class="rv">
    <div class="rv__bar">
      <span
        v-if="httpStatus != null"
        class="rv__status"
        :data-tone="statusTone"
      >
        HTTP {{ httpStatus }}
      </span>
      <span v-if="elapsedMs" class="rv__meta">{{ elapsedMs }} ms</span>
      <span v-if="traceId" class="rv__trace" :title="`X-Trace-Id: ${traceId}`">
        trace {{ traceId.slice(0, 8) }}…
      </span>
      <span class="rv__spacer" />

      <label v-if="hasBody" class="rv__search">
        <input
          v-model="query"
          type="search"
          class="rv__search-input"
          placeholder="搜索响应…"
          aria-label="搜索响应体"
        />
        <span v-if="query.trim()" class="rv__search-count" aria-live="polite">{{ matchCount }}</span>
      </label>

      <button
        v-if="hasBody"
        type="button"
        class="rv__act"
        :class="{ 'is-on': !wrap }"
        :aria-pressed="!wrap"
        title="切换自动换行"
        @click="wrap = !wrap"
      >
        换行
      </button>

      <div v-if="hasBody" class="rv__tabs" role="tablist">
        <button
          type="button"
          role="tab"
          :aria-selected="viewMode === 'tree'"
          class="rv__tab"
          :class="{ active: viewMode === 'tree' }"
          @click="viewMode = 'tree'"
        >
          树
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="viewMode === 'raw'"
          class="rv__tab"
          :class="{ active: viewMode === 'raw' }"
          @click="viewMode = 'raw'"
        >
          原始
        </button>
      </div>

      <button v-if="rawText" type="button" class="rv__act" title="下载响应体" @click="download">
        下载
      </button>
      <CopyButton v-if="rawText" :text="rawText" compact />
    </div>

    <div class="rv__body">
      <div v-if="error" class="rv__error" role="alert">{{ error }}</div>

      <template v-if="hasBody">
        <div v-if="viewMode === 'tree' && hasData" class="rv__json">
          <JsonView :data="data" />
        </div>
        <pre v-else class="rv__raw" :class="{ 'rv__raw--nowrap': !wrap }"><template
            v-if="query.trim()"
          ><span
            v-for="(s, i) in segments"
            :key="i"
            :class="{ 'rv__hit': s.hit }"
          >{{ s.text }}</span></template><template v-else>{{ rawText }}</template></pre>
      </template>

      <EmptyState
        v-else-if="!error && phase === 'idle'"
        variant="empty"
        title="尚未执行"
        description="填写左侧参数后点击执行，响应将在此显示。"
      />
      <EmptyState
        v-else-if="!error && phase === 'running'"
        variant="loading"
        title="请求中…"
      />
      <EmptyState
        v-else-if="!error && (phase === 'success' || phase === 'done')"
        variant="empty"
        icon="∅"
        title="空响应"
        description="请求成功，但响应体为空。"
      />
    </div>
  </div>
</template>

<style scoped>
.rv {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}
.rv__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
  flex-wrap: wrap;
}
.rv__status {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: 700;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
}
.rv__status[data-tone='ok'] {
  color: var(--success);
  background: var(--success-soft);
  border-color: var(--success-border);
}
.rv__status[data-tone='warn'] {
  color: var(--warning);
  background: var(--warning-soft);
  border-color: var(--warning-border);
}
.rv__status[data-tone='danger'] {
  color: var(--danger);
  background: var(--danger-soft);
  border-color: var(--danger-border);
}
.rv__meta,
.rv__trace {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  font-family: var(--font-mono);
}
.rv__spacer {
  flex: 1;
}
/* 响应操作条按钮：与 curl/复制统一的小控件规格。 */
.rv__act {
  display: inline-flex;
  align-items: center;
  height: var(--control-h-sm);
  padding: 0 var(--space-2);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}
.rv__act:hover {
  color: var(--text);
  background: var(--surface-2);
}
.rv__act:active,
.rv__act.is-on {
  color: var(--primary);
  background: var(--primary-soft);
  border-color: var(--primary-border);
}
.rv__search {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.rv__search-input {
  width: 118px;
  height: var(--control-h-sm);
  padding: 0 var(--space-2);
  font-size: var(--fs-xs);
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.rv__search-input:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-border);
}
.rv__search-count {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-subtle);
  min-width: 2ch;
  text-align: right;
}
.rv__tabs {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.rv__tab {
  padding: 2px 10px;
  font-size: var(--fs-xs);
  color: var(--text-muted);
  background: var(--surface);
  border: none;
}
.rv__tab.active {
  color: var(--primary);
  background: var(--primary-soft);
}
.rv__body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: var(--space-3);
}
.rv__error {
  padding: var(--space-3);
  margin-bottom: var(--space-3);
  color: var(--danger);
  background: var(--danger-soft);
  border: 1px solid var(--danger-border);
  border-radius: var(--radius);
  font-size: var(--fs-sm);
  line-height: 1.5;
}
.rv__raw {
  margin: 0;
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text);
}
.rv__raw--nowrap {
  white-space: pre;
  word-break: normal;
  overflow-x: auto;
}
.rv__hit {
  border-radius: 3px;
  padding: 0 1px;
  color: var(--text);
  background: var(--warning-soft);
  box-shadow: 0 0 0 1px var(--warning-border);
}
</style>
