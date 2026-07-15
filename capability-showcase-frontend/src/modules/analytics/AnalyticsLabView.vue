<script setup lang="ts">
/**
 * Analytics Lab —— Schema 浏览 + NL2SQL 双区工作台（module=analytics）。
 *
 * 左「Schema 浏览器」：analytics.schema.tables → 选表 analytics.schema.describe 看列。
 *   即使 NL2SQL flag-off 也可用（schema 浏览默认开启）。
 * 右「NL2SQL 台」：analytics.sql 自然语言问题 → 生成的 SQL + 结果表格（行集则渲染表格，否则 ResponseViewer 兜底）。
 *   analytics.sql 默认 flag-off：经 executionGate 诚实锁定、仅预览 curl。
 *
 * 深链（capId 存在）沿用通用 CapabilityRunner（与其它 specialized 视图一致）。
 * 所有执行统一经 executionGate + runCapability，不手写 fetch / 路径 / 绕闸门。
 */
import { computed, ref } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import { useSessionStore } from '../../stores/session'
import { runCapability } from '../../api/client'
import { humanizeError } from '../../api/errors'
import { executionGate } from '../../utils/gate'
import { toCurl } from '../../utils/curl'
import type { Capability } from '../../types/catalog'
import type { FormValues } from '../../utils/validation'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import ResponseViewer from '../../components/capability/ResponseViewer.vue'
import JsonView from '../../components/capability/JsonView.vue'
import CopyButton from '../../components/common/CopyButton.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import ModuleHeader from '../../components/layout/ModuleHeader.vue'
import WorkbenchSection from '../_shared/WorkbenchSection.vue'
import InfoNote from '../_shared/InfoNote.vue'
import ResultTable from '../_shared/ResultTable.vue'

const props = defineProps<{ moduleId: string; capId?: string }>()
const catalog = useCatalogStore()
const session = useSessionStore()

const module = computed(() => catalog.moduleById(props.moduleId))
const focusedCap = computed(() =>
  props.capId ? (module.value?.capabilities ?? []).find((c) => c.id === props.capId) : undefined,
)

const tablesCap = computed(() => catalog.capabilityById('analytics.schema.tables'))
const describeCap = computed(() => catalog.capabilityById('analytics.schema.describe'))
const sqlCap = computed(() => catalog.capabilityById('analytics.sql'))

// ── 通用一次性调用（复用 executionGate + runCapability；诚实返回 data / error）──
async function callCap(
  cap: Capability | undefined,
  values: FormValues,
): Promise<{ data?: unknown; error?: string }> {
  if (!cap) return { error: '能力不在目录中。' }
  const gate = executionGate(cap, { ...session.permissionContext(), confirmed: false })
  if (!gate.allowed) return { error: gate.reason ?? '当前不可执行。' }
  try {
    const res = await runCapability(cap, values, session.runContext())
    return { data: res.data }
  } catch (e) {
    return { error: humanizeError(e, cap) }
  }
}

function asStr(v: unknown): string | undefined {
  if (v == null) return undefined
  if (typeof v === 'string') return v.trim() ? v : undefined
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return undefined
}

// ── Schema 浏览器 ──
interface TableItem {
  name: string
}
const tables = ref<TableItem[]>([])
const tablesLoaded = ref(false)
const tablesBusy = ref(false)
const tablesError = ref<string | null>(null)
const selectedTable = ref<string | null>(null)
const describeData = ref<unknown>(null)
const describeBusy = ref(false)
const describeError = ref<string | null>(null)

function parseTables(data: unknown): TableItem[] {
  const arr = Array.isArray(data)
    ? data
    : data && typeof data === 'object' && Array.isArray((data as Record<string, unknown>).tables)
      ? ((data as Record<string, unknown>).tables as unknown[])
      : []
  return arr
    .map((item): TableItem => {
      if (typeof item === 'string') return { name: item }
      if (item && typeof item === 'object') {
        const o = item as Record<string, unknown>
        const name =
          asStr(o.table) ??
          asStr(o.name) ??
          asStr(o.tableName) ??
          asStr(o.TABLE_NAME) ??
          JSON.stringify(item)
        return { name }
      }
      return { name: String(item) }
    })
    .filter((t) => t.name)
}

async function loadTables(): Promise<void> {
  tablesBusy.value = true
  tablesError.value = null
  const { data, error } = await callCap(tablesCap.value, {})
  tablesBusy.value = false
  tablesLoaded.value = true
  if (error) {
    tablesError.value = error
    return
  }
  tables.value = parseTables(data)
}

