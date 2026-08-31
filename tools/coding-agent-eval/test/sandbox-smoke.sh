#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
docker build --pull=false -t coding-agent-sandbox-smoke:local \
    -f "$repo_root/tools/coding-agent-eval/sandbox/Dockerfile.smoke" \
    "$repo_root/tools/coding-agent-eval/sandbox"

node --input-type=module - "$repo_root" <<'NODE'
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { runSandboxCommand } from './tools/coding-agent-eval/lib/sandbox.mjs';

const repoRoot = process.argv[2];
const workspace = await mkdtemp(path.join(repoRoot, '.coding-agent-sandbox-smoke-'));
await writeFile(path.join(workspace, 'marker.txt'), 'sandbox fixture\n');
const common = { workspace, cwd: workspace, profile: 'smoke', timeoutMs: 15_000 };

try {
    const readable = await runSandboxCommand(['test', '-f', 'marker.txt'], common);
    assert.equal(readable.exitCode, 0, readable.stderr);
    assert.equal(readable.isolation, 'docker');

    const sourceReadOnly = await runSandboxCommand(['sh', '-c', 'touch /source/coding-agent-must-not-exist'], common);
    assert.notEqual(sourceReadOnly.exitCode, 0, 'source bind unexpectedly allowed writes');

    const nonRoot = await runSandboxCommand(['sh', '-c', 'test "$(id -u)" = 65532'], common);
    assert.equal(nonRoot.exitCode, 0, nonRoot.stderr);

    const noDockerSocket = await runSandboxCommand(['sh', '-c', 'test ! -e /var/run/docker.sock'], common);
    assert.equal(noDockerSocket.exitCode, 0, noDockerSocket.stderr);

    const timeout = await runSandboxCommand(['sh', '-c', 'sleep 30'], { ...common, timeoutMs: 500 });
    assert.equal(timeout.exitCode, 124);
    assert.equal(timeout.timedOut, true);

    console.log('docker sandbox smoke: pass');
} finally {
    await rm(workspace, { recursive: true, force: true });
}
NODE
