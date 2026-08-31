import { realpath } from 'node:fs/promises';
import path from 'node:path';
import { changedFiles, repositoryRoot, runCommand } from './git.mjs';
import { matchesPath, validateCommand, ValidationError } from './manifest.mjs';

function round(value) {
    return Math.round(value * 100) / 100;
}

function trimOutput(value, max = 4000) {
    return value.length <= max ? value : `${value.slice(0, max)}\n...[truncated]`;
}

function verificationEnvironment() {
    const allowed = ['PATH', 'HOME', 'JAVA_HOME', 'M2_HOME', 'CI', 'LANG', 'LC_ALL', 'TMPDIR', 'TMP', 'TEMP'];
    return Object.fromEntries(allowed.filter((key) => process.env[key] !== undefined).map((key) => [key, process.env[key]]));
}

export async function scoreCase(caseData, workspace, options = {}) {
    const root = await repositoryRoot(workspace);
    if (await realpath(path.resolve(workspace)) !== root) {
        throw new ValidationError(`workspace must be the root of its git worktree: ${root}`);
    }
    const files = await changedFiles(root, caseData.baseRef);
    const forbidden = files.filter((file) => caseData.forbiddenPaths.some((pattern) => matchesPath(file, pattern)));
    const outsideAllowed = files.filter((file) => !caseData.allowedPaths.some((pattern) => matchesPath(file, pattern)));
    const scopePassed = files.length > 0 && forbidden.length === 0 && outsideAllowed.length === 0;
    const pathScore = scopePassed ? caseData.scoring.pathScope : 0;

    const checks = [];
    for (const check of caseData.verification) {
        validateCommand(check.command, `verification '${check.name}'`);
        const requestedCwd = path.resolve(root, check.cwd ?? '.');
        const cwd = await realpath(requestedCwd);
        if (cwd !== root && !cwd.startsWith(`${root}${path.sep}`)) {
            throw new ValidationError(`verification cwd escapes workspace: ${check.cwd}`);
        }
        const commandRunner = options.commandRunner ?? ((command, context) => runCommand(command, context));
        const result = await commandRunner(check.command, { cwd, root, env: verificationEnvironment() });
        checks.push({
            name: check.name,
            command: check.command,
            cwd: path.relative(root, cwd) || '.',
            exitCode: result.exitCode,
            durationMs: result.durationMs,
            isolation: result.isolation ?? 'host',
            timedOut: result.timedOut === true,
            passed: result.exitCode === 0,
            stdout: trimOutput(result.stdout),
            stderr: trimOutput(result.stderr)
        });
    }
    const passedChecks = checks.filter((check) => check.passed).length;
    const verificationScore = checks.length === 0 ? 0 : caseData.scoring.verification * passedChecks / checks.length;

    const rubric = caseData.scoring.rubricItems.map((item) => {
        const evidence = files.filter((file) => item.evidencePaths.some((pattern) => matchesPath(file, pattern)));
        return { id: item.id, description: item.description, weight: item.weight, passed: evidence.length > 0, evidence };
    });
    const rubricScore = rubric.filter((item) => item.passed).reduce((sum, item) => sum + item.weight, 0);
    const total = round(pathScore + verificationScore + rubricScore);
    const hardGatePassed = scopePassed && checks.every((check) => check.passed);
    const verdict = hardGatePassed && total >= caseData.scoring.passThreshold ? 'pass' : 'fail';

    return {
        schemaVersion: 'coding-agent-score/v1',
        toolVersion: '1.0.0',
        caseId: caseData.id,
        baseRef: caseData.baseRef,
        oracleRef: caseData.oracleRef,
        workspace: root,
        generatedAt: new Date().toISOString(),
        changedFiles: files,
        scope: { passed: scopePassed, allowedPaths: caseData.allowedPaths, forbiddenFiles: forbidden, outsideAllowed },
        checks,
        rubric,
        scores: {
            pathScope: round(pathScore),
            verification: round(verificationScore),
            rubric: round(rubricScore),
            total
        },
        passThreshold: caseData.scoring.passThreshold,
        verdict
    };
}
