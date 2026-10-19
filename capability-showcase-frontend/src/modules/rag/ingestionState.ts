import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import type { DocumentInfo, IngestionDuplicateNotice, IngestionJobView, IngestionSubmission } from '../../types/knowledge'
import { useKnowledgeScope } from './useKnowledgeScope'
import { TRACKED_JOB_LIMIT } from './knowledgePresentation'

/** 本会话保存提交身份及回执，路由切换不生成新幂等键；文件及凭证不写本地存储。 */
export const useIngestionState = defineStore('knowledge-ingestion', () => {
  const scope = useKnowledgeScope()
  const documentId = ref('')
  const documentVersion = ref(1)
  const displayName = ref('')
  const category = ref('')
  const recoveryKey = ref('')
  const file = ref<File | null>(null)
  const attempt = ref<IngestionSubmission | null>(null)
  const phase = ref<'idle' | 'submitting' | 'uncertain' | 'accepted' | 'rejected' | 'duplicate'>('idle')
  const duplicate = ref<IngestionDuplicateNotice | null>(null)
  const jobs = ref<IngestionJobView[]>([])
  const selectedId = ref('')
  const submitError = ref('')
  const uncertainRetries = ref<string[]>([])
  const locked = computed(() => ['submitting', 'uncertain', 'accepted', 'duplicate'].includes(phase.value))

  function newDocument(document?: DocumentInfo) {
    if (phase.value === 'submitting' || phase.value === 'uncertain') return false
    documentId.value = document?.docId ?? `doc-${crypto.randomUUID()}`
    documentVersion.value = document ? document.version + 1 : 1
    displayName.value = document?.displayName ?? ''
    category.value = document?.category ?? ''
    recoveryKey.value = ''
    file.value = null
    attempt.value = null
    phase.value = 'idle'
    submitError.value = ''
    duplicate.value = null
    return true
  }
  /** 重复文件换选时保留新建/更新目标，不能把“更新版本”意图悄悄变成新文档。 */
  function changeDuplicateFile() {
    if (phase.value !== 'duplicate') return
    duplicate.value = null
    file.value = null
    attempt.value = null
    recoveryKey.value = ''
    phase.value = 'idle'
  }
  function command(): IngestionSubmission {
    const current = {
      documentId: documentId.value.trim(), documentVersion: documentVersion.value,
      displayName: displayName.value.trim() || file.value!.name,
      category: category.value.trim(), file: file.value!,
    }
    const old = attempt.value
    if (!old || old.documentId !== current.documentId || old.documentVersion !== current.documentVersion
      || old.displayName !== current.displayName || old.category !== current.category || old.file !== current.file
      || (recoveryKey.value.trim() && recoveryKey.value.trim() !== old.idempotencyKey)) {
      attempt.value = { ...current, idempotencyKey: recoveryKey.value.trim() || `ingest-${crypto.randomUUID()}` }
    }
    return attempt.value!
  }
  function upsert(job: IngestionJobView) {
    const index = jobs.value.findIndex(row => row.jobId === job.jobId)
    if (index >= 0) jobs.value[index] = job
    else jobs.value = [job, ...jobs.value].slice(0, TRACKED_JOB_LIMIT)
    if (!selectedId.value) selectedId.value = job.jobId
  }
  watch(scope, () => {
    // 身份切换时连同未知结果一起隔离；迟到回执由组件的 scope fence 丢弃。
    phase.value = 'idle'
    newDocument()
    jobs.value = []
    selectedId.value = ''
    uncertainRetries.value = []
  }, { immediate: true, flush: 'sync' })
  return { documentId, documentVersion, displayName, category, recoveryKey, file, attempt, phase, jobs, selectedId,
    submitError, duplicate, uncertainRetries, locked, newDocument, changeDuplicateFile, command, upsert }
})
