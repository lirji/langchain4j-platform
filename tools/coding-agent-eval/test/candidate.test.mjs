import assert from 'node:assert/strict';
import test from 'node:test';
import { buildCodexCommand } from '../lib/candidate.mjs';

test('Codex command is ephemeral, model-pinned, config-isolated, and workspace-scoped', () => {
    const command = buildCodexCommand('/tmp/candidate-worktree', 'fixture-model');
    assert.ok(command.includes('--ephemeral'));
    assert.ok(command.includes('--ignore-user-config'));
    assert.ok(command.includes('workspace-write'));
    assert.ok(command.includes('shell_environment_policy.inherit="none"'));
    assert.deepEqual(command.slice(command.indexOf('--model'), command.indexOf('--model') + 2), ['--model', 'fixture-model']);
    assert.deepEqual(command.slice(command.indexOf('-C'), command.indexOf('-C') + 2), ['-C', '/tmp/candidate-worktree']);
    assert.equal(command.includes('--dangerously-bypass-approvals-and-sandbox'), false);
});
