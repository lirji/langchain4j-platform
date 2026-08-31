import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { cleanupWorktree, CommandError, prepareWorktree, repositoryRoot } from './git.mjs';
import { findCase, stableStringify, ValidationError } from './manifest.mjs';
import { scoreCase } from './scorer.mjs';
import { runCodexCandidate } from './candidate.mjs';
import { createVerificationRunner } from './sandbox.mjs';
import { appendEvent, readEvents, sanitizeTelemetry, sha256, verifyEventChain } from './telemetry.mjs';

export const BENCHMARK_PLAN_SCHEMA = 'coding-agent-benchmark-plan/v1';
export const CHECKPOINT_SCHEMA = 'coding-agent-benchmark-checkpoint/v1';
const TERMINAL = new Set(['pass', 'fail', 'blocked', 'timeout', 'infra_error']);

function safeInteger(value, label, minimum, maximum) {
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
        throw new ValidationError(`${label} must be an integer from ${minimum} to ${maximum}`);
    }
    return parsed;
}

function planDigest(plan) {
    const { planDigest: ignored, ...payload } = plan;
    return sha256(stableStringify(payload));
}

export async function createBenchmarkPlan(dataset, options) {
    if (!['oracle', 'codex'].includes(options.candidate)) throw new ValidationError('candidate must be oracle or codex');
    if (options.candidate === 'codex' && (typeof options.model !== 'string' || options.model.trim() === '')) {
        throw new ValidationError('codex benchmark plans must pin --model explicitly');
    }
    if (!['host', 'docker'].includes(options.isolation)) throw new ValidationError('isolation must be host or docker');
    const limit = safeInteger(options.caseLimit ?? 20, 'case limit', 1, dataset.cases.length);
    const timeoutSeconds = safeInteger(options.timeoutSeconds ?? 480, 'timeout seconds', 5, 3600);
    const core = dataset.cases.filter((entry) => (entry.data.tier ?? 'core') === 'core');
    const source = core.length >= limit ? core : dataset.cases;
    const cases = source.slice(0, limit).map((entry) => entry.data.id);
    const sourceRepo = await repositoryRoot(options.sourceRepo ?? process.cwd());
    const createdAt = new Date().toISOString();
    const runId = options.runId ?? `coding-agent-${options.candidate}-${createdAt.replace(/[-:.TZ]/g, '').slice(0, 14)}`;
    if (!/^[a-z0-9][a-z0-9-]{2,79}$/.test(runId)) throw new ValidationError('run id must be lowercase kebab-case');
    const plan = {
        schemaVersion: BENCHMARK_PLAN_SCHEMA,
        runId,
        createdAt,
        dataset: { id: dataset.manifest.datasetId, version: dataset.manifest.version, digest: dataset.manifest.digest },
        toolVersion: dataset.manifest.toolVersion,
        candidate: options.candidate,
        model: options.model ?? null,
        workflowVersion: options.workflowVersion ?? 'p1-p2-v1',
        isolation: options.isolation,
        timeoutSeconds,
        maxConsecutiveInfraErrors: 3,
        sourceRepo,
        cases
    };
    return { ...plan, planDigest: planDigest(plan) };
}

export async function initializeBenchmarkRun(runDirectory, plan) {
    const root = path.resolve(runDirectory);
    await mkdir(root);
    await mkdir(path.join(root, 'reports'));
    await mkdir(path.join(root, 'raw'));
    await writeFile(path.join(root, 'PLAN.json'), `${JSON.stringify(plan, null, 2)}\n`, { flag: 'wx' });
    const checkpoint = {
        schemaVersion: CHECKPOINT_SCHEMA,
        runId: plan.runId,
        planDigest: plan.planDigest,
        updatedAt: new Date().toISOString(),
        overallStatus: 'planned',
        consecutiveInfraErrors: 0,
        cases: Object.fromEntries(plan.cases.map((id) => [id, { status: 'planned', attempts: 0 }]))
    };
    await writeCheckpoint(root, checkpoint);
    await appendEvent(path.join(root, 'events.jsonl'), {
        runId: plan.runId, phase: 'plan', status: 'planned', isolation: plan.isolation,
        details: { planDigest: plan.planDigest, candidate: plan.candidate, cases: plan.cases.length }
    });
    return { root, plan, checkpoint };
}

async function readJson(file, label) {
    try { return JSON.parse(await readFile(file, 'utf8')); }
    catch (error) { throw new ValidationError(`cannot read ${label}: ${error.message}`); }
}

