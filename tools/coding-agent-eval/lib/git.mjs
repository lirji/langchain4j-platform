import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

export class CommandError extends Error {
    constructor(message, result) {
        super(message);
        this.name = 'CommandError';
        this.result = result;
    }
}

export function runCommand(command, options = {}) {
    return new Promise((resolve, reject) => {
        const startedAt = Date.now();
        const child = spawn(command[0], command.slice(1), {
            cwd: options.cwd,
            env: options.env ?? process.env,
            shell: false,
            stdio: ['ignore', 'pipe', 'pipe']
        });
        let stdout = '';
        let stderr = '';
        const maxOutput = options.maxOutput ?? 200_000;
        child.stdout.on('data', (chunk) => { if (stdout.length < maxOutput) stdout += chunk; });
        child.stderr.on('data', (chunk) => { if (stderr.length < maxOutput) stderr += chunk; });
        child.on('error', (error) => reject(new CommandError(`cannot run ${command[0]}: ${error.message}`, {
            command, exitCode: null, stdout, stderr, durationMs: Date.now() - startedAt
        })));
        child.on('close', (exitCode, signal) => resolve({
            command,
            exitCode,
            signal,
            stdout: stdout.slice(0, maxOutput),
            stderr: stderr.slice(0, maxOutput),
            durationMs: Date.now() - startedAt
        }));
    });
}

export async function repositoryRoot(workspace) {
    const result = await runCommand(['git', 'rev-parse', '--show-toplevel'], { cwd: workspace });
    if (result.exitCode !== 0) throw new CommandError(`${workspace} is not a git worktree`, result);
    return realpath(path.resolve(result.stdout.trim()));
}

export async function refExists(repoRoot, ref) {
    const result = await runCommand(['git', 'cat-file', '-e', `${ref}^{commit}`], { cwd: repoRoot });
    return result.exitCode === 0;
}

export async function changedFiles(repoRoot, baseRef) {
    if (!await refExists(repoRoot, baseRef)) throw new CommandError(`missing git ref: ${baseRef}`, { exitCode: 3 });
    const tracked = await runCommand(['git', 'diff', '--name-only', '-z', '--diff-filter=ACMRTUXB', baseRef, '--'], { cwd: repoRoot });
    if (tracked.exitCode !== 0) throw new CommandError(`cannot calculate diff from ${baseRef}`, tracked);
    const untracked = await runCommand(['git', 'ls-files', '--others', '--exclude-standard', '-z'], { cwd: repoRoot });
    if (untracked.exitCode !== 0) throw new CommandError('cannot list untracked files', untracked);
    return [...new Set(`${tracked.stdout}${untracked.stdout}`.split('\0').filter(Boolean))].sort();
}

export async function prepareWorktree(sourceRepo, caseData, useOracle = false) {
    const repoRoot = await repositoryRoot(sourceRepo);
    const ref = useOracle ? caseData.oracleRef : caseData.baseRef;
    if (!await refExists(repoRoot, ref)) {
        throw new CommandError(`missing git ref ${ref}; fetch history explicitly before retrying`, { exitCode: 3 });
    }
    const container = await mkdtemp(path.join(os.tmpdir(), `coding-agent-${caseData.id}-`));
    const workspace = path.join(container, 'worktree');
    await mkdir(path.join(container, 'metadata'));
    const result = await runCommand(['git', 'worktree', 'add', '--detach', workspace, ref], { cwd: repoRoot });
    if (result.exitCode !== 0) {
        await rm(container, { recursive: true, force: true });
        throw new CommandError(`cannot prepare worktree for ${caseData.id}`, result);
    }
    const metadata = {
        createdBy: 'coding-agent-eval',
        caseId: caseData.id,
        sourceRepo: repoRoot,
        workspace,
        ref,
        mode: useOracle ? 'oracle' : 'base'
    };
    await writeFile(path.join(container, 'metadata', 'worktree.json'), `${JSON.stringify(metadata, null, 2)}\n`, { flag: 'wx' });
    return metadata;
}

export async function cleanupWorktree(metadata) {
    if (!metadata || metadata.createdBy !== 'coding-agent-eval') {
        throw new CommandError('refusing to clean an unregistered worktree', { exitCode: 2 });
    }
    const tempRoot = await realpath(os.tmpdir());
    const container = path.resolve(path.dirname(metadata.workspace));
    const containerReal = await realpath(container).catch(() => null);
    if (!containerReal
            || !containerReal.startsWith(`${tempRoot}${path.sep}`)
            || !path.basename(containerReal).startsWith(`coding-agent-${metadata.caseId}-`)
            || path.resolve(metadata.workspace) !== path.join(container, 'worktree')) {
        throw new CommandError('refusing to clean a target outside the registered temporary directory', { exitCode: 2 });
    }
    const workspace = await realpath(metadata.workspace).catch(() => null);
    const marker = path.join(containerReal, 'metadata', 'worktree.json');
    const markerData = JSON.parse(await readFile(marker, 'utf8'));
    if (markerData.createdBy !== 'coding-agent-eval' || markerData.workspace !== metadata.workspace) {
        throw new CommandError('worktree marker does not match cleanup target', { exitCode: 2 });
    }
    if (workspace) {
        const result = await runCommand(['git', 'worktree', 'remove', '--force', workspace], { cwd: metadata.sourceRepo });
        if (result.exitCode !== 0) throw new CommandError(`cannot remove worktree ${workspace}`, result);
    }
    await rm(containerReal, { recursive: true, force: false });
}
