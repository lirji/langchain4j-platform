import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { appendEvent, readEvents, sanitizeTelemetry, verifyEventChain } from '../lib/telemetry.mjs';

test('events form a verifiable digest chain', async () => {
    const root = await mkdtemp(path.join(os.tmpdir(), 'coding-agent-events-'));
    const file = path.join(root, 'events.jsonl');
    await appendEvent(file, { runId: 'run-one', phase: 'plan', status: 'planned' });
    await appendEvent(file, { runId: 'run-one', caseId: 'case-one', phase: 'case', status: 'pass', score: 100 });
    const events = await readEvents(file);
    assert.deepEqual(verifyEventChain(events).events, 2);
    assert.equal(events[1].previousDigest, events[0].eventDigest);
});

test('tampered events fail closed', async () => {
    const root = await mkdtemp(path.join(os.tmpdir(), 'coding-agent-events-tamper-'));
    const file = path.join(root, 'events.jsonl');
    await appendEvent(file, { runId: 'run-two', phase: 'plan', status: 'planned' });
    const event = JSON.parse((await readFile(file, 'utf8')).trim());
    event.status = 'pass';
    await writeFile(file, `${JSON.stringify(event)}\n`);
    assert.throws(() => verifyEventChain([event]), /digest mismatch/);
});

test('telemetry redacts secret fields, bearer values, and home paths', () => {
    const sanitized = sanitizeTelemetry({
        apiKey: 'secret-value',
        message: `Bearer abcdefghijklmnopqrstuvwxyz at ${os.homedir()}/repo`,
        command: 'password=hunter2 github_pat_abcdefghijklmnopqrstuvwxyz123456'
    });
    assert.equal(sanitized.apiKey, '[REDACTED]');
    assert.doesNotMatch(sanitized.message, /abcdefghijklmnopqrstuvwxyz/);
    assert.doesNotMatch(sanitized.message, new RegExp(os.homedir().replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
    assert.doesNotMatch(sanitized.command, /hunter2|github_pat_/);
});