export async function loadBenchmarkRun(runDirectory, dataset) {
    const root = path.resolve(runDirectory);
    const plan = await readJson(path.join(root, 'PLAN.json'), 'benchmark plan');
    const checkpoint = await readJson(path.join(root, 'checkpoint.json'), 'benchmark checkpoint');
    if (plan.schemaVersion !== BENCHMARK_PLAN_SCHEMA || checkpoint.schemaVersion !== CHECKPOINT_SCHEMA) {
        throw new ValidationError('benchmark run has an incompatible schema');
    }
    if (plan.planDigest !== planDigest(plan)) throw new ValidationError('benchmark plan digest mismatch');
    if (checkpoint.planDigest !== plan.planDigest) throw new ValidationError('checkpoint plan digest mismatch');
    if (dataset.manifest.digest !== plan.dataset.digest) throw new ValidationError('benchmark dataset digest drift');
    if (new Set(plan.cases).size !== plan.cases.length) throw new ValidationError('benchmark plan contains duplicate cases');
    for (const id of plan.cases) findCase(dataset, id);
    verifyEventChain(await readEvents(path.join(root, 'events.jsonl')));
    return { root, plan, checkpoint };
}

async function writeCheckpoint(root, checkpoint) {
    const target = path.join(root, 'checkpoint.json');
    const temporary = path.join(root, 'checkpoint.next.json');
    await writeFile(temporary, `${JSON.stringify(checkpoint, null, 2)}\n`);
    await rename(temporary, target);
}

function candidatePrompt(caseData) {
    return [
        'Complete this repository task in the current isolated git worktree.',
        'Do not read outside the worktree, commit, push, deploy, access production, add writable directories, or inspect credentials.',
        'Keep changes inside the allowed paths and run focused verification when feasible.',
        `Task: ${caseData.prompt}`,
        `Allowed paths: ${caseData.allowedPaths.join(', ')}`,
        `Forbidden paths: ${caseData.forbiddenPaths.join(', ')}`,
        'Return a concise summary of changed files and tests actually run.'
    ].join('\n');
}

function classifyCaught(error) {
    if (error instanceof CommandError && error.result?.exitCode === 3) return 'blocked';
    return 'infra_error';
}

