<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ metadata?: unknown }>()
/** 只展示后端约定的来源字段；未知扩展不直接展开，避免泄露内部元数据。 */
const source = computed<Record<string, string>>(() => props.metadata && typeof props.metadata === 'object'
  ? Object.fromEntries(Object.entries(props.metadata).filter(([, value]) => typeof value === 'string')) : {})
const EXTRACTION: Record<string, string> = { native: '原生文字', 'tesseract-auto-osd': 'OCR 识别', 'native+tesseract-auto-osd': '原生文字与 OCR 合并', blank: '空白页' }
const KIND: Record<string, string> = { text: '正文', code: '代码', table: '表格', page: '页面' }
const STRATEGY: Record<string, string> = { structure: '按文档结构', recursive: '递归分块', semantic: '语义分块', 'markdown-header': 'Markdown 标题', 'parent-child': '父子分块' }
const CONTEXT: Record<string, string> = { applied: '已添加检索上下文', fallback: '增强失败，保留原文', 'prefix-limit': '增强前缀超限，保留原文' }
const WARNINGS: Record<string, string> = {
  FIRST_NONEMPTY_ROW_USED_AS_HEADER: '首个非空行按表头处理，请核对表头含义。',
  FORMULA_CACHED_VALUE_NOT_RECALCULATED: '公式使用文件中保存的缓存值，未重新计算。',
  MERGED_CELLS_NOT_EXPANDED: '合并单元格未展开，请核对表格关系。',
  HIDDEN_SHEET_OR_ROW_INCLUDED: '包含隐藏工作表或隐藏行的内容。',
  PDF_OCR_APPLIED: '部分页面使用了 OCR。',
  PDF_NATIVE_OCR_MERGED_READING_ORDER_UNVERIFIED: '原生文字与 OCR 已合并，阅读顺序尚未验证。',
  PDF_BLANK_PAGE: '检测到空白页。', PDF_PAGE_WITHOUT_TEXT: '存在未提取到文字的页面，不能据此认定全文完整。',
  PDF_READING_ORDER_UNVERIFIED: 'PDF 阅读顺序尚未验证，复杂多栏请对照原文。',
  PDF_ROTATED_PAGE: '检测到旋转页面。', EMBEDDED_ATTACHMENTS_NOT_PARSED: '嵌入附件未解析。',
  WORD_AUXILIARY_CONTENT_NOT_INDEXED: 'Word 辅助内容未进入索引。', WORD_TRACKED_CHANGES_NOT_RESOLVED: 'Word 修订尚未合并，请核对采用的版本。',
  WORD_LIST_LABELS_NOT_RENDERED: 'Word 列表编号未完整还原。', FORMAT_STRUCTURE_NOT_AVAILABLE: '该格式仅提供正文，未提供可靠的结构定位。',
}
const fields = computed(() => {
  const s = source.value
  const rows: { label: string; value: string }[] = []
  const add = (key: string, label: string, values?: Record<string, string>) => {
    if (s[key]) rows.push({ label, value: values?.[s[key]] ?? s[key] })
  }
  add('source_format', '文件格式'); add('source_kind', '片段类型', KIND); add('source_block', '原文块编号')
  add('source_page', '页码'); add('source_sheet', '工作表'); add('section', '章节'); add('breadcrumb', '标题路径')
  add('source_paragraph', '段落'); add('source_table', '表格编号')
  add('source_row_start', '起始行（逻辑记录）'); add('source_row_end', '结束行（逻辑记录）')
  add('source_line_start', '原文起始行'); add('source_line_end', '原文结束行')
  add('source_columns', '列范围'); add('source_row_fragment', '行内分片'); add('source_code_fragment', '代码分片')
  add('source_extraction_method', '提取方式', EXTRACTION); add('source_rotation', '页面旋转角度')
  add('source_native_chars', '原生文字字符数'); add('source_ocr_chars', 'OCR 字符数')
  add('source_ocr_confidence', 'OCR 置信分（0–100）'); add('chunking_actual_strategy', '实际分块策略', STRATEGY)
  add('contextual_status', '上下文增强状态', CONTEXT); add('source_pages_without_text', '无文字页码')
  if (!rows.length) add('source_location', '原文位置')
  return rows
})
const warnings = computed(() => [...new Set((source.value.parsing_warnings ?? '').split(',').map(code => code.trim()).filter(Boolean))])
</script>

<template>
  <section class="source-detail" aria-label="来源与解析信息">
    <h4>来源与解析信息</h4>
    <dl v-if="fields.length"><template v-for="field in fields" :key="field.label"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></template></dl>
    <p v-else class="source-muted">此条命中未提供来源定位，可能来自历史版本或仅正文格式。</p>
    <p v-if="source.source_ocr_confidence" class="source-muted">OCR 置信分是引擎评分，不是实测准确率，也不代表整页内容完整。</p>
    <details v-if="warnings.length" open class="source-warnings"><summary>文档解析提示（{{ warnings.length }} 项）</summary><p class="source-muted">这些提示来自整份文档，可能涉及其他页面；请结合原文件核对。</p><ul><li v-for="code in warnings" :key="code">{{ WARNINGS[code] ?? `未识别的解析提示：${code}` }}</li></ul></details>
  </section>
</template>

<style scoped>
.source-detail { margin-top:var(--space-3); padding:var(--space-3); border:1px solid var(--border); border-radius:var(--radius); font-size:var(--fs-sm); }
h4 { font-size:var(--fs-sm); margin:0 0 var(--space-3); }
dl { display:grid; grid-template-columns:minmax(120px,auto) 1fr; gap:var(--space-2) var(--space-4); margin:0; }
dt,.source-muted { color:var(--text-muted); } dd { margin:0; white-space:pre-wrap; overflow-wrap:anywhere; }
.source-muted { line-height:1.6; margin:var(--space-2) 0; }
.source-warnings { margin-top:var(--space-3); background:var(--warning-soft); padding:var(--space-3); border-radius:var(--radius); }
summary { cursor:pointer; font-weight:600; } ul { padding-left:var(--space-5); } li { line-height:1.6; overflow-wrap:anywhere; }
@media(max-width:600px) { dl { grid-template-columns:1fr; } }
</style>
