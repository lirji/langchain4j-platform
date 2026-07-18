<script setup lang="ts">
/**
 * RAG Workspace —— 文档库 + 检索台 双区工作台（module=rag）。
 *
 * 左「文档库」：rag.documents.list 列表（标题/类目/id + 详情/删除）；文档入库（file/json/obsidian，需 ingest scope）。
 * 右「检索台」：rag.query 输入 → 带分数排序的结果卡（score/docId/category/text，高亮命中）。
 *   顶部横幅按后端 /rag/config.rag 动态呈现：语义就绪时展示真实 provider/模型 + 混排开关；
 *   未探测到运行时（v1 后端 / 无凭证 / 探测失败）诚实回退到"默认 HashEmbedding 降级"提示。
 * GraphRAG 子分区（rag.graph.query / entities，flag-off）：诚实锁定。
 *
 * 深链（capId 存在）沿用通用 CapabilityRunner。执行统一经 executionGate + runCapability。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useAbortable } from '../../composables/useAbortable'
import { useCatalogStore } from '../../stores/catalog'
import { useSessionStore } from '../../stores/session'
import { runCapability } from '../../api/client'
import { humanizeError } from '../../api/errors'
import { executionGate } from '../../utils/gate'
import { highlightSegments } from '../../utils/highlight'
import { SHARED_KB_UI_ENABLED } from '../../config'
import { deleteDocument, fetchRagConfig, getDocument, listDocumentsPaged } from '../../api/knowledge'
import type { DocumentInfo, KnowledgeRuntimeView, Visibility } from '../../types/knowledge'
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

const uploadFileSharedCap = computed(() => catalog.capabilityById('rag.upload.file.shared'))
const uploadJsonSharedCap = computed(() => catalog.capabilityById('rag.upload.json.shared'))
/** 租户库入库（file/json/obsidian，需 ingest）——始终可用。 */
const tenantUploadRunners = computed<Capability[]>(() =>
  [uploadFileCap.value, uploadJsonCap.value, obsidianCap.value].filter((c): c is Capability => !!c),
)
/** 共享库入库（需 public-ingest）——仅在 SHARED_KB_UI 开 + 运行时 publicEnabled 时出现（sharedTabEnabled）。 */
const sharedUploadRunners = computed<Capability[]>(() =>
  sharedTabEnabled.value
    ? [uploadFileSharedCap.value, uploadJsonSharedCap.value].filter((c): c is Capability => !!c)
    : [],
)
const graphRunners = computed<Capability[]>(() =>
  [graphQueryCap.value, graphEntitiesCap.value].filter((c): c is Capability => !!c),
)
/** GraphRAG 是否未启用（issue-13）：以目录 state 为事实源，不硬编码「未启用」文案。 */
const graphOff = computed(() => graphRunners.value.some((c) => c.state === 'flag-off'))
const graphFlag = computed(
  () => graphRunners.value.find((c) => c.featureFlag)?.featureFlag ?? 'app.rag.graph.enabled',
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

// ── 通用一次性调用（复用 executionGate + runCapability；可选 AbortSignal 供卸载中止）──
async function callCap(
  cap: Capability | undefined,
  values: FormValues,
  signal?: AbortSignal,
): Promise<{ data?: unknown; error?: string }> {
  if (!cap) return { error: '能力不在目录中。' }
  const gate = executionGate(cap, { ...session.permissionContext(), confirmed: false })
  if (!gate.allowed) return { error: gate.reason ?? '当前不可执行。' }
  try {
    const res = await runCapability(cap, values, session.runContext(signal))
    return { data: res.data }
  } catch (e) {
    return { error: humanizeError(e, cap) }
  }
}

// ── 知识运行时配置 + 可见性分区（租户库 / 共享库）──
const ragConfig = ref<KnowledgeRuntimeView | null>(null)
const visibility = ref<Visibility>('tenant')
/** 共享 tab 双控：构建开关 SHARED_KB_UI_ENABLED && 运行时 rag-config.publicEnabled。 */
const sharedTabEnabled = computed(
  () => SHARED_KB_UI_ENABLED && ragConfig.value?.publicEnabled === true,
)
/** 共享图片入库是否受支持（后端权威；false 时共享图片入口禁用 + 说明）。 */
const sharedImagesSupported = computed(() => ragConfig.value?.sharedImagesSupported === true)
/**
 * 当前 RAG 后端运行时形态（合同 v2）。为 null 时（v1 后端 / 未探测 / 探测失败）检索台横幅诚实回退到
 * "默认 HashEmbedding 降级"提示；非 null 且 semantic 时展示真实 provider / 模型 / 混排开关。
 */
const ragRuntime = computed(() => ragConfig.value?.rag ?? null)
const onOff = (v: boolean): string => (v ? '开' : '关')
/** 构建开关已开、但后端明确关闭共享分区时给出说明（区别于"未探测"）。 */
const sharedDisabledNote = computed(
  () => SHARED_KB_UI_ENABLED && ragConfig.value != null && ragConfig.value.publicEnabled === false,
)

async function probeRagConfig(): Promise<void> {
  if (!session.hasCredential) return
  try {
    ragConfig.value = await fetchRagConfig(session.runContext())
  } catch {
    ragConfig.value = null // 探测失败：共享分区保持隐藏（fail-closed）
  }
}

// ── 文档库（改用强类型 DocumentInfo，按 tab 可见性 + 分页请求）──
const docs = ref<DocumentInfo[]>([])
/** 分页：租户库 / 共享库各自一套页码；切 tab 归 1。page 1-based，total/totalPages 由服务端权威回写。 */
const PAGE_SIZE_OPTIONS = [10, 20, 50] as const
const pageSize = ref<number>(PAGE_SIZE_OPTIONS[0])
const page = ref(1)
const total = ref(0)
const totalPages = ref(1)
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
// 乱序保护：快速切换 tab / 连点详情时，慢请求后到不得覆盖新结果（否则会把共享文档误标成租户，或详情张冠李戴）。
let docsSeq = 0
let detailSeq = 0

function fmtBytes(n: number): string {
  if (!Number.isFinite(n)) return '—'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}
function fmtDate(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}

async function loadDocs(): Promise<void> {
  if (!session.hasCredential) return
  const my = ++docsSeq
  const reqVis = visibility.value // 与本次请求绑定的可见性
  docsBusy.value = true
  docsError.value = null
  docsNote.value = null
  try {
    const result = await listDocumentsPaged(reqVis, page.value, pageSize.value, session.runContext())
    if (my !== docsSeq) return // 已被更新的请求取代 → 丢弃陈旧结果，绝不覆盖新 tab
    docs.value = result.items
    page.value = result.page // 服务端 clamp 后回写（如删到末页越界会被拉回最后一页）
    total.value = result.total
    totalPages.value = result.totalPages
    docsLoaded.value = true
    if (!result.total) {
      docsNote.value =
        reqVis === 'public' ? '共享知识库暂无文档。' : '当前租户下暂无文档。上传后可在此管理与检索。'
    }
  } catch (e) {
    if (my !== docsSeq) return
    docsError.value = humanizeError(e)
    docsLoaded.value = true
  } finally {
    if (my === docsSeq) docsBusy.value = false
  }
}

/** 翻页：clamp 到 [1, totalPages] 后按新页重拉；忙时忽略、同页不重复请求。 */
async function goToPage(target: number): Promise<void> {
  const next = Math.min(Math.max(target, 1), totalPages.value)
  if (next === page.value || docsBusy.value) return
  page.value = next
  pendingDelete.value = null
  await loadDocs()
}

/** 改每页条数：归第 1 页后重拉（总页数随之变化，回第 1 页最直观）。 */
async function changePageSize(size: number): Promise<void> {
  if (size === pageSize.value) return
  pageSize.value = size
  page.value = 1
  pendingDelete.value = null
  await loadDocs()
}

/** 切换可见性 tab：清空当前列表/详情、页码归 1，并按新 tab 重新拉取。 */
async function switchTab(v: Visibility): Promise<void> {
  if (v === visibility.value) return
  visibility.value = v
  docs.value = []
  page.value = 1
  total.value = 0
  totalPages.value = 1
  docsLoaded.value = false
  detailDocId.value = null
  detailData.value = null
  pendingDelete.value = null
  await loadDocs()
}

async function viewDetail(docId: string): Promise<void> {
  const my = ++detailSeq
  const reqVis = visibility.value
  detailDocId.value = docId
  detailData.value = null
  detailError.value = null
  detailBusy.value = true
  busyKey.value = `get:${docId}`
  try {
    const data = await getDocument(docId, reqVis, session.runContext())
    if (my !== detailSeq) return // 已被更新的详情请求取代 → 丢弃，避免张冠李戴
    detailData.value = data
  } catch (e) {
    if (my !== detailSeq) return
    detailError.value = humanizeError(e)
  } finally {
    if (my === detailSeq) {
      detailBusy.value = false
      busyKey.value = null
    }
  }
}

async function confirmDelete(docId: string): Promise<void> {
  busyKey.value = `del:${docId}`
  docsError.value = null
  try {
    // 删共享需 public-ingest（后端 403 兜底并翻译为人话）。
    await deleteDocument(docId, visibility.value, session.runContext())
    if (detailDocId.value === docId) {
      detailDocId.value = null
      detailData.value = null
    }
    docsNote.value = `文档 ${docId} 已删除。`
    await loadDocs()
  } catch (e) {
    docsError.value = humanizeError(e)
  } finally {
    busyKey.value = null
    pendingDelete.value = null
  }
}

function onUploaded(): void {
  // 入库成功后刷新文档库（若已填凭证）；回到第 1 页——新文档按上传时间降序排在最前，便于立即看到。
  if (session.hasCredential) {
    page.value = 1
    void loadDocs()
  }
}

// 启动时探测 rag 配置；凭证到位后再探一次（登录/填 Key 后共享分区才能出现）。
onMounted(() => {
  void probeRagConfig()
})
watch(
  () => session.hasCredential,
  (has) => {
    if (has) void probeRagConfig()
  },
)

// ── 检索台 ──
interface Hit {
  score?: number
  docId?: string
  category?: string
  text?: string
  /** 服务端权威 visibility（KnowledgeHit.visibility）；前端不靠 docId/名称推断。 */
  visibility?: Visibility
  raw: unknown
}
const query = ref('')
/** 提交时的查询快照（issue-12）：命中高亮只跟随本批结果对应的查询，请求期间改输入不影响已回结果。 */
const submittedQuery = ref('')
const topK = ref<number | null>(5)
const minScore = ref<number | null>(null)
const category = ref('')
const searchBusy = ref(false)
const searchError = ref<string | null>(null)
const searchResult = ref<unknown>(null)
const searched = ref(false)
// 检索请求的 AbortController：新检索中止旧请求，组件卸载自动中止（issue-15）。
const searchAbort = useAbortable()

/** TopK/最低分边界校验（issue-08）：越界禁发并显示字段错误，不依赖 HTML min/max。 */
const topKError = computed(() => {
  if (topK.value == null) return null
  return Number.isInteger(topK.value) && topK.value >= 1 && topK.value <= 50
    ? null
    : 'TopK 不能小于 1 或大于 50。'
})
const minScoreError = computed(() => {
  if (minScore.value == null) return null
  return Number.isFinite(minScore.value) && minScore.value >= 0 && minScore.value <= 1
    ? null
    : '最低分需在 0..1 之间。'
})

const queryGate = computed(() => {
  if (!queryCap.value) return { allowed: false, reason: '未找到检索能力。' }
  return executionGate(queryCap.value, { ...session.permissionContext() })
})
const canSearch = computed(
  () =>
    queryGate.value.allowed &&
    !searchBusy.value &&
    query.value.trim().length > 0 &&
    !topKError.value &&
    !minScoreError.value,
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
          visibility: o.visibility === 'public' || o.visibility === 'tenant' ? o.visibility : undefined,
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
  // 用提交快照高亮（issue-12）：结果与其对应查询绑定，请求期间编辑输入不改变已回结果的高亮。
  return highlightSegments(text, submittedQuery.value)
}

async function runSearch(): Promise<void> {
  if (!canSearch.value) return
  const trimmed = query.value.trim()
  submittedQuery.value = trimmed
  searchBusy.value = true
  searchError.value = null
  searchResult.value = null
  const values: FormValues = { query: trimmed }
  if (topK.value != null) values.topK = topK.value
  if (minScore.value != null) values.minScore = minScore.value
  if (category.value.trim()) values.category = category.value.trim()
  // fresh()：新检索中止上一次仍在途的检索；组件卸载时 useAbortable 自动中止（issue-15）。
  const controller = searchAbort.fresh()
  const { data, error } = await callCap(queryCap.value, values, controller.signal)
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
// 入库可见性现由每个上传表单的「可见性」字段承载（随请求发送），不再用外部选择器，避免双重控件失真。
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

          <!-- 可见性分区 tabs：租户库 / 共享库（共享仅 sharedTabEnabled 时出现） -->
          <div class="rag__tabs" role="tablist" aria-label="文档库可见性">
            <button
              type="button"
              role="tab"
              class="rag__tab"
              :class="{ active: visibility === 'tenant' }"
              :aria-selected="visibility === 'tenant'"
              @click="switchTab('tenant')"
            >
              我的租户库
            </button>
            <button
              v-if="sharedTabEnabled"
              type="button"
              role="tab"
              class="rag__tab"
              :class="{ active: visibility === 'public' }"
              :aria-selected="visibility === 'public'"
              @click="switchTab('public')"
            >
              共享知识库
            </button>
          </div>
          <InfoNote v-if="sharedDisabledNote" tone="neutral" class="rag__tabs-note">
            共享知识库当前未开放（服务端 <code>publicEnabled=false</code>），仅显示当前租户文档。
          </InfoNote>

          <ul v-if="docs.length" class="rag__doclist">
            <li v-for="d in docs" :key="d.docId" class="rag__doc">
              <div class="rag__doc-main">
                <strong class="rag__doc-title">{{ d.displayName || '(无标题)' }}</strong>
                <div class="rag__doc-meta">
                  <span class="rag__vis" :data-vis="visibility">
                    {{ visibility === 'public' ? '共享' : '租户' }}
                  </span>
                  <span v-if="d.category" class="rag__cat">{{ d.category }}</span>
                  <code class="rag__id">{{ d.docId }}</code>
                </div>
                <div class="rag__doc-sub">
                  <span title="片段数">🧩 {{ d.segmentCount }} 段</span>
                  <span title="大小">· {{ fmtBytes(d.sizeBytes) }}</span>
                  <span title="版本">· v{{ d.version }}</span>
                  <span title="上传时间">· {{ fmtDate(d.uploadedAt) }}</span>
                </div>
              </div>
              <div class="rag__doc-actions">
                <button
                  v-if="getCap"
                  type="button"
                  class="btn btn--ghost btn--sm"
                  :disabled="busyKey === `get:${d.docId}`"
                  @click="viewDetail(d.docId)"
                >
                  {{ busyKey === `get:${d.docId}` ? '…' : '详情' }}
                </button>
                <template v-if="deleteCap">
                  <template v-if="pendingDelete === d.docId">
                    <button
                      type="button"
                      class="btn btn--danger btn--sm"
                      :disabled="busyKey === `del:${d.docId}`"
                      @click="confirmDelete(d.docId)"
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
                    :title="visibility === 'public' ? '删除共享文档需 public-ingest scope' : '删除文档'"
                    @click="pendingDelete = d.docId"
                  >
                    {{ visibility === 'public' ? '删除 ⚠' : '删除' }}
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

          <!-- 分页控件（仅当前 tab 有文档时出现；页码 / 总数服务端权威） -->
          <nav v-if="docsLoaded && total > 0" class="rag__pager" aria-label="文档分页">
            <button
              type="button"
              class="btn btn--ghost btn--sm"
              :disabled="page <= 1 || docsBusy"
              @click="goToPage(page - 1)"
            >
              上一页
            </button>
            <span class="rag__pager-info" aria-live="polite">
              第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 条
            </span>
            <button
              type="button"
              class="btn btn--ghost btn--sm"
              :disabled="page >= totalPages || docsBusy"
              @click="goToPage(page + 1)"
            >
              下一页
            </button>
            <label class="rag__pager-size">
              每页
              <select
                class="form-control"
                :value="pageSize"
                :disabled="docsBusy"
                aria-label="每页条数"
                @change="changePageSize(Number(($event.target as HTMLSelectElement).value))"
              >
                <option v-for="n in PAGE_SIZE_OPTIONS" :key="n" :value="n">{{ n }}</option>
              </select>
              条
            </label>
          </nav>

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
            <!-- 语义就绪：如实展示当前后端实际形态（技术 provider / 模型 + 混排开关）。 -->
            <InfoNote v-if="ragRuntime?.semantic" tone="success">
              <strong>语义就绪：</strong>embedding <code>{{ ragRuntime.embeddingModel }}</code>（<code>{{
                ragRuntime.embeddingProvider
              }}</code>）· 向量库 <code>{{ ragRuntime.vectorStoreProvider }}</code>。
              <br />
              混排：ES 全文 <strong>{{ onOff(ragRuntime.esHybridEnabled) }}</strong> · 关键词
              <strong>{{ onOff(ragRuntime.keywordHybridEnabled) }}</strong> · GraphRAG
              <strong>{{ onOff(ragRuntime.graphEnabled) }}</strong> · 多模态
              <strong>{{ onOff(ragRuntime.multimodalEnabled) }}</strong> · 融合
              <code>{{ ragRuntime.fusionStrategy }}</code>。
            </InfoNote>
            <!-- 降级 或 未探测到运行时：诚实回退到 HashEmbedding 提示（安全默认）。 -->
            <InfoNote v-else tone="warning">
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
            <p v-if="topKError" class="rag__field-err" role="alert">{{ topKError }}</p>
            <p v-if="minScoreError" class="rag__field-err" role="alert">{{ minScoreError }}</p>

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
                  <!-- 命中来源 visibility 徽章：只读服务端 hit.visibility，绝不推断 -->
                  <span v-if="h.visibility" class="rag__vis" :data-vis="h.visibility">
                    {{ h.visibility === 'public' ? '共享' : '租户' }}
                  </span>
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

    <!-- 文档入库（租户库 file/json/obsidian 需 ingest；共享库另有专用入口需 public-ingest） -->
    <WorkbenchSection
      v-if="tenantUploadRunners.length || sharedUploadRunners.length"
      title="文档入库"
      subtitle="上传文档到知识库以供检索。入库为 caution 操作。"
      collapsible
      :default-open="false"
    >
      <template #notice>
        <InfoNote tone="warning">
          租户库入库需 <strong>{{ ingestScopeHint }}</strong> scope；若当前凭证不具备，将返回 403（已翻译为人话提示）。成功入库后文档库自动刷新。
        </InfoNote>
        <InfoNote v-if="sharedTabEnabled" tone="neutral">
          「<strong>共享库</strong>」入库写公共分区，将对<strong>所有租户</strong>检索可见，需 <code>public-ingest</code> scope（与租户库入口分开）。<span v-if="!sharedImagesSupported">共享图片入库暂不支持，仅文本 / JSON。</span>
        </InfoNote>
      </template>
      <div class="rag__runners">
        <p class="rag__uplabel">当前租户库</p>
        <CapabilityRunner
          v-for="c in tenantUploadRunners"
          :key="c.id"
          :cap="c"
          @result="onUploaded"
        />
        <template v-if="sharedUploadRunners.length">
          <p class="rag__uplabel rag__uplabel--shared">共享知识库（全租户可检索 · 需 public-ingest）</p>
          <CapabilityRunner
            v-for="c in sharedUploadRunners"
            :key="c.id"
            :cap="c"
            @result="onUploaded"
          />
        </template>
      </div>
    </WorkbenchSection>

    <!-- GraphRAG：文案按目录 state 动态呈现（ready 可执行；flag-off 诚实锁定） -->
    <WorkbenchSection
      v-if="graphRunners.length"
      title="GraphRAG（图谱增强）"
      :subtitle="graphOff ? '基于实体关系图的检索增强。需开启 feature flag 后方可执行。' : '基于实体关系图的检索增强。'"
      collapsible
      :default-open="false"
    >
      <template #notice>
        <InfoNote v-if="graphOff" tone="neutral">
          <strong>未启用：</strong>需开启 <code>{{ graphFlag }}=true</code>。未启用时仅可预览 / 复制 curl，执行将被闸门拦截。
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

/* 可见性 tabs（分段控件） */
.rag__tabs {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  margin-bottom: var(--space-3);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.rag__tab {
  padding: 5px 12px;
  font-size: var(--fs-sm);
  color: var(--text-muted);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}
.rag__tab:hover {
  color: var(--text);
}
.rag__tab.active {
  color: var(--primary);
  background: var(--surface);
  box-shadow: inset 0 0 0 1px var(--primary-border);
}
.rag__tabs-note {
  margin-bottom: var(--space-3);
}
/* visibility 徽章：租户=primary、共享=stream（图标+文字双标，AA） */
.rag__vis {
  font-size: var(--fs-xs);
  padding: 0 6px;
  border-radius: var(--radius-sm);
}
.rag__vis[data-vis='tenant'] {
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
}
.rag__vis[data-vis='public'] {
  color: var(--stream);
  background: var(--stream-soft);
  border: 1px solid var(--stream-border);
}

/* 文档库 */
.rag__doclist {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.rag__doc-sub {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 4px;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}

/* 分页控件 */
.rag__pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  margin-top: var(--space-3);
  flex-wrap: wrap;
}
.rag__pager-info {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  white-space: nowrap;
}
.rag__pager-size {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  white-space: nowrap;
}
.rag__pager-size .form-control {
  width: auto;
  padding: 2px 6px;
  font-size: var(--fs-xs);
}

/* 入库可见性选择 */
.rag__upvis {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.rag__upvis-label {
  color: var(--text-subtle);
}
.rag__radio {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
}
.rag__confirm {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.rag__shared-imgoff {
  display: inline-block;
  margin-left: 4px;
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
/* 手机档：文档行允许操作按钮换行到下一行，避免标题被挤没 */
@media (max-width: 640px) {
  .rag__doc {
    flex-wrap: wrap;
  }
  .rag__doc-main {
    flex: 1 1 100%;
  }
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
.rag__field-err {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--danger);
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
/* 手机档：TopK/最低分/类目三列塌单列，避免挤成三窄条 */
@media (max-width: 640px) {
  .rag__params {
    grid-template-columns: minmax(0, 1fr);
  }
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
.rag__uplabel {
  margin: 0;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
}
.rag__uplabel--shared {
  color: var(--stream, var(--primary));
}

@media (max-width: 1023px) {
  .rag__cols {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
