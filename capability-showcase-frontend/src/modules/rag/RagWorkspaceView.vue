<script setup lang="ts">
/**
 * RAG Workspace —— 文档库 + 检索台 双区工作台（module=rag）。
 *
 * 左「文档库」：rag.documents.list 列表（标题/类目/id + 详情/删除）；文档入库（file/json/obsidian，需 ingest scope）。
 * 右「检索台」：rag.query 输入 → 带分数排序的结果卡（score/docId/category/text，高亮命中）。
 *   rag.query 为 ready-degraded：顶部降级横幅（默认 HashEmbedding 内存库，非真实语义）。
 * GraphRAG 子分区（rag.graph.query / entities，flag-off）：诚实锁定。
 *
 * 深链（capId 存在）沿用通用 CapabilityRunner。执行统一经 executionGate + runCapability。
 */
import { computed, ref } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import { useSessionStore } from '../../stores/session'
import { runCapability } from '../../api/client'
import { humanizeError } from '../../api/errors'
import { executionGate } from '../../utils/gate'
import { highlightSegments } from '../../utils/highlight'
import type { Capability } from '../../types/catalog'
import type { FormValues } from '../../utils/validation'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import ResponseViewer from '../../components/capability/ResponseViewer.vue'
import JsonView from '../../components/capability/JsonView.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import ModuleHeader from '../../components/layout/ModuleHeader.vue'
import WorkbenchSection from '../_shared/WorkbenchSection.vue'
import InfoNote from '../_shared/InfoNote.vue'

const props = defineProps<{ moduleId: string; capId?: string }>()
const catalog = useCatalogStore()
const session = useSessionStore()

const module = computed(() => catalog.moduleById(props.moduleId))
const focusedCap = computed(() =>
  props.capId ? (module.value?.capabilities ?? []).find((c) => c.id === props.capId) : undefined,
)

const queryCap = computed(() => catalog.capabilityById('rag.query'))
const listCap = computed(() => catalog.capabilityById('rag.documents.list'))
const getCap = computed(() => catalog.capabilityById('rag.documents.get'))
const deleteCap = computed(() => catalog.capabilityById('rag.documents.delete'))
const uploadFileCap = computed(() => catalog.capabilityById('rag.upload.file'))
const uploadJsonCap = computed(() => catalog.capabilityById('rag.upload.json'))
const obsidianCap = computed(() => catalog.capabilityById('rag.obsidian.import'))
const graphQueryCap = computed(() => catalog.capabilityById('rag.graph.query'))
const graphEntitiesCap = computed(() => catalog.capabilityById('rag.graph.entities'))

const uploadRunners = computed<Capability[]>(() =>
  [uploadFileCap.value, uploadJsonCap.value, obsidianCap.value].filter(
    (c): c is Capability => !!c,
  ),
)
const graphRunners = computed<Capability[]>(() =>
  [graphQueryCap.value, graphEntitiesCap.value].filter((c): c is Capability => !!c),
)

function asStr(v: unknown): string | undefined {
  if (v == null) return undefined
  if (typeof v === 'string') return v.trim() ? v : undefined
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return undefined
}
function numOf(v: unknown): number | undefined {
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (typeof v === 'string' && v.trim() && Number.isFinite(Number(v))) return Number(v)
  return undefined
}
function firstArray(data: unknown, keys: string[]): unknown[] | null {
  if (Array.isArray(data)) return data
  if (data && typeof data === 'object') {
    const o = data as Record<string, unknown>
    for (const k of keys) if (Array.isArray(o[k])) return o[k] as unknown[]
  }
  return null
}

// ── 通用一次性调用（复用 executionGate + runCapability）──
async function callCap(
  cap: Capability | undefined,
  values: FormValues,
): Promise<{ data?: unknown; error?: string }> {
  if (!cap) return { error: '能力不在目录中。' }
  const gate = executionGate(cap, { hasApiKey: session.hasCredential, confirmed: false })
  if (!gate.allowed) return { error: gate.reason ?? '当前不可执行。' }
  try {
    const res = await runCapability(cap, values, session.runContext())
    return { data: res.data }
  } catch (e) {
    return { error: humanizeError(e, cap) }
  }
}