export async function runBenchmark(dataset, runDirectory, options = {}) {
    const state = await loadBenchmarkRun(runDirectory, dataset);
    if (state.plan.candidate === 'codex' && options.allowModelExecution !== true) {
        throw new ValidationError('codex benchmark requires --allow-model-execution');
    }
    const maxCases = options.maxCases === undefined ? Number.POSITIVE_INFINITY : safeInteger(options.maxCases, 'max cases', 1, state.plan.cases.length);
    const candidateRunner = options.candidateRunner ?? runCodexCandidate;
    const commandRunner = options.commandRunner ?? (state.plan.isolation === 'docker'
        ? createVerificationRunner({ timeoutMs: state.plan.timeoutSeconds * 1000 })
        : undefined);
    let processed = 0;
    state.checkpoint.overallStatus = 'running';
    await writeCheckpoint(state.root, state.checkpoint);

    for (const id of state.plan.cases) {
        const prior = state.checkpoint.cases[id];
        if (TERMINAL.has(prior.status)) continue;
        if (processed >= maxCases || state.checkpoint.consecutiveInfraErrors >= state.plan.maxConsecutiveInfraErrors) break;
        processed += 1;
        const caseData = findCase(dataset, id);
        const startedAt = Date.now();
        let metadata;
        let status = 'infra_error';
        let score = null;
        let candidateResult = null;
        let errorSummary = null;
        prior.status = 'running';
        prior.attempts += 1;
        prior.startedAt = new Date().toISOString();
        state.checkpoint.updatedAt = prior.startedAt;
        await writeCheckpoint(state.root, state.checkpoint);
        await appendEvent(path.join(state.root, 'events.jsonl'), {
            runId: state.plan.runId, caseId: id, phase: 'case', status: 'running', isolation: state.plan.isolation,
            details: { attempt: prior.attempts }
        });
        try {
            metadata = await prepareWorktree(state.plan.sourceRepo, caseData, state.plan.candidate === 'oracle');
            if (state.plan.candidate === 'codex') {
                candidateResult = await candidateRunner({
                    workspace: metadata.workspace,
                    prompt: candidatePrompt(caseData),
                    timeoutMs: state.plan.timeoutSeconds * 1000,
                    rawEventPath: path.join(state.root, 'raw', `${id}-attempt-${prior.attempts}.jsonl`),
                    model: state.plan.model
                });
                status = candidateResult.status;
            } else {
                candidateResult = { status: 'completed', durationMs: 0, tokens: { inputTokens: null, outputTokens: null }, cost: null };
                status = 'completed';
            }
            if (status === 'completed') {
                const report = await scoreCase(caseData, metadata.workspace, { commandRunner });
                score = report.scores.total;
                status = report.verdict;
                await writeFile(path.join(state.root, 'reports', `${id}.json`), `${JSON.stringify({
                    ...report,
                    dataset: state.plan.dataset,
                    benchmark: { runId: state.plan.runId, candidate: state.plan.candidate, isolation: state.plan.isolation, attempt: prior.attempts }
                }, null, 2)}\n`);
            } else {
                errorSummary = candidateResult.errorSummary ?? null;
            }
        } catch (error) {
            status = classifyCaught(error);
            errorSummary = sanitizeTelemetry(error.message);
        } finally {
            if (metadata) {
                try { await cleanupWorktree(metadata); }
                catch (error) {
                    status = 'infra_error';
                    errorSummary = `cleanup failed: ${sanitizeTelemetry(error.message)}`;
                }
            }
        }
        const durationMs = Date.now() - startedAt;
        Object.assign(prior, {
            status,
            finishedAt: new Date().toISOString(),
            durationMs,
            score,
            isolation: state.plan.isolation,
            tokens: candidateResult?.tokens ?? null,
            cost: candidateResult?.cost ?? null,
            errorSummary
        });
        state.checkpoint.consecutiveInfraErrors = status === 'infra_error' ? state.checkpoint.consecutiveInfraErrors + 1 : 0;
        state.checkpoint.updatedAt = new Date().toISOString();
        await writeCheckpoint(state.root, state.checkpoint);
        await appendEvent(path.join(state.root, 'events.jsonl'), {
            runId: state.plan.runId, caseId: id, phase: 'case', status, durationMs,
            exitCode: candidateResult?.exitCode ?? null, score, isolation: state.plan.isolation,
            tokens: candidateResult?.tokens ?? null, cost: candidateResult?.cost ?? null,
            details: { attempt: prior.attempts, errorSummary }
        });
    }
    const pending = Object.values(state.checkpoint.cases).some((entry) => !TERMINAL.has(entry.status));
    state.checkpoint.overallStatus = pending ? 'stopped' : 'complete';
    state.checkpoint.updatedAt = new Date().toISOString();
    await writeCheckpoint(state.root, state.checkpoint);
    await appendEvent(path.join(state.root, 'events.jsonl'), {
        runId: state.plan.runId, phase: 'run', status: state.checkpoint.overallStatus, isolation: state.plan.isolation,
        details: { processed, consecutiveInfraErrors: state.checkpoint.consecutiveInfraErrors }
    });
    return state.checkpoint;
}

function percentile(values, fraction) {
    if (values.length === 0) return null;
    const sorted = [...values].sort((a, b) => a - b);
    return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1)];
}

