<script setup lang="ts">
import type { DocumentInfo } from '../../types/knowledge'
import { formatBytes } from '../../utils/json'
import { knowledgeDate } from './knowledgePresentation'
defineProps<{ document: DocumentInfo }>()
</script>
<template>
  <dl class="document-facts">
    <dt>文档名称</dt><dd>{{ document.displayName || '未提供' }}</dd>
    <dt>文档 ID</dt><dd>{{ document.docId }}</dd>
    <dt>已发布版本</dt><dd>{{ document.version }}</dd>
    <dt>文件类型</dt><dd>{{ document.contentType || '未提供' }}</dd>
    <dt>文件大小</dt><dd>{{ document.sizeBytes >= 0 ? formatBytes(document.sizeBytes) : '未提供' }}</dd>
    <dt>片段数</dt><dd>{{ document.segmentCount }}</dd>
    <dt>类目</dt><dd>{{ document.category || '未分类' }}</dd>
    <dt>上传时间（北京时间）</dt><dd>{{ knowledgeDate(document.uploadedAt) }}</dd>
  </dl>
  <p class="document-note">页码、表格行和解析提示随检索命中返回，可在“检索台”查看；此详情不提供完整解析报告。</p>
</template>
<style scoped>
.document-facts { display:grid; grid-template-columns:minmax(120px,auto) 1fr; gap:var(--space-3); font-size:var(--fs-sm); }
dt,.document-note { color:var(--text-muted); } dd { margin:0; overflow-wrap:anywhere; }.document-note { font-size:var(--fs-sm); line-height:1.6; }
@media(max-width:600px) { .document-facts { grid-template-columns:1fr; } }
</style>
