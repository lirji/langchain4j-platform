import type { RunContext } from '../types/api'
import { governanceJson } from './governanceTransport'
export interface Target { visibility: 'tenant' | 'public'; docId: string }
export interface QualityCase { id: string; question: string; relevant: Target[]; feedbackId: string | null }
export interface Settings { topK: number; minScore: number; category: string | null; allowedDrop: number; allowedP95IncreaseMs: number }
export interface Definition { name: string; cases: QualityCase[]; settings: Settings }
export interface Dataset { id: string; revision: number; definition: Definition; baselineRunId: string | null; updatedBy: string; updatedAt: number }
export interface CaseResult { caseId: string; retrieved: { document: Target; version: string | null }[]; recall: number; precision: number; mrr: number; hit: boolean; latencyMs: number }
export interface Metrics { recall: number; precision: number; mrr: number; hitRate: number; p50Ms: number; p95Ms: number }
export interface QualityRun {
  id: string; datasetId: string; datasetHash: string; actor: string; snapshot: Definition; datasetRevision: number; label: string
  baselineRunId: string | null; status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'; startedAt: number; deadlineAt: number; finishedAt: number | null
  results: CaseResult[]; metrics: Metrics | null; failure: string | null
  gate: { verdict: 'BASELINE_REQUIRED' | 'PASS' | 'REGRESSION' | 'FAILED'; reasons: string[]; recallDelta: number | null; precisionDelta: number | null; mrrDelta: number | null; hitRateDelta: number | null; p95DeltaMs: number | null } | null
}
const base = '/rag/operations/quality'
const dataset = (id: string) => `${base}/datasets/${encodeURIComponent(id)}`
export const listDatasets = (before: string, ctx: RunContext) => governanceJson<Dataset[]>(`${base}/datasets?before=${encodeURIComponent(before)}&limit=20`, ctx)
export const getDataset = (id: string, ctx: RunContext) => governanceJson<Dataset>(dataset(id), ctx)
export const createDataset = (idempotencyKey: string, definition: Definition, ctx: RunContext) => governanceJson<Dataset>(`${base}/datasets`, ctx, 'POST', { idempotencyKey, definition })
export const editDataset = (row: Dataset, definition: Definition, ctx: RunContext) => governanceJson<Dataset>(dataset(row.id), ctx, 'PUT', { expectedRevision: row.revision, definition })
export const selectBaseline = (row: Dataset, runId: string, ctx: RunContext) => governanceJson<Dataset>(`${dataset(row.id)}/baseline`, ctx, 'POST', { expectedRevision: row.revision, runId })
export const execute = (row: Dataset, idempotencyKey: string, label: string, ctx: RunContext) => governanceJson<QualityRun>(`${dataset(row.id)}/runs`, ctx, 'POST', { expectedRevision: row.revision, idempotencyKey, label })
export const listRuns = (id: string, before: string, ctx: RunContext) => governanceJson<QualityRun[]>(`${dataset(id)}/runs?before=${encodeURIComponent(before)}&limit=20`, ctx)
export const getRun = (id: string, ctx: RunContext) => governanceJson<QualityRun>(`${base}/runs/${encodeURIComponent(id)}`, ctx)
export const abandonRun = (id: string, ctx: RunContext) => governanceJson<QualityRun>(`${base}/runs/${encodeURIComponent(id)}/abandon`, ctx, 'POST')
