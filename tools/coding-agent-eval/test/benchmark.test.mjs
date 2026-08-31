import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { createBenchmarkPlan, generateBenchmarkReport, initializeBenchmarkRun, loadBenchmarkRun, runBenchmark } from '../lib/benchmark.mjs';
import { runCommand } from '../lib/git.mjs';
import { computeDatasetDigest, loadDataset, SCHEMA_VERSION, TOOL_VERSION } from '../lib/manifest.mjs';

async function fixture() {
    const root = await mkdtemp(path.join(os.tmpdir(), 'coding-agent-benchmark-'));
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
    const base = (await runCommand(['git', 'rev-parse', 'HEAD'], { cwd: repository })).stdout.trim();
    await writeFile(path.join(repository, 'src', 'main.txt'), 'oracle\n');
    await runCommand(['git', 'add', 'src/main.txt'], { cwd: repository });
    await runCommand(['git', 'commit', '--quiet', '-m', 'oracle'], { cwd: repository });
    const oracle = (await runCommand(['git', 'rev-parse', 'HEAD'], { cwd: repository })).stdout.trim();
    const entries = [];
    for (let index = 0; index < 20; index += 1) {
        const id = `benchmark-case-${String(index).padStart(2, '0')}`;
        const data = {
            schemaVersion: SCHEMA_VERSION,
            id,
            kind: 'bug',
            title: `Benchmark fixture ${index}`,
            baseRef: base,
            oracleRef: oracle,
            prompt: 'Change the source to the expected behavior.',
            riskTags: ['fixture'],
            allowedPaths: ['src/**'],
            forbiddenPaths: ['.env'],
            verification: [{ name: 'diff', command: ['git', 'diff', '--check', base, '--'] }],
            scoring: {
                pathScope: 30, verification: 50, rubric: 20, passThreshold: 80,
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
        datasetId: 'benchmark-fixture', version: '1.0.0', toolVersion: TOOL_VERSION,
        digest: computeDatasetDigest(entries), cases: entries.map((entry) => entry.relativePath)
    }, null, 2)}\n`);
    return { root, repository, dataset: await loadDataset(manifestPath) };
}

test('oracle benchmark checkpoints and resumes without rerunning completed cases', async () => {
    const data = await fixture();
    const plan = await createBenchmarkPlan(data.dataset, {
        candidate: 'oracle', isolation: 'host', caseLimit: 2, timeoutSeconds: 10,
        sourceRepo: data.repository, runId: 'oracle-resume-test'
    });
    const runDirectory = path.join(data.root, 'run');
    await initializeBenchmarkRun(runDirectory, plan);
    const first = await runBenchmark(data.dataset, runDirectory, { maxCases: 1 });
    assert.equal(first.overallStatus, 'stopped');
    assert.equal(first.cases['benchmark-case-00'].status, 'pass');
    const resumed = await runBenchmark(data.dataset, runDirectory);
    assert.equal(resumed.overallStatus, 'complete');
    assert.equal(resumed.cases['benchmark-case-00'].attempts, 1);
    assert.equal(resumed.cases['benchmark-case-01'].status, 'pass');
    const summary = await generateBenchmarkReport(data.dataset, runDirectory);
    assert.equal(summary.counts.pass, 2);
    assert.equal(summary.metrics.passRate, 100);
});

test('plan tampering and dataset drift are rejected', async () => {
    const data = await fixture();
    const plan = await createBenchmarkPlan(data.dataset, {
        candidate: 'oracle', isolation: 'host', caseLimit: 1, timeoutSeconds: 10,
        sourceRepo: data.repository, runId: 'plan-drift-test'
    });
    const runDirectory = path.join(data.root, 'drift-run');
    await initializeBenchmarkRun(runDirectory, plan);
    const file = path.join(runDirectory, 'PLAN.json');
    const tampered = JSON.parse(await readFile(file, 'utf8'));
    tampered.workflowVersion = 'tampered';
    await writeFile(file, `${JSON.stringify(tampered, null, 2)}\n`);
    await assert.rejects(() => loadBenchmarkRun(runDirectory, data.dataset), /plan digest mismatch/);
});

test('codex runs require explicit model execution authorization', async () => {
    const data = await fixture();
    const plan = await createBenchmarkPlan(data.dataset, {
        candidate: 'codex', model: 'fixture-model', isolation: 'host', caseLimit: 1, timeoutSeconds: 10,
        sourceRepo: data.repository, runId: 'authorization-test'
    });
    const runDirectory = path.join(data.root, 'auth-run');
    await initializeBenchmarkRun(runDirectory, plan);
    await assert.rejects(() => runBenchmark(data.dataset, runDirectory), /allow-model-execution/);
});

test('three consecutive candidate infrastructure errors stop the run', async () => {
    const data = await fixture();
    const plan = await createBenchmarkPlan(data.dataset, {
        candidate: 'codex', model: 'fixture-model', isolation: 'host', caseLimit: 5, timeoutSeconds: 10,
        sourceRepo: data.repository, runId: 'infra-stop-test'
    });
    const runDirectory = path.join(data.root, 'infra-run');
    await initializeBenchmarkRun(runDirectory, plan);
    const checkpoint = await runBenchmark(data.dataset, runDirectory, {
        allowModelExecution: true,
        candidateRunner: async () => ({
            status: 'infra_error', exitCode: 1, durationMs: 1,
            tokens: { inputTokens: null, outputTokens: null }, cost: null, errorSummary: 'network unavailable'
        })
    });
    assert.equal(checkpoint.overallStatus, 'stopped');
    assert.equal(checkpoint.consecutiveInfraErrors, 3);
    assert.equal(Object.values(checkpoint.cases).filter((entry) => entry.status === 'infra_error').length, 3);
    assert.equal(Object.values(checkpoint.cases).filter((entry) => entry.status === 'planned').length, 2);
});

test('candidate timeout is a terminal status distinct from infrastructure error', async () => {
    const data = await fixture();
    const plan = await createBenchmarkPlan(data.dataset, {
        candidate: 'codex', model: 'fixture-model', isolation: 'host', caseLimit: 1, timeoutSeconds: 10,
        sourceRepo: data.repository, runId: 'timeout-test'
    });
    const runDirectory = path.join(data.root, 'timeout-run');
    await initializeBenchmarkRun(runDirectory, plan);
    const checkpoint = await runBenchmark(data.dataset, runDirectory, {
        allowModelExecution: true,
        candidateRunner: async () => ({
            status: 'timeout', exitCode: null, durationMs: 10_000,
            tokens: { inputTokens: null, outputTokens: null }, cost: null, errorSummary: 'timed out'
        })
    });
    assert.equal(checkpoint.overallStatus, 'complete');
    assert.equal(checkpoint.cases['benchmark-case-00'].status, 'timeout');
    assert.equal(checkpoint.consecutiveInfraErrors, 0);
});

test('codex plans require a pinned model and persist running state before invocation', async () => {
    const data = await fixture();
    await assert.rejects(() => createBenchmarkPlan(data.dataset, {
        candidate: 'codex', isolation: 'host', caseLimit: 1, timeoutSeconds: 10,
        sourceRepo: data.repository, runId: 'unpinned-model-test'
    }), /pin --model/);
    const plan = await createBenchmarkPlan(data.dataset, {
        candidate: 'codex', model: 'fixture-model', isolation: 'host', caseLimit: 1, timeoutSeconds: 10,
        sourceRepo: data.repository, runId: 'running-checkpoint-test'
    });
    const runDirectory = path.join(data.root, 'running-checkpoint-run');
    await initializeBenchmarkRun(runDirectory, plan);
    let observed = false;
    await runBenchmark(data.dataset, runDirectory, {
        allowModelExecution: true,
        candidateRunner: async () => {
            const checkpoint = JSON.parse(await readFile(path.join(runDirectory, 'checkpoint.json'), 'utf8'));
            observed = checkpoint.cases['benchmark-case-00'].status === 'running'
                && checkpoint.cases['benchmark-case-00'].attempts === 1;
            return { status: 'completed', exitCode: 0, durationMs: 1, tokens: null, cost: null };
        }
    });
    assert.equal(observed, true);
});