// ── 文档库 ──
interface DocItem {
  docId?: string
  title?: string
  category?: string
}
const docs = ref<DocItem[]>([])
const docsLoaded = ref(false)
const docsBusy = ref(false)
const docsError = ref<string | null>(null)
const docsNote = ref<string | null>(null)
const pendingDelete = ref<string | null>(null)
const detailDocId = ref<string | null>(null)
const detailData = ref<unknown>(null)
const detailBusy = ref(false)
const detailError = ref<string | null>(null)
const busyKey = ref<string | null>(null)

function parseDocs(data: unknown): DocItem[] {
  const arr = firstArray(data, ['documents', 'items', 'data', 'results']) ?? []
  return arr.map((item): DocItem => {
    if (item && typeof item === 'object') {
      const o = item as Record<string, unknown>
      return {
        docId: asStr(o.docId) ?? asStr(o.id) ?? asStr(o.documentId),
        title: asStr(o.title) ?? asStr(o.name) ?? asStr(o.filename),
        category: asStr(o.category),
      }
    }
    return { title: String(item) }
  })
}

async function loadDocs(): Promise<void> {
  docsBusy.value = true
  docsError.value = null
  docsNote.value = null
  const { data, error } = await callCap(listCap.value, {})
  docsBusy.value = false
  docsLoaded.value = true
  if (error) {
    docsError.value = error
    return
  }
  docs.value = parseDocs(data)
  if (!docs.value.length) docsNote.value = '当前租户下暂无文档。上传后可在此管理与检索。'
}

async function viewDetail(docId: string): Promise<void> {
  detailDocId.value = docId
  detailData.value = null
  detailError.value = null
  detailBusy.value = true
  busyKey.value = `get:${docId}`
  const { data, error } = await callCap(getCap.value, { docId })
  detailBusy.value = false
  busyKey.value = null
  if (error) {
    detailError.value = error
    return
  }
  detailData.value = data ?? null
}

async function confirmDelete(docId: string): Promise<void> {
  busyKey.value = `del:${docId}`
  docsError.value = null
  const { error } = await callCap(deleteCap.value, { docId })
  busyKey.value = null
  pendingDelete.value = null
  if (error) {
    docsError.value = error
    return
  }
  if (detailDocId.value === docId) {
    detailDocId.value = null
    detailData.value = null
  }
  docsNote.value = `文档 ${docId} 已删除。`
  await loadDocs()
}

function onUploaded(): void {
  // 入库成功后刷新文档库（若已加载过 / 已填 Key）。
  if (session.hasCredential) void loadDocs()
}

// ── 检索台 ──
interface Hit {
  score?: number
  docId?: string
  category?: string
  text?: string
  raw: unknown
}
const query = ref('')
const topK = ref<number | null>(5)
const minScore = ref<number | null>(null)
const category = ref('')
const searchBusy = ref(false)
const searchError = ref<string | null>(null)
const searchResult = ref<unknown>(null)
const searched = ref(false)

const queryGate = computed(() =>
  queryCap.value
    ? executionGate(queryCap.value, { hasApiKey: session.hasCredential })
    : { allowed: false, reason: '未找到检索能力。' },
)
const canSearch = computed(
  () => queryGate.value.allowed && !searchBusy.value && query.value.trim().length > 0,
)

function parseHits(data: unknown): Hit[] | null {
  const arr = firstArray(data, ['results', 'matches', 'hits', 'documents', 'items', 'data'])
  if (!arr) return null
  return arr
    .map((item): Hit => {
      if (item && typeof item === 'object') {
        const o = item as Record<string, unknown>
        return {
          score: numOf(o.score) ?? numOf(o.relevance) ?? numOf(o.similarity),
          docId: asStr(o.docId) ?? asStr(o.documentId) ?? asStr(o.id),
          category: asStr(o.category),
          text: asStr(o.text) ?? asStr(o.content) ?? asStr(o.snippet) ?? asStr(o.chunk),
          raw: item,
        }
      }
      return { text: String(item), raw: item }
    })
    .sort((a, b) => (b.score ?? -Infinity) - (a.score ?? -Infinity))
}

const hits = computed(() => parseHits(searchResult.value))
const searchFallback = computed(() => searchResult.value != null && !hits.value)

