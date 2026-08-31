import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { buildDockerArgs, runSandboxCommand } from '../lib/sandbox.mjs';

test('docker contract is networkless, read-only, non-root, resource-bounded, and pull-free', () => {
    const args = buildDockerArgs({
        name: 'contract-test', workspace: '/safe/worktree', relativeCwd: '.',
        image: 'fixture:local', command: ['test', '-f', 'README.md'],
        entrypointPath: '/safe/entrypoint.sh'
    });
    const text = args.join(' ');
    assert.match(text, /--pull=never/);
    assert.match(text, /--network none/);
    assert.match(text, /--read-only/);
    assert.match(text, /--cap-drop ALL/);
    assert.match(text, /no-new-privileges/);
    assert.match(text, /--user 65532:65532/);
    assert.match(text, /\/safe\/worktree:\/source:ro/);
    assert.doesNotMatch(text, /docker\.sock|\.ssh|\.aws|\.env/);
    assert.equal(args.at(-4), 'fixture:local');
});

test('absent image fails closed without running on the host', async () => {
    const workspace = await mkdtemp(path.join(os.tmpdir(), 'coding-agent-sandbox-missing-'));
    await writeFile(path.join(workspace, 'marker'), 'source\n');
    const profiles = {
        schemaVersion: 'coding-agent-sandbox-profiles/v1',
        profiles: { missing: { image: 'coding-agent-definitely-absent:never', executables: ['test'] } }
    };
    await assert.rejects(() => runSandboxCommand(['test', '-f', 'marker'], {
        workspace, cwd: workspace, profiles, profile: 'missing', timeoutMs: 5_000,
        imageChecker: async () => false
    }), /absent; pulls and host fallback are disabled/);
});