async function selectTable(name: string): Promise<void> {
  selectedTable.value = name
  describeData.value = null
  describeError.value = null
  if (!describeCap.value) {
    describeError.value = '未找到「表结构详情」能力。'
    return
  }
  describeBusy.value = true
  const { data, error } = await callCap(describeCap.value, { table: name })
  describeBusy.value = false
  if (error) {
    describeError.value = error
    return
  }
  describeData.value = data
}

// 行集探测：array-of-objects / {columns,rows} / array-of-scalars / 嵌套数组字段。不臆造 schema。
function toRowObjects(data: unknown): Record<string, unknown>[] | null {
  if (Array.isArray(data)) {
    if (!data.length) return []
    if (data.every((x) => x != null && typeof x === 'object' && !Array.isArray(x))) {
      return data as Record<string, unknown>[]
    }
    if (data.every((x) => x == null || typeof x !== 'object')) {
      return data.map((v) => ({ value: v }))
    }
    return null
  }
  if (data && typeof data === 'object') {
    const o = data as Record<string, unknown>
    const cols = o.columns
    const rws = o.rows
    if (Array.isArray(cols) && Array.isArray(rws) && rws.every((r) => Array.isArray(r))) {
      const names = cols.map((c, i) =>
        typeof c === 'string'
          ? c
          : (asStr((c as Record<string, unknown>)?.name) ??
            asStr((c as Record<string, unknown>)?.column) ??
            `col${i}`),
      )
      return (rws as unknown[][]).map((r) => {
        const obj: Record<string, unknown> = {}
        names.forEach((n, i) => {
          obj[n] = r[i]
        })
        return obj
      })
    }
    for (const key of ['rows', 'results', 'data', 'records', 'items', 'columns']) {
      if (Array.isArray(o[key])) return toRowObjects(o[key])
    }
  }
  return null
}

const describeRows = computed(() => toRowObjects(describeData.value))

// ── NL2SQL 台 ──
const question = ref('')
const showSqlCurl = ref(false)
const showSqlRaw = ref(false)
const sqlBusy = ref(false)
const sqlError = ref<string | null>(null)
const sqlResult = ref<unknown>(null)

const sqlGate = computed(() =>
  sqlCap.value
    ? executionGate(sqlCap.value, { ...session.permissionContext() })
    : { allowed: false, reason: '未找到 NL2SQL 能力。' },
)
const sqlCurl = computed(() =>
  sqlCap.value
    ? toCurl(sqlCap.value, { question: question.value }, { edgeBaseUrl: session.edgeBaseUrl })
    : '',
)
const canRunSql = computed(
  () => sqlGate.value.allowed && !sqlBusy.value && question.value.trim().length > 0,
)

function extractSql(data: unknown): string | null {
  if (data && typeof data === 'object') {
    const o = data as Record<string, unknown>
    for (const k of ['sql', 'generatedSql', 'sqlQuery', 'query', 'sql_text']) {
      const v = o[k]
      if (typeof v === 'string' && v.trim()) return v
    }
  }
  return null
}

const generatedSql = computed(() => extractSql(sqlResult.value))
const sqlRows = computed(() => toRowObjects(sqlResult.value))
// 结构化视图（SQL / 表格）之外仍无法解析时，用 ResponseViewer 兜底。
const sqlFallback = computed(
  () => sqlResult.value != null && !generatedSql.value && !sqlRows.value,
)

async function runSql(): Promise<void> {
  if (!canRunSql.value) return
  sqlBusy.value = true
  sqlError.value = null
  sqlResult.value = null
  const { data, error } = await callCap(sqlCap.value, { question: question.value.trim() })
  sqlBusy.value = false
  if (error) {
    sqlError.value = error
    return
  }
  sqlResult.value = data ?? null
}
</script>