function fmtScore(s?: number): string {
  return s == null ? '—' : s.toFixed(3)
}
function segmentsFor(text: string): { text: string; hit: boolean }[] {
  return highlightSegments(text, query.value)
}

async function runSearch(): Promise<void> {
  if (!canSearch.value) return
  searchBusy.value = true
  searchError.value = null
  searchResult.value = null
  const values: FormValues = { query: query.value.trim() }
  if (topK.value != null) values.topK = topK.value
  if (minScore.value != null) values.minScore = minScore.value
  if (category.value.trim()) values.category = category.value.trim()
  const { data, error } = await callCap(queryCap.value, values)
  searchBusy.value = false
  searched.value = true
  if (error) {
    searchError.value = error
    return
  }
  searchResult.value = data ?? null
}

const ingestScopeHint = computed(() => {
  const scopes = uploadFileCap.value?.requiredScopes ?? []
  return scopes.length ? scopes.join(' / ') : 'ingest'
})
</script>

<template>
  <EmptyState
    v-if="!module"
    variant="error"
    title="模块不存在"
    :description="`未找到模块「${moduleId}」。`"
  />

  <!-- 深链：单能力聚焦 -->
  <CapabilityRunner v-else-if="capId && focusedCap" :key="focusedCap.id" :cap="focusedCap" />

  <EmptyState
    v-else-if="capId && !focusedCap"
    variant="error"
    title="能力不存在"
    :description="`模块「${module.title}」下未找到能力「${capId}」。`"
  />

  <!-- 工作台着陆：文档库（左） + 检索台（右） + 入库 + GraphRAG -->
  <div v-else class="rag">
    <ModuleHeader :module-id="moduleId" />

    <div class="rag__cols">
      <!-- 左：文档库 -->
      <div class="rag__col">
        <WorkbenchSection
          v-if="listCap"
          title="文档库"
          subtitle="列出、查看与删除当前租户文档。删除为 caution 操作，经安全闸门执行。"
        >
          <template #actions>
            <button
              type="button"
              class="btn btn--primary btn--sm"
              :disabled="docsBusy || !session.hasCredential"
              @click="loadDocs"
            >
              {{ docsBusy ? '刷新中…' : '刷新文档' }}
            </button>
          </template>
          <template #notice>
            <InfoNote v-if="!session.hasCredential" tone="warning">
              请先登录 才能加载文档库。
            </InfoNote>
            <InfoNote v-if="docsError" tone="danger" role="alert">{{ docsError }}</InfoNote>
            <InfoNote v-else-if="docsNote" tone="success">{{ docsNote }}</InfoNote>
          </template>

          <ul v-if="docs.length" class="rag__doclist">
            <li v-for="(d, i) in docs" :key="d.docId ?? i" class="rag__doc">
              <div class="rag__doc-main">
                <strong class="rag__doc-title">{{ d.title ?? '(无标题)' }}</strong>
                <div class="rag__doc-meta">
                  <span v-if="d.category" class="rag__cat">{{ d.category }}</span>
                  <code v-if="d.docId" class="rag__id">{{ d.docId }}</code>
                  <span v-else class="rag__muted">无 ID</span>
                </div>
              </div>
              <div v-if="d.docId" class="rag__doc-actions">
                <button
                  v-if="getCap"
                  type="button"
                  class="btn btn--ghost btn--sm"
                  :disabled="busyKey === `get:${d.docId}`"
                  @click="viewDetail(d.docId!)"
                >
                  {{ busyKey === `get:${d.docId}` ? '…' : '详情' }}
                </button>
                <template v-if="deleteCap">
                  <template v-if="pendingDelete === d.docId">
                    <button
                      type="button"
                      class="btn btn--danger btn--sm"
                      :disabled="busyKey === `del:${d.docId}`"
                      @click="confirmDelete(d.docId!)"
                    >
                      {{ busyKey === `del:${d.docId}` ? '删除中…' : '确认删除' }}
                    </button>
                    <button type="button" class="btn btn--ghost btn--sm" @click="pendingDelete = null">
                      取消
                    </button>
                  </template>
                  <button
                    v-else
                    type="button"
                    class="btn btn--ghost btn--sm rag__del"
                    @click="pendingDelete = d.docId ?? null"
                  >
                    删除
                  </button>
                </template>
              </div>
            </li>
          </ul>

          <EmptyState
            v-else-if="docsLoaded && !docsError"
            variant="empty"
            icon="∅"
            title="没有文档"
            description="当前租户下暂无文档。用下方「文档入库」上传后再检索。"
          />
          <EmptyState
            v-else-if="!docsLoaded"
            variant="empty"
            icon="📚"
            title="尚未加载文档"
            description="点击右上「刷新文档」拉取当前租户文档。"
          />

          <!-- 文档详情 -->
          <div v-if="detailDocId" class="rag__detail" aria-live="polite">
            <h3 class="rag__detail-title">
              文档详情 · <code>{{ detailDocId }}</code>
            </h3>
            <p v-if="detailBusy" class="rag__muted">加载中…</p>
            <InfoNote v-else-if="detailError" tone="danger" role="alert">{{ detailError }}</InfoNote>
            <div v-else-if="detailData != null" class="rag__json">
              <JsonView :data="detailData" />
            </div>
            <p v-else class="rag__muted">无详情返回。</p>
          </div>
        </WorkbenchSection>
      </div>

      <!-- 右：检索台 -->
      <div class="rag__col">
        <WorkbenchSection
          v-if="queryCap"
          title="检索台"
          subtitle="输入查询，结果按相关度分数排序展示（命中词高亮）。"
        >
          <template #notice>
            <InfoNote tone="warning">
              <strong>就绪·降级：</strong>默认 <code>HashEmbedding</code> 内存库（确定性降级，<strong>非真实语义</strong>）。
              接生产语义需配置 <code>qdrant</code> + <code>nomic</code> 等真实向量库 / embedding。
            </InfoNote>
            <InfoNote v-if="!queryGate.allowed && queryGate.reason" tone="warning">
              {{ queryGate.reason }}
            </InfoNote>
          </template>

          <div class="rag__search">
            <label class="rag__field rag__field--wide">
              查询
              <textarea
                v-model="query"
                class="form-control"
                rows="2"
                placeholder="退款政策是什么？"
                aria-label="检索查询"
              />
            </label>
            <div class="rag__params">
              <label class="rag__field">
                TopK
                <input v-model.number="topK" class="form-control" type="number" min="1" max="50" />
              </label>
              <label class="rag__field">
                最低分
                <input
                  v-model.number="minScore"
                  class="form-control"
                  type="number"
                  min="0"
                  max="1"
                  step="0.01"
                  placeholder="可选"
                />
              </label>
              <label class="rag__field">
                类目
                <input v-model="category" class="form-control" type="text" placeholder="可选" />
              </label>
            </div>
            <div class="rag__search-actions">
              <button type="button" class="btn btn--primary" :disabled="!canSearch" @click="runSearch">
                {{ searchBusy ? '检索中…' : '检索' }}
              </button>
            </div>

            <InfoNote v-if="searchError" tone="danger" role="alert">{{ searchError }}</InfoNote>

            <!-- 结果卡（分数排序） -->
            <ul v-if="hits && hits.length" class="rag__hits" aria-live="polite">
              <li v-for="(h, i) in hits" :key="i" class="rag__hit">
                <div class="rag__hit-head">
                  <span class="rag__score" :title="`相关度分数 ${h.score ?? '未知'}`">
                    {{ fmtScore(h.score) }}
                  </span>
                  <code v-if="h.docId" class="rag__id">{{ h.docId }}</code>
                  <span v-if="h.category" class="rag__cat">{{ h.category }}</span>
                </div>
                <p v-if="h.text" class="rag__hit-text"><span
                    v-for="(s, si) in segmentsFor(h.text)"
                    :key="si"
                    :class="{ 'rag__mark': s.hit }"
                  >{{ s.text }}</span></p>
                <p v-else class="rag__muted">（该命中项无文本字段）</p>
              </li>
            </ul>
            <EmptyState
              v-else-if="hits && !hits.length"
              variant="empty"
              icon="∅"
              title="无命中"
              description="没有匹配的文档。放宽最低分或先入库更多文档。"
            />
            <!-- 兜底：无法解析为结果集时用 ResponseViewer -->
            <div v-else-if="searchFallback" class="rag__fallback">
              <ResponseViewer phase="success" :data="searchResult" />
            </div>
            <EmptyState
              v-else-if="!searched"
              variant="empty"
              icon="🔎"
              title="尚未检索"
              description="输入查询并点击「检索」。"
            />
          </div>
        </WorkbenchSection>
      </div>
    </div>

    <!-- 文档入库（file / json / obsidian，需 ingest scope） -->
    <WorkbenchSection
      v-if="uploadRunners.length"
      title="文档入库"
      subtitle="上传文档到知识库以供检索。入库为 caution 操作，需 ingest scope。"
      collapsible
      :default-open="false"
    >
      <template #notice>
        <InfoNote tone="warning">
          入库能力需 <strong>{{ ingestScopeHint }}</strong> scope；若当前 API Key 不具备，将返回 403（已翻译为人话提示）。成功入库后文档库自动刷新。
        </InfoNote>
      </template>
      <div class="rag__runners">
        <CapabilityRunner
          v-for="c in uploadRunners"
          :key="c.id"
          :cap="c"
          @result="onUploaded"
        />
      </div>
    </WorkbenchSection>

    <!-- GraphRAG（flag-off，诚实锁定） -->
    <WorkbenchSection
      v-if="graphRunners.length"
      title="GraphRAG（图谱增强）"
      subtitle="基于实体关系图的检索增强。需开启 feature flag 后方可执行。"
      collapsible
      :default-open="false"
    >
      <template #notice>
        <InfoNote tone="neutral">
          <strong>未启用：</strong>需开启 <code>app.rag.graph.enabled=true</code>。未启用时仅可预览 / 复制 curl，执行将被闸门拦截。
        </InfoNote>
      </template>
      <div class="rag__runners">
        <CapabilityRunner v-for="c in graphRunners" :key="c.id" :cap="c" />
      </div>
    </WorkbenchSection>
  </div>
