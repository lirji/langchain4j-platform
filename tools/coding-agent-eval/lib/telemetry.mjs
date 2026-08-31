import { createHash } from 'node:crypto';
import { appendFile, readFile } from 'node:fs/promises';
import os from 'node:os';
import { stableStringify, ValidationError } from './manifest.mjs';

export const EVENT_SCHEMA = 'coding-agent-event/v1';
const GENESIS_DIGEST = `sha256:${'0'.repeat(64)}`;
const SECRET_KEY = /(?:authorization|api[-_]?key|access[-_]?token|refresh[-_]?token|password|secret|credential)/i;
const SECRET_VALUE = /(?:sk-[A-Za-z0-9_-]{16,}|Bearer\s+[A-Za-z0-9._~+/-]{12,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{12,}|AIza[A-Za-z0-9_-]{20,}|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,})/g;
const INLINE_SECRET = /\b(?:authorization|api[-_]?key|access[-_]?token|refresh[-_]?token|password|secret|credential)\s*[:=]\s*[^\s,;]+/gi;

export function sha256(value) {
    return `sha256:${createHash('sha256').update(value).digest('hex')}`;
}

export function sanitizeTelemetry(value, key = '') {
    if (SECRET_KEY.test(key)) return '[REDACTED]';
    if (typeof value === 'string') {
        const home = os.homedir();
        return value.replaceAll(home, '<HOME>').replace(SECRET_VALUE, '[REDACTED]').replace(INLINE_SECRET, '[REDACTED]');
    }
    if (Array.isArray(value)) return value.map((entry) => sanitizeTelemetry(entry));
    if (value && typeof value === 'object') {
        return Object.fromEntries(Object.entries(value).map(([childKey, child]) => [childKey, sanitizeTelemetry(child, childKey)]));
    }
    return value;
}

export function createEvent(previous, event) {
    const sequence = previous ? previous.sequence + 1 : 1;
    const previousDigest = previous?.eventDigest ?? GENESIS_DIGEST;
    const payload = sanitizeTelemetry({
        schemaVersion: EVENT_SCHEMA,
        sequence,
        previousDigest,
        timestamp: event.timestamp ?? new Date().toISOString(),
        runId: event.runId,
        caseId: event.caseId ?? null,
        phase: event.phase,
        status: event.status,
        durationMs: event.durationMs ?? null,
        exitCode: event.exitCode ?? null,
        score: event.score ?? null,
        isolation: event.isolation ?? null,
        tokens: event.tokens ?? null,
        cost: event.cost ?? null,
        details: event.details ?? null
    });
    return { ...payload, eventDigest: sha256(stableStringify(payload)) };
}

export async function readEvents(file) {
    try {
        const content = await readFile(file, 'utf8');
        return content.split('\n').filter(Boolean).map((line, index) => {
            try { return JSON.parse(line); } catch (error) { throw new ValidationError(`invalid event JSON at line ${index + 1}: ${error.message}`); }
        });
    } catch (error) {
        if (error.code === 'ENOENT') return [];
        throw error;
    }
}

export async function appendEvent(file, event) {
    const events = await readEvents(file);
    if (events.length > 0) verifyEventChain(events);
    const next = createEvent(events.at(-1), event);
    await appendFile(file, `${JSON.stringify(next)}\n`, { flag: 'a' });
    return next;
}

export function verifyEventChain(events) {
    let previous = null;
    for (const [index, event] of events.entries()) {
        if (event.schemaVersion !== EVENT_SCHEMA) throw new ValidationError(`event ${index + 1} has incompatible schema`);
        if (event.sequence !== index + 1) throw new ValidationError(`event ${index + 1} has invalid sequence`);
        const expectedPrevious = previous?.eventDigest ?? GENESIS_DIGEST;
        if (event.previousDigest !== expectedPrevious) throw new ValidationError(`event ${index + 1} breaks the digest chain`);
        const { eventDigest, ...payload } = event;
        if (eventDigest !== sha256(stableStringify(payload))) throw new ValidationError(`event ${index + 1} digest mismatch`);
        previous = event;
    }
    return { valid: true, events: events.length, finalDigest: previous?.eventDigest ?? GENESIS_DIGEST };
}
