import { refExists, repositoryRoot, runCommand } from './git.mjs';
import { matchesPath, ValidationError } from './manifest.mjs';

export async function auditDatasetHistory(dataset, repository) {
    const root = await repositoryRoot(repository);
    const errors = [];
    for (const entry of dataset.cases) {
        const data = entry.data;
        if (!await refExists(root, data.baseRef)) errors.push(`${data.id}: missing baseRef ${data.baseRef}`);
        if (!await refExists(root, data.oracleRef)) errors.push(`${data.id}: missing oracleRef ${data.oracleRef}`);
        if (errors.some((message) => message.startsWith(`${data.id}: missing`))) continue;
        const parent = await runCommand(['git', 'rev-parse', `${data.oracleRef}^`], { cwd: root });
        if (parent.exitCode !== 0 || parent.stdout.trim() !== data.baseRef) {
            errors.push(`${data.id}: baseRef is not the direct oracle parent`);
        }
        const diff = await runCommand(['git', 'diff', '--name-only', '-z', data.baseRef, data.oracleRef, '--'], { cwd: root });
        if (diff.exitCode !== 0) {
            errors.push(`${data.id}: cannot inspect oracle diff`);
            continue;
        }
        const files = diff.stdout.split('\0').filter(Boolean);
        if (files.length === 0) errors.push(`${data.id}: oracle diff is empty`);
        const forbidden = files.filter((file) => data.forbiddenPaths.some((pattern) => matchesPath(file, pattern)));
        const outside = files.filter((file) => !data.allowedPaths.some((pattern) => matchesPath(file, pattern)));
        if (forbidden.length > 0) errors.push(`${data.id}: oracle touches forbidden paths: ${forbidden.join(', ')}`);
        if (outside.length > 0) errors.push(`${data.id}: oracle escapes allowed paths: ${outside.join(', ')}`);
    }
    if (errors.length > 0) throw new ValidationError(errors);
    return {
        status: 'valid',
        repository: root,
        cases: dataset.cases.length,
        tiers: Object.fromEntries(['core', 'extended'].map((tier) => [tier, dataset.cases.filter((entry) => entry.data.tier === tier).length])),
        difficulties: Object.fromEntries(['easy', 'medium', 'hard'].map((difficulty) => [difficulty, dataset.cases.filter((entry) => entry.data.difficulty === difficulty).length])),
        kinds: Object.fromEntries([...new Set(dataset.cases.map((entry) => entry.data.kind))].sort().map((kind) => [kind, dataset.cases.filter((entry) => entry.data.kind === kind).length])),
        digest: dataset.actualDigest
    };
}