<template>
  <EmptyState
    v-if="!module"
    variant="error"
    title="模块不存在"
    :description="`未找到模块「${moduleId}」。`"
  />

  <!-- 深链：单能力聚焦（沿用通用运行器，含 gate/curl/危险确认） -->
  <CapabilityRunner v-else-if="capId && focusedCap" :key="focusedCap.id" :cap="focusedCap" />

  <EmptyState
    v-else-if="capId && !focusedCap"
    variant="error"
    title="能力不存在"
    :description="`模块「${module.title}」下未找到能力「${capId}」。`"
  />

  <!-- 工作台着陆：Schema 浏览器（左） + NL2SQL 台（右） -->
  <div v-else class="al">
    <ModuleHeader :module-id="moduleId" />

    <div class="al__cols">
      <!-- 左：Schema 浏览器 -->
      <div class="al__col">
        <WorkbenchSection
          v-if="tablesCap"
          title="Schema 浏览器"
          subtitle="浏览业务表与列结构；即使 NL2SQL 未启用也可用（schema 浏览默认开启）。"
        >
          <template #actions>
            <button
              type="button"
              class="btn btn--primary btn--sm"
              :disabled="tablesBusy || !session.hasCredential"
              @click="loadTables"
            >
              {{ tablesBusy ? '加载中…' : '加载表清单' }}
            </button>
          </template>
          <template #notice>
            <InfoNote tone="info">
              <code>GET /analytics/schema/tables</code> 与 <code>/tables/&#123;table&#125;</code> 均为只读元数据接口，不依赖 NL2SQL flag。
            </InfoNote>
            <InfoNote v-if="!session.hasCredential" tone="warning">
              请先登录 才能加载 schema。
            </InfoNote>
            <InfoNote v-if="tablesError" tone="danger" role="alert">{{ tablesError }}</InfoNote>
          </template>

          <div class="al__schema">
            <!-- 表清单 -->
            <div class="al__tables">
              <ul v-if="tables.length" class="al__tablelist" role="listbox" aria-label="表清单">
                <li v-for="t in tables" :key="t.name">
                  <button
                    type="button"
                    class="al__table-item"
                    :class="{ 'is-selected': t.name === selectedTable }"
                    role="option"
                    :aria-selected="t.name === selectedTable"
                    @click="selectTable(t.name)"
                  >
                    <span class="al__table-icon" aria-hidden="true">▤</span>
                    <span class="al__table-name">{{ t.name }}</span>
                  </button>
                </li>
              </ul>
              <EmptyState
                v-else-if="tablesLoaded"
                variant="empty"
                icon="∅"
                title="没有表"
                description="当前租户 schema 下未返回任何表。"
              />
              <EmptyState
                v-else
                variant="empty"
                icon="▤"
                title="尚未加载 schema"
                description="点击右上「加载表清单」拉取业务表。"
              />
            </div>

            <!-- 选中表结构 -->
            <div v-if="selectedTable" class="al__describe" aria-live="polite">
              <h3 class="al__describe-title">
                <code>{{ selectedTable }}</code> 结构
              </h3>
              <p v-if="describeBusy" class="al__muted">加载列结构中…</p>
              <InfoNote v-else-if="describeError" tone="danger" role="alert">{{ describeError }}</InfoNote>
              <ResultTable
                v-else-if="describeRows && describeRows.length"
                :rows="describeRows"
                :caption="`${describeRows.length} 列`"
              />
              <div v-else-if="describeData != null" class="al__json">
                <JsonView :data="describeData" />
              </div>
              <p v-else class="al__muted">该表无列信息返回。</p>
            </div>
          </div>
        </WorkbenchSection>
      </div>

      <!-- 右：NL2SQL 台 -->
      <div class="al__col">
        <WorkbenchSection
          v-if="sqlCap"
          title="NL2SQL 台"
          subtitle="自然语言问题 → 生成 SQL 与结果。生成的 SQL 与行集将结构化呈现（无法解析时回退原始响应）。"
        >
          <template #notice>
            <InfoNote v-if="!sqlGate.allowed && sqlGate.reason" tone="warning">
              {{ sqlGate.reason }}<span v-if="sqlCap.state === 'flag-off'">仅可预览 curl。</span>
            </InfoNote>
            <InfoNote v-else-if="sqlGate.hint" tone="info">{{ sqlGate.hint }}</InfoNote>
          </template>

          <div class="al__sql">
            <label class="al__sql-label">
              自然语言问题
              <textarea
                v-model="question"
                class="form-control al__sql-input"
                rows="3"
                placeholder="统计每个类目的文档数量"
                aria-label="NL2SQL 自然语言问题"
              />
            </label>

            <div class="al__sql-actions">
              <button
                type="button"
                class="btn btn--primary"
                :disabled="!canRunSql"
                @click="runSql"
              >
                {{ sqlBusy ? '生成中…' : '生成并执行' }}
              </button>
              <button type="button" class="btn btn--ghost" @click="showSqlCurl = !showSqlCurl">
                {{ showSqlCurl ? '隐藏' : '预览' }} curl
              </button>
            </div>

            <div v-if="showSqlCurl" class="al__curl">
              <div class="al__curl-bar">
                <span>curl 预览（凭证以占位符呈现，不含明文）</span>
                <CopyButton :text="sqlCurl" compact />
              </div>
              <pre class="al__curl-code">{{ sqlCurl }}</pre>
            </div>

            <InfoNote v-if="sqlError" tone="danger" role="alert">{{ sqlError }}</InfoNote>

            <!-- 生成的 SQL -->
            <div v-if="generatedSql" class="al__result-block">
              <div class="al__result-head">
                <span class="al__result-tag">生成的 SQL</span>
                <CopyButton :text="generatedSql" compact />
              </div>
              <pre class="al__sql-code">{{ generatedSql }}</pre>
            </div>

            <!-- 结果表格 -->
            <div v-if="sqlRows && sqlRows.length" class="al__result-block">
              <div class="al__result-head">
                <span class="al__result-tag">结果集</span>
                <span class="al__muted">{{ sqlRows.length }} 行</span>
              </div>
              <ResultTable :rows="sqlRows" />
            </div>
            <p v-else-if="sqlRows && !sqlRows.length" class="al__muted">查询成功，结果集为空。</p>

            <!-- 兜底：无法结构化解析时用 ResponseViewer -->
            <div v-if="sqlFallback" class="al__fallback">
              <ResponseViewer phase="success" :data="sqlResult" />
            </div>

            <!-- 原始响应（结构化视图存在时可展开对照，保证不隐藏字段） -->
            <div v-if="sqlResult != null && !sqlFallback" class="al__raw">
              <button
                type="button"
                class="al__raw-toggle"
                :aria-expanded="showSqlRaw"
                @click="showSqlRaw = !showSqlRaw"
              >
                <span class="al__chevron" :class="{ 'is-open': showSqlRaw }" aria-hidden="true">▸</span>
                原始响应
              </button>
              <div v-show="showSqlRaw" class="al__json">
                <JsonView :data="sqlResult" />
              </div>
            </div>
          </div>
        </WorkbenchSection>
      </div>
    </div>
  </div>