export async function generateBenchmarkReport(dataset, runDirectory) {
    const state = await loadBenchmarkRun(runDirectory, dataset);
    const entries = Object.entries(state.checkpoint.cases).map(([caseId, value]) => ({ caseId, ...value }));
    const counts = Object.fromEntries(['planned', 'running', 'pass', 'fail', 'blocked', 'timeout', 'infra_error'].map((status) => [status, entries.filter((entry) => entry.status === status).length]));
    const terminal = entries.filter((entry) => TERMINAL.has(entry.status));
    const scores = terminal.filter((entry) => typeof entry.score === 'number').map((entry) => entry.score);
    const durations = terminal.map((entry) => entry.durationMs).filter(Number.isFinite);
    const tokenEntries = terminal.filter((entry) => Number.isFinite(entry.tokens?.inputTokens) || Number.isFinite(entry.tokens?.outputTokens));
    const scoreReports = [];
    for (const entry of terminal.filter((candidate) => typeof candidate.score === 'number')) {
        scoreReports.push(await readJson(path.join(state.root, 'reports', `${entry.caseId}.json`), `score report ${entry.caseId}`));
    }
    const verificationIsolation = {};
    for (const report of scoreReports) {
        for (const check of report.checks ?? []) {
            const isolation = check.isolation ?? 'unknown';
            verificationIsolation[isolation] = (verificationIsolation[isolation] ?? 0) + 1;
        }
    }
    const chain = verifyEventChain(await readEvents(path.join(state.root, 'events.jsonl')));
    const summary = {
        schemaVersion: 'coding-agent-benchmark-summary/v1',
        generatedAt: new Date().toISOString(),
        runId: state.plan.runId,
        planDigest: state.plan.planDigest,
        eventDigest: chain.finalDigest,
        dataset: state.plan.dataset,
        candidate: state.plan.candidate,
        model: state.plan.model,
        workflowVersion: state.plan.workflowVersion,
        isolation: state.plan.isolation,
        overallStatus: state.checkpoint.overallStatus,
        totalCases: entries.length,
        counts,
        metrics: {
            completionRate: entries.length === 0 ? 0 : Math.round(terminal.length * 10000 / entries.length) / 100,
            passRate: terminal.length === 0 ? 0 : Math.round(counts.pass * 10000 / terminal.length) / 100,
            firstPassRate: terminal.length === 0 ? 0 : Math.round(entries.filter((entry) => entry.status === 'pass' && entry.attempts === 1).length * 10000 / terminal.length) / 100,
            outOfScopeRate: scoreReports.length === 0 ? null : Math.round(scoreReports.filter((report) => report.scope?.passed === false).length * 10000 / scoreReports.length) / 100,
            scoreReportCoverageRate: terminal.length === 0 ? 0 : Math.round(scoreReports.length * 10000 / terminal.length) / 100,
            averageScore: scores.length === 0 ? null : Math.round(scores.reduce((a, b) => a + b, 0) * 100 / scores.length) / 100,
            durationP50Ms: percentile(durations, 0.50),
            durationP95Ms: percentile(durations, 0.95),
            tokenCoverageRate: terminal.length === 0 ? 0 : Math.round(tokenEntries.length * 10000 / terminal.length) / 100,
            inputTokens: tokenEntries.length === 0 ? null : tokenEntries.reduce((sum, entry) => sum + (entry.tokens.inputTokens ?? 0), 0),
            outputTokens: tokenEntries.length === 0 ? null : tokenEntries.reduce((sum, entry) => sum + (entry.tokens.outputTokens ?? 0), 0),
            cost: null,
            verificationIsolation
        },
        cases: entries.map(({ errorSummary, ...entry }) => ({
            ...entry,
            errorSummary: entry.status === 'timeout' ? 'candidate exceeded configured timeout' : sanitizeTelemetry(errorSummary)
        }))
    };
    await writeFile(path.join(state.root, 'SUMMARY.json'), `${JSON.stringify(summary, null, 2)}\n`);
    const rows = summary.cases.map((entry) => `| ${entry.caseId} | ${entry.status} | ${entry.score ?? '-'} | ${entry.durationMs ?? '-'} |`).join('\n');
    const markdown = `# Coding Agent Benchmark Report\n\n- Run: \`${summary.runId}\`\n- Candidate: \`${summary.candidate}\`\n- Dataset: \`${summary.dataset.id}@${summary.dataset.version}\`\n- Plan digest: \`${summary.planDigest}\`\n- Event digest: \`${summary.eventDigest}\`\n- Isolation: \`${summary.isolation}\`\n- Status: \`${summary.overallStatus}\`\n\n## Metrics\n\n- Completion rate: ${summary.metrics.completionRate}%\n- Pass rate: ${summary.metrics.passRate}%\n- First-pass rate: ${summary.metrics.firstPassRate}%\n- Out-of-scope rate: ${summary.metrics.outOfScopeRate ?? 'unknown'}%\n- Average score: ${summary.metrics.averageScore ?? 'unknown'}\n- Duration P50/P95: ${summary.metrics.durationP50Ms ?? 'unknown'} / ${summary.metrics.durationP95Ms ?? 'unknown'} ms\n- Token coverage: ${summary.metrics.tokenCoverageRate}%\n- Verification isolation: ${Object.entries(summary.metrics.verificationIsolation).map(([key, value]) => `${key}=${value}`).join(', ') || 'unknown'}\n- Cost: unknown (not reported by the candidate event stream)\n\n## Cases\n\n| Case | Status | Score | Duration ms |\n| --- | --- | ---: | ---: |\n${rows}\n`;
    await writeFile(path.join(state.root, 'REPORT.md'), markdown);
    return summary;
}
