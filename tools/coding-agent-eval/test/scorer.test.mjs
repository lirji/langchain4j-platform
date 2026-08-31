import assert from 'node:assert/strict';
import { mkdtemp, mkdir, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { changedFiles, cleanupWorktree, prepareWorktree, repositoryRoot, runCommand } from '../lib/git.mjs';
import { scoreCase } from '../lib/scorer.mjs';

async function createRepository() {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'coding-agent-score-'));
    await runCommand(['git', 'init', '--quiet'], { cwd: directory });
    await runCommand(['git', 'config', 'user.email', 'fixture@example.invalid'], { cwd: directory });
    await runCommand(['git', 'config', 'user.name', 'Fixture'], { cwd: directory });
    await mkdir(path.join(directory, 'src'));
    await writeFile(path.join(directory, 'src', 'main.txt'), 'base\n');
    await runCommand(['git', 'add', 'src/main.txt'], { cwd: directory });
    await runCommand(['git', 'commit', '--quiet', '-m', 'base'], { cwd: directory });
    const root = await repositoryRoot(directory);
    const result = await runCommand(['git', 'rev-parse', 'HEAD'], { cwd: root });
    return { root, baseRef: result.stdout.trim() };
}

function fixtureCase(baseRef, overrides = {}) {
    return {
        id: 'scorer-fixture',
        baseRef,
        oracleRef: baseRef,
        allowedPaths: ['src/**'],
        forbiddenPaths: ['.env', 'secrets/**'],
        verification: [{ name: 'whitespace', command: ['git', 'diff', '--check', baseRef, '--'] }],
        scoring: {
            pathScope: 30,
            verification: 50,
            rubric: 20,
            passThreshold: 80,
            rubricItems: [{ id: 'source', description: 'Source changed', weight: 20, evidencePaths: ['src/**'] }]
        },
        ...overrides
    };
}

test('scores an in-scope verified change as pass', async () => {
    const repo = await createRepository();
    await writeFile(path.join(repo.root, 'src', 'main.txt'), 'fixed\n');
    const report = await scoreCase(fixtureCase(repo.baseRef), repo.root);
    assert.equal(report.verdict, 'pass');
    assert.equal(report.scores.total, 100);
    assert.deepEqual(report.changedFiles, ['src/main.txt']);
});

test('fails a diff containing an out-of-scope file', async () => {
    const repo = await createRepository();
    await writeFile(path.join(repo.root, 'src', 'main.txt'), 'fixed\n');
    await writeFile(path.join(repo.root, 'README.md'), 'unrelated\n');
    const report = await scoreCase(fixtureCase(repo.baseRef), repo.root);
    assert.equal(report.verdict, 'fail');
    assert.deepEqual(report.scope.outsideAllowed, ['README.md']);
});

test('fails when a verification command fails', async () => {
    const repo = await createRepository();
    await writeFile(path.join(repo.root, 'src', 'main.txt'), 'fixed\n');
    const report = await scoreCase(fixtureCase(repo.baseRef, {
        verification: [{ name: 'clean-tree', command: ['git', 'diff', '--exit-code', repo.baseRef, '--'] }]
    }), repo.root);
    assert.equal(report.verdict, 'fail');
    assert.equal(report.checks[0].passed, false);
});

test('missing base ref is reported instead of fetched', async () => {
    const repo = await createRepository();
    await assert.rejects(() => changedFiles(repo.root, 'f'.repeat(40)), /missing git ref/);
});

test('prepare refuses a missing ref without creating a worktree', async () => {
    const repo = await createRepository();
    await assert.rejects(() => prepareWorktree(repo.root, fixtureCase('f'.repeat(40))), /fetch history explicitly/);
});

test('cleanup refuses targets outside its registered temporary namespace', async () => {
    const repo = await createRepository();
    await assert.rejects(() => cleanupWorktree({
        createdBy: 'coding-agent-eval', caseId: 'fixture', sourceRepo: repo.root,
        workspace: path.join(repo.root, 'worktree')
    }), /outside the registered temporary directory/);
});

test('verification cwd may not escape through a symlink', async () => {
    const repo = await createRepository();
    await writeFile(path.join(repo.root, 'src', 'main.txt'), 'fixed\n');
    await symlink(os.tmpdir(), path.join(repo.root, 'outside-link'));
    await assert.rejects(() => scoreCase(fixtureCase(repo.baseRef, {
        allowedPaths: ['src/**', 'outside-link'],
        verification: [{ name: 'escape', command: ['git', 'status'], cwd: 'outside-link' }]
    }), repo.root), /escapes workspace/);
});

test('records the verification runner isolation evidence', async () => {
    const repo = await createRepository();
    await writeFile(path.join(repo.root, 'src', 'main.txt'), 'fixed\n');
    const report = await scoreCase(fixtureCase(repo.baseRef), repo.root, {
        commandRunner: async () => ({ exitCode: 0, durationMs: 1, stdout: '', stderr: '', isolation: 'docker' })
    });
    assert.equal(report.verdict, 'pass');
    assert.equal(report.checks[0].isolation, 'docker');
});