</template>

<style scoped>
.al {
  max-width: var(--content-max);
  margin: 0 auto;
  padding: var(--space-6) var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.al__cols {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr);
  gap: var(--space-5);
  align-items: start;
}
.al__col {
  min-width: 0;
}
.al__muted {
  font-size: var(--fs-sm);
  color: var(--text-subtle);
}

/* Schema 浏览器 */
.al__schema {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.al__tablelist {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  max-height: 320px;
  overflow: auto;
}
.al__table-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  text-align: left;
  padding: var(--space-2) var(--space-3);
  font-size: var(--fs-sm);
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: border-color var(--dur) var(--ease), background var(--dur) var(--ease);
}
.al__table-item:hover {
  border-color: var(--primary-border);
  background: var(--surface-2);
}
.al__table-item.is-selected {
  color: var(--primary);
  border-color: var(--primary);
  background: var(--primary-soft);
}
.al__table-item:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.al__table-icon {
  color: var(--text-subtle);
}
.al__table-name {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.al__describe-title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  margin-bottom: var(--space-2);
}
.al__describe-title code {
  font-family: var(--font-mono);
  color: var(--primary);
}

/* NL2SQL 台 */
.al__sql {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.al__sql-label {
  display: block;
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.al__sql-input {
  margin-top: 6px;
}
.al__sql-actions {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.al__result-block {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.al__result-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.al__result-tag {
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--primary);
}
.al__sql-code {
  margin: 0;
  padding: var(--space-3);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text);
  background: var(--code-bg);
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
}
.al__curl {
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  overflow: hidden;
}
.al__curl-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  background: var(--surface-2);
}
.al__curl-code {
  margin: 0;
  padding: var(--space-3);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text);
  background: var(--code-bg);
  overflow-x: auto;
}
.al__json {
  padding: var(--space-3);
  background: var(--code-bg);
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  overflow: auto;
  max-height: 360px;
}
.al__fallback {
  min-height: 200px;
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  background: var(--code-bg);
  overflow: hidden;
}
.al__raw-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  font-size: var(--fs-sm);
  color: var(--text-muted);
  background: none;
  border: none;
  cursor: pointer;
}
.al__chevron {
  color: var(--text-subtle);
  transition: transform var(--dur) var(--ease);
}
.al__chevron.is-open {
  transform: rotate(90deg);
}

@media (max-width: 1023px) {
  .al__cols {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (prefers-reduced-motion: reduce) {
  .al__chevron {
    transition: none;
  }
}
</style>
