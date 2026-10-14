import type { RunContext } from '../types/api'
import { governanceJson } from './governanceTransport'
export type FeedbackReason = 'NO_ANSWER' | 'INCORRECT' | 'HELPFUL'
export interface FeedbackInput { idempotencyKey: string; question: string; reason: FeedbackReason; comment: string; documentId: string | null }
export interface Feedback { id: string; tenantId: string; userId: string; input: FeedbackInput; createdAt: number }
export interface KnowledgeCheck { question: string; requiredText: string }
export interface KnowledgeDraft { title: string; content: string; category: string | null; checks: KnowledgeCheck[] }
export interface Evidence { kind: string; contentHash: string; total: number; passed: number; checkedAt: number; results: { question: string; passed: boolean; reason: string }[] }
export type CandidateStatus = 'DRAFT' | 'EVALUATED' | 'APPROVED' | 'PUBLISHING' | 'PUBLISH_FAILED' | 'PUBLISHED'
export interface Candidate {
  id: string; tenantId: string; feedbackId: string | null; rollbackOf: string | null; documentId: string
  draft: KnowledgeDraft; status: CandidateStatus; version: number; evaluation: Evidence | null; probe: Evidence | null
  release: { documentVersion: number; reviewer: string; publisher: string | null; jobId: string | null } | null
  createdBy: string; updatedBy: string; createdAt: number; updatedAt: number
}
export interface OperationAudit { id: number; candidateId: string; actor: string; action: string; version: number; occurredAt: number }
export type CandidateAction = 'evaluate' | 'approve' | 'publish' | 'sync' | 'probe'
const base = '/rag/operations'
const item = (id: string) => `${base}/candidates/${encodeURIComponent(id)}`
export const submitFeedback = (input: FeedbackInput, ctx: RunContext) => governanceJson<Feedback>(`${base}/feedback`, ctx, 'POST', input)
export const listFeedback = (inbox: boolean, before: string, ctx: RunContext) => governanceJson<Feedback[]>(`${base}/feedback${inbox ? '/inbox' : ''}?limit=20&before=${encodeURIComponent(before)}`, ctx)
export const listCandidates = (before: string, ctx: RunContext) => governanceJson<Candidate[]>(`${base}/candidates?limit=20&before=${encodeURIComponent(before)}`, ctx)
export const getCandidate = (id: string, ctx: RunContext) => governanceJson<Candidate>(item(id), ctx)
export const createCandidate = (idempotencyKey: string, feedbackId: string | null, draft: KnowledgeDraft, ctx: RunContext) => governanceJson<Candidate>(`${base}/candidates`, ctx, 'POST', { idempotencyKey, feedbackId, draft })
export const editCandidate = (id: string, expectedVersion: number, draft: KnowledgeDraft, ctx: RunContext) => governanceJson<Candidate>(item(id), ctx, 'PUT', { expectedVersion, draft })
export const actOnCandidate = (row: Candidate, action: CandidateAction, ctx: RunContext) => governanceJson<Candidate>(`${item(row.id)}/${action}`, ctx, 'POST', { expectedVersion: row.version })
export const rollbackCandidate = (id: string, idempotencyKey: string, ctx: RunContext) => governanceJson<Candidate>(`${item(id)}/rollback-draft`, ctx, 'POST', { idempotencyKey })
export const candidateHistory = (id: string, ctx: RunContext) => governanceJson<OperationAudit[]>(`${item(id)}/history?limit=50`, ctx)
export const candidateFeedback = (id: string, ctx: RunContext) => governanceJson<Partial<Record<FeedbackReason, number>>>(`${item(id)}/feedback-summary`, ctx)
