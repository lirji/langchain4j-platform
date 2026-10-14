import type { RunContext } from '../types/api'
import { governanceJson } from './governanceTransport'
export interface MemoryEntry {
  recordId: number; id: string; text: string; type: string | null; createdAtEpochMs: number
  sourceChatId: string | null; state: 'PENDING' | 'ACTIVE'; version: number; pinned: boolean; expiresAtEpochMs: number | null
}
export interface MemorySnapshot { enabled: boolean; generation: number; items: MemoryEntry[] }
export interface MemoryAudit { auditId: number; recordId: number; action: string; version: number; occurredAtEpochMs: number }
export interface MemoryDecision {
  expectedVersion: number; action: 'APPROVE' | 'REJECT' | 'FORGET' | 'UPDATE' | 'PIN' | 'UNPIN'
  text?: string; type?: string | null; expiresAtEpochMs?: number | null
}
export const fetchMemory = (ctx: RunContext) => governanceJson<MemorySnapshot>('/memory/governance', ctx)
export const decideMemory = (id: number, body: MemoryDecision, ctx: RunContext) =>
  governanceJson<void>(`/memory/governance/${id}/decision`, ctx, 'POST', body)
export const setMemoryEnabled = (enabled: boolean, expectedGeneration: number, ctx: RunContext) =>
  governanceJson<void>('/memory/governance/settings', ctx, 'PUT', { enabled, expectedGeneration })
export const fetchMemoryAudit = (ctx: RunContext) => governanceJson<MemoryAudit[]>('/memory/governance/audit?limit=50', ctx)
