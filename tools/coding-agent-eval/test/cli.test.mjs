import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { runCommand } from '../lib/git.mjs';
import { computeDatasetDigest, SCHEMA_VERSION, TOOL_VERSION } from '../lib/manifest.mjs';

const toolRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = path.resolve(toolRoot, '..', '..');
const cli = path.join(toolRoot, 'cli.mjs');
const manifest = path.join(repoRoot, 'docs', 'qa', 'coding-agent-golden', 'manifest.json');

async function createCliFixture({ missingRef = false } = {}) {
    const root = await mkdtemp(path.join(os.tmpdir(), 'coding-agent-cli-'));
    const repository = path.join(root, 'repo');
    const datasetRoot = path.join(root, 'dataset');
    await mkdir(repository);
    await mkdir(path.join(repository, 'src'));
    await mkdir(path.join(datasetRoot, 'cases'), { recursive: true });
    await runCommand(['git', 'init', '--quiet'], { cwd: repository });
    await runCommand(['git', 'config', 'user.email', 'fixture@example.invalid'], { cwd: repository });
    await runCommand(['git', 'config', 'user.name', 'Fixture'], { cwd: repository });
    await writeFile(path.join(repository, 'src', 'main.txt'), 'base\n');
    await runCommand(['git', 'add', 'src/main.txt'], { cwd: repository });
    await runCommand(['git', 'commit', '--quiet', '-m', 'base'], { cwd: repository });
    const head = await runCommand(['git', 'rev-parse', 'HEAD'], { cwd: repository });
    const baseRef = missingRef ? 'f'.repeat(40) : head.stdout.trim();
    const entries = [];
    for (let index = 0; index < 20; index += 1) {
        const id = `fixture-case-${String(index).padStart(2, '0')}`;
        const data = {
            schemaVersion: SCHEMA_VERSION,
            id,
            kind: 'bug',
            title: `Fixture ${index}`,
            baseRef,
            oracleRef: baseRef,
            prompt: 'Change the fixture source.',
            riskTags: ['fixture'],
            allowedPaths: ['src/**'],
            forbiddenPaths: ['.env'],
            verification: [{ name: 'clean-tree', command: ['git', 'diff', '--exit-code', baseRef, '--'] }],
            scoring: {
                pathScope: 30,
                verification: 50,
                rubric: 20,
                passThreshold: 80,
                rubricItems: [{ id: 'source', description: 'Source changed', weight: 20, evidencePaths: ['src/**'] }]
            }
        };
        const relativePath = `cases/${id}.json`;
        await writeFile(path.join(datasetRoot, relativePath), `${JSON.stringify(data, null, 2)}\n`);
        entries.push({ relativePath, data });
    }
    const manifestPath = path.join(datasetRoot, 'manifest.json');
    await writeFile(manifestPath, `${JSON.stringify({
        schemaVersion: SCHEMA_VERSION,
        datasetId: 'cli-fixture-dataset',
        version: '1.0.0',
        toolVersion: TOOL_VERSION,
        digest: computeDatasetDigest(entries),
        cases: entries.map((entry) => entry.relativePath)
    }, null, 2)}\n`);
    return { repository, manifestPath };
}

test('validate and list commands work against the checked-in dataset', async () => {
    const validated = await runCommand(['node', cli, 'validate', '--manifest', manifest], { cwd: repoRoot });
    assert.equal(validated.exitCode, 0, validated.stderr);
    assert.equal(JSON.parse(validated.stdout).cases, 50);

    const listed = await runCommand(['node', cli, 'list', '--manifest', manifest, '--kind', 'security', '--json'], { cwd: repoRoot });
    assert.equal(listed.exitCode, 0, listed.stderr);
    assert.equal(JSON.parse(listed.stdout).length, 4);
});

test('audit verifies all checked-in history references and oracle scopes', async () => {
    const audited = await runCommand(['node', cli, 'audit', '--manifest', manifest, '--repo', repoRoot], { cwd: repoRoot });
    assert.equal(audited.exitCode, 0, audited.stderr);
    const report = JSON.parse(audited.stdout);
    assert.equal(report.cases, 50);
    assert.deepEqual(report.tiers, { core: 20, extended: 30 });
    assert.equal(Object.keys(report.kinds).length, 7);
});

test('invalid command input returns exit code 2', async () => {
    const result = await runCommand(['node', cli, 'list', '--manifest', manifest, '--unknown', 'value'], { cwd: repoRoot });
    assert.equal(result.exitCode, 2);
    assert.match(result.stderr, /unknown argument/);
});

test('a failed score returns exit code 4', async () => {
    const fixture = await createCliFixture();
    await writeFile(path.join(fixture.repository, 'src', 'main.txt'), 'changed\n');
    const result = await runCommand([
        'node', cli, 'score', '--manifest', fixture.manifestPath,
        '--case', 'fixture-case-00', '--workspace', fixture.repository
    ], { cwd: repoRoot });
    assert.equal(result.exitCode, 4, result.stderr);
    assert.equal(JSON.parse(result.stdout).verdict, 'fail');
});

test('prepare returns exit code 3 when history is missing', async () => {
    const fixture = await createCliFixture({ missingRef: true });
    const result = await runCommand([
        'node', cli, 'prepare', '--manifest', fixture.manifestPath,
        '--case', 'fixture-case-00', '--repo', fixture.repository
    ], { cwd: repoRoot });
    assert.equal(result.exitCode, 3);
    assert.match(result.stderr, /fetch history explicitly/);
});

test('report aggregates score files and ignores unrelated JSON', async () => {
    const input = await mkdtemp(path.join(os.tmpdir(), 'coding-agent-report-'));
    const datasetDigest = JSON.parse(await readFile(manifest, 'utf8')).digest;
    await writeFile(path.join(input, 'pass.json'), JSON.stringify({
        schemaVersion: 'coding-agent-score/v1',
        toolVersion: '1.0.0',
        dataset: { digest: datasetDigest },
        caseId: 'agent-json-mode',
        verdict: 'pass',
        scores: { total: 90 }
    }));
    await writeFile(path.join(input, 'metadata.json'), JSON.stringify({ schemaVersion: 'something-else' }));
    const result = await runCommand(['node', cli, 'report', '--manifest', manifest, '--input', input], { cwd: repoRoot });
    assert.equal(result.exitCode, 0, result.stderr);
    const report = JSON.parse(result.stdout);
    assert.deepEqual(report.summary, { evaluated: 1, passed: 1, failed: 0, passRate: 100, averageScore: 90 });
});

test('report rejects a score from another dataset digest', async () => {
    const input = await mkdtemp(path.join(os.tmpdir(), 'coding-agent-stale-report-'));
    await writeFile(path.join(input, 'stale.json'), JSON.stringify({
        schemaVersion: 'coding-agent-score/v1',
        toolVersion: '1.0.0',
        dataset: { digest: `sha256:${'0'.repeat(64)}` },
        caseId: 'agent-json-mode',
        verdict: 'pass',
        scores: { total: 100 }
    }));
    const result = await runCommand(['node', cli, 'report', '--manifest', manifest, '--input', input], { cwd: repoRoot });
    assert.equal(result.exitCode, 2);
    assert.match(result.stderr, /does not match dataset digest/);
});