</template>

<style scoped>
.rag {
  max-width: var(--content-max);
  margin: 0 auto;
  padding: var(--space-6) var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.rag__cols {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr);
  gap: var(--space-5);
  align-items: start;
}
.rag__col {
  min-width: 0;
}
.rag__muted {
  font-size: var(--fs-sm);
  color: var(--text-subtle);
}

/* 文档库 */
.rag__doclist {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.rag__doc {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}
.rag__doc-main {
  min-width: 0;
  flex: 1;
}
.rag__doc-title {
  display: block;
  font-size: var(--fs-sm);
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rag__doc-meta {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: 4px;
  flex-wrap: wrap;
}
.rag__doc-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.rag__del:hover {
  color: var(--danger);
}
.rag__cat {
  font-size: var(--fs-xs);
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  border-radius: var(--radius-sm);
  padding: 0 6px;
}
.rag__id {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  background: var(--surface-2);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 18ch;
}
.rag__detail {
  margin-top: var(--space-4);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}
.rag__detail-title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  margin-bottom: var(--space-2);
}
.rag__detail-title code {
  font-family: var(--font-mono);
  color: var(--primary);
}
.rag__json {
  padding: var(--space-3);
  background: var(--code-bg);
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  overflow: auto;
  max-height: 320px;
}

/* 检索台 */
.rag__search {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.rag__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.rag__params {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-3);
}
.rag__search-actions {
  display: flex;
  gap: var(--space-2);
}
.rag__hits {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.rag__hit {
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}
.rag__hit-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
  flex-wrap: wrap;
}
.rag__score {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  color: var(--success);
  background: var(--success-soft);
  border: 1px solid var(--success-border);
  border-radius: var(--radius-sm);
  padding: 1px 8px;
}
.rag__hit-text {
  font-size: var(--fs-sm);
  color: var(--text);
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.rag__mark {
  border-radius: 3px;
  padding: 0 1px;
  color: var(--text);
  background: var(--warning-soft);
  box-shadow: 0 0 0 1px var(--warning-border);
}
.rag__fallback {
  min-height: 200px;
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  background: var(--code-bg);
  overflow: hidden;
}

/* 运行器分区（入库 / GraphRAG）：纵向堆叠 */
.rag__runners {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

@media (max-width: 1023px) {
  .rag__cols {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
