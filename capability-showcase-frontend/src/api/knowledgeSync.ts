import type { RunContext } from '../types/api'
import { governanceJson } from './governanceTransport'
export interface SourceStatus {
  sourceId: string; status: string; errorCode: string | null; remaining: number; pendingJobId: string | null
  nextPollAt: number; paused: boolean; controlRevision: number; lastSuccessAt: number | null; activeRunId: string | null; executionUserId: string
}
export interface RecordResult { externalId: string; sourceRevision: number; documentId: string; action: string; status: string; errorCode: string | null; jobId: string | null }
export interface SyncRun {
  id: string; sequence: string; sourceId: string; status: string; triggeredBy: string; executionUserId: string
  startedAt: number; updatedAt: number; finishedAt: number | null; configHash: string; checkpointBeforeHash: string; checkpointAfterHash: string | null
  fetchedCount: number | null; remaining: number; errorCode: string | null; created: number; updated: number; deleted: number; skipped: number; failed: number; legacyConfirmed: number; records: RecordResult[]
}
export interface RunPage { items: SyncRun[]; nextCursor: string | null }
const base = '/rag/ingestions/sources'
const source = (id: string) => `${base}/${encodeURIComponent(id)}`
export const listSources = (ctx: RunContext) => governanceJson<SourceStatus[]>(base, ctx)
export const getSource = (id: string, ctx: RunContext) => governanceJson<SourceStatus>(source(id), ctx)
export const control = (row: SourceStatus, action: 'pause' | 'resume' | 'poll' | 'retry', ctx: RunContext) =>
  governanceJson<SourceStatus>(`${source(row.sourceId)}/${action}`, ctx, 'POST', action === 'pause' || action === 'resume' ? { expectedRevision: row.controlRevision } : undefined)
export const listRuns = (id: string, before: string | null, ctx: RunContext) => governanceJson<RunPage>(`${source(id)}/runs?limit=20${before ? `&before=${encodeURIComponent(before)}` : ''}`, ctx)
export const getRun = (id: string, runId: string, ctx: RunContext) => governanceJson<SyncRun>(`${source(id)}/runs/${encodeURIComponent(runId)}`, ctx)
