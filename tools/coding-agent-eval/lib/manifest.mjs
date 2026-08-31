import { createHash } from 'node:crypto';
import { readFile, realpath } from 'node:fs/promises';
import path from 'node:path';

export const SCHEMA_VERSION = 'coding-agent-golden/v1';
export const TOOL_VERSION = '1.0.0';
export const CASE_KINDS = new Set(['feature', 'legacy', 'bug', 'security', 'review', 'doc', 'safety']);
export const CASE_TIERS = new Set(['core', 'extended']);
export const CASE_DIFFICULTIES = new Set(['easy', 'medium', 'hard']);
const EXECUTABLES = new Set(['mvn', 'node', 'npm', 'bash', 'git']);
const READ_ONLY_GIT = new Set(['cat-file', 'diff', 'grep', 'log', 'ls-files', 'rev-parse', 'show', 'status']);

export class ValidationError extends Error {
    constructor(messages) {
        const list = Array.isArray(messages) ? messages : [messages];
        super(list.join('\n'));
        this.name = 'ValidationError';
        this.messages = list;
    }
}

export function stableStringify(value) {
    if (Array.isArray(value)) {
        return `[${value.map(stableStringify).join(',')}]`;
    }
    if (value !== null && typeof value === 'object') {
        return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(',')}}`;
    }
    return JSON.stringify(value);
}

export function computeDatasetDigest(caseEntries) {
    const payload = caseEntries.map(({ relativePath, data }) => ({ path: relativePath, case: data }));
    return `sha256:${createHash('sha256').update(stableStringify(payload)).digest('hex')}`;
}

function assertString(errors, value, label, options = {}) {
    if (typeof value !== 'string' || value.trim() === '') {
        errors.push(`${label} must be a non-empty string`);
        return;
    }
    if (options.pattern && !options.pattern.test(value)) {
        errors.push(`${label} has an invalid format`);
    }
}

function validateRelativePath(value, label, { allowGlob = false } = {}) {
    if (typeof value !== 'string' || value.length === 0) {
        throw new ValidationError(`${label} must be a non-empty string`);
    }
    const normalized = value.replaceAll('\\', '/');
    if (path.posix.isAbsolute(normalized) || normalized.includes('\0')) {
        throw new ValidationError(`${label} must be repository-relative`);
    }
    const parts = normalized.split('/');
    if (parts.some((part) => part === '..' || part === '.')) {
        throw new ValidationError(`${label} may not contain traversal segments`);
    }
    if (allowGlob) {
        const stars = (normalized.match(/\*/g) ?? []).length;
        if (stars > 0 && (stars !== 2 || !normalized.endsWith('/**'))) {
            throw new ValidationError(`${label} only supports a trailing /** glob`);
        }
    } else if (normalized.includes('*')) {
        throw new ValidationError(`${label} may not contain globs`);
    }
    return normalized;
}

export function validateCommand(command, label = 'verification command') {
    if (!Array.isArray(command) || command.length === 0 || command.some((arg) => typeof arg !== 'string')) {
        throw new ValidationError(`${label} must be a non-empty string array`);
    }
    if (!EXECUTABLES.has(command[0])) {
        throw new ValidationError(`${label} executable '${command[0]}' is not allowed`);
    }
    if (command.some((arg) => arg.includes('\0') || arg.includes('\n') || arg.includes('\r'))) {
        throw new ValidationError(`${label} contains a control character`);
    }
    if (command[0] === 'git' && (!command[1] || !READ_ONLY_GIT.has(command[1]))) {
        throw new ValidationError(`${label} uses a non-read-only git subcommand`);
    }
    if (command[0] === 'git' && command.some((arg) =>
        arg === '-C'
        || arg.startsWith('--git-dir')
        || arg.startsWith('--work-tree')
        || arg === '--ext-diff'
        || arg === '--no-index'
        || arg === '--textconv'
        || arg === '--filters'
        || arg.startsWith('--open-files-in-pager')
        || arg.startsWith('--output=')
        || arg === '--output')) {
        throw new ValidationError(`${label} may not redirect git outside the workspace`);
    }
    if (command[0] === 'node' && command.slice(1).some((arg) => arg === '-e' || arg === '--eval' || arg === '-p' || arg === '--print')) {
        throw new ValidationError(`${label} may not execute inline Node.js code`);
    }
    if (command[0] === 'node') {
        const scriptIndex = command[1] === '--test' ? 2 : 1;
        if (!command[scriptIndex]) throw new ValidationError(`${label} must name a repository JavaScript file`);
        const script = validateRelativePath(command[scriptIndex], `${label} script`);
        if (!/\.(?:mjs|cjs|js)$/.test(script)) throw new ValidationError(`${label} Node.js target must be a JavaScript file`);
    }
    if (command[0] === 'npm') {
        const operation = command[1];
        if (operation !== 'test' && operation !== 'run') throw new ValidationError(`${label} npm operation must be test or run`);
        if (operation === 'run' && (!command[2] || !/^[a-zA-Z0-9:_-]+$/.test(command[2]))) {
            throw new ValidationError(`${label} npm run requires a simple script name`);
        }
        if (command.some((arg) => arg === '--prefix' || arg.startsWith('--prefix='))) {
            throw new ValidationError(`${label} may not redirect npm outside the workspace`);
        }
    }
    if (command[0] === 'mvn') {
        if (command.some((arg) => ['install', 'deploy'].includes(arg) || arg.includes('exec:'))) {
            throw new ValidationError(`${label} uses a disallowed Maven goal`);
        }
        if (!command.some((arg) => ['test', 'verify', 'package'].includes(arg))) {
            throw new ValidationError(`${label} Maven command must include a verification lifecycle goal`);
        }
    }
    if (command[0] === 'bash') {
        if (!command[1] || command[1].startsWith('-')) {
            throw new ValidationError(`${label} must invoke a repository script directly`);
        }
        const script = validateRelativePath(command[1], `${label} script`);
        if (!script.endsWith('.sh')) {
            throw new ValidationError(`${label} bash target must end in .sh`);
        }
    }
}

export function validateCase(data, relativePath = '<case>', options = {}) {
    const errors = [];
    if (!data || typeof data !== 'object' || Array.isArray(data)) {
        throw new ValidationError(`${relativePath} must contain a JSON object`);
    }
    if (data.schemaVersion !== SCHEMA_VERSION) {
        errors.push(`${relativePath}.schemaVersion must equal ${SCHEMA_VERSION}`);
    }
    assertString(errors, data.id, `${relativePath}.id`, { pattern: /^[a-z0-9][a-z0-9-]{2,79}$/ });
    if (!CASE_KINDS.has(data.kind)) {
        errors.push(`${relativePath}.kind must be one of ${[...CASE_KINDS].join(', ')}`);
    }
    assertString(errors, data.title, `${relativePath}.title`);
    assertString(errors, data.prompt, `${relativePath}.prompt`);
    if (options.requireProductionMetadata || data.tier !== undefined) {
        if (!CASE_TIERS.has(data.tier)) errors.push(`${relativePath}.tier must be one of ${[...CASE_TIERS].join(', ')}`);
    }
    if (options.requireProductionMetadata || data.difficulty !== undefined) {
        if (!CASE_DIFFICULTIES.has(data.difficulty)) errors.push(`${relativePath}.difficulty must be one of ${[...CASE_DIFFICULTIES].join(', ')}`);
    }
    if (options.requireProductionMetadata || data.sourceCommitSubject !== undefined) {
        assertString(errors, data.sourceCommitSubject, `${relativePath}.sourceCommitSubject`);
    }
    assertString(errors, data.baseRef, `${relativePath}.baseRef`, { pattern: /^[a-f0-9]{40}$/ });
    assertString(errors, data.oracleRef, `${relativePath}.oracleRef`, { pattern: /^[a-f0-9]{40}$/ });
    for (const [field, allowEmpty] of [['riskTags', true], ['allowedPaths', false], ['forbiddenPaths', true], ['verification', false]]) {
        if (!Array.isArray(data[field]) || (!allowEmpty && data[field].length === 0)) {
            errors.push(`${relativePath}.${field} must be ${allowEmpty ? 'an array' : 'a non-empty array'}`);
        }
    }
    if (Array.isArray(data.riskTags) && data.riskTags.some((tag) => typeof tag !== 'string' || tag.trim() === '')) {
        errors.push(`${relativePath}.riskTags must contain only non-empty strings`);
    }
    for (const [field, paths] of [['allowedPaths', data.allowedPaths], ['forbiddenPaths', data.forbiddenPaths]]) {
        if (!Array.isArray(paths)) continue;
        paths.forEach((candidate, index) => {
            try {
                validateRelativePath(candidate, `${relativePath}.${field}[${index}]`, { allowGlob: true });
            } catch (error) {
                errors.push(error.message);
            }
        });
    }
    if (Array.isArray(data.verification)) {
        data.verification.forEach((check, index) => {
            if (!check || typeof check !== 'object') {
                errors.push(`${relativePath}.verification[${index}] must be an object`);
                return;
            }
            assertString(errors, check.name, `${relativePath}.verification[${index}].name`);
            try {
                validateCommand(check.command, `${relativePath}.verification[${index}].command`);
                if (check.cwd !== undefined) validateRelativePath(check.cwd, `${relativePath}.verification[${index}].cwd`);
            } catch (error) {
                errors.push(error.message);
            }
        });
    }
    const scoring = data.scoring;
    if (!scoring || typeof scoring !== 'object') {
        errors.push(`${relativePath}.scoring must be an object`);
    } else {
        const weights = ['pathScope', 'verification', 'rubric'].map((field) => scoring[field]);
        if (weights.some((weight) => !Number.isInteger(weight) || weight < 0) || weights.reduce((a, b) => a + b, 0) !== 100) {
            errors.push(`${relativePath}.scoring weights must be non-negative integers totaling 100`);
        }
        if (!Number.isInteger(scoring.passThreshold) || scoring.passThreshold < 0 || scoring.passThreshold > 100) {
            errors.push(`${relativePath}.scoring.passThreshold must be an integer from 0 to 100`);
        }
        if (!Array.isArray(scoring.rubricItems) || scoring.rubricItems.length === 0) {
            errors.push(`${relativePath}.scoring.rubricItems must be a non-empty array`);
        } else {
            let rubricWeight = 0;
            scoring.rubricItems.forEach((item, index) => {
                if (!item || typeof item !== 'object') {
                    errors.push(`${relativePath}.scoring.rubricItems[${index}] must be an object`);
                    return;
                }
                assertString(errors, item.id, `${relativePath}.scoring.rubricItems[${index}].id`);
                assertString(errors, item.description, `${relativePath}.scoring.rubricItems[${index}].description`);
                if (!Number.isInteger(item.weight) || item.weight < 0) {
                    errors.push(`${relativePath}.scoring.rubricItems[${index}].weight must be a non-negative integer`);
                } else {
                    rubricWeight += item.weight;
                }
                if (!Array.isArray(item.evidencePaths) || item.evidencePaths.length === 0) {
                    errors.push(`${relativePath}.scoring.rubricItems[${index}].evidencePaths must be non-empty`);
                } else {
                    item.evidencePaths.forEach((candidate, pathIndex) => {
                        try {
                            validateRelativePath(candidate, `${relativePath}.scoring.rubricItems[${index}].evidencePaths[${pathIndex}]`, { allowGlob: true });
                        } catch (error) {
                            errors.push(error.message);
                        }
                    });
                }
            });
            if (Number.isInteger(scoring.rubric) && rubricWeight !== scoring.rubric) {
                errors.push(`${relativePath}.scoring rubric item weights must total scoring.rubric`);
            }
        }
    }
    if (errors.length > 0) throw new ValidationError(errors);
    return data;
}

async function readJson(file) {
    try {
        return JSON.parse(await readFile(file, 'utf8'));
    } catch (error) {
        throw new ValidationError(`cannot read JSON ${file}: ${error.message}`);
    }
}

export async function loadDataset(manifestPath, options = {}) {
    let resolvedManifest;
    try {
        resolvedManifest = await realpath(path.resolve(manifestPath));
    } catch (error) {
        throw new ValidationError(`cannot resolve manifest ${manifestPath}: ${error.message}`);
    }
    const manifest = await readJson(resolvedManifest);
    const errors = [];
    if (manifest.schemaVersion !== SCHEMA_VERSION) errors.push(`manifest.schemaVersion must equal ${SCHEMA_VERSION}`);
    assertString(errors, manifest.datasetId, 'manifest.datasetId', { pattern: /^[a-z0-9][a-z0-9-]{2,79}$/ });
    assertString(errors, manifest.version, 'manifest.version', { pattern: /^\d+\.\d+\.\d+$/ });
    if (manifest.toolVersion !== TOOL_VERSION) errors.push(`manifest.toolVersion must equal ${TOOL_VERSION}`);
    const requestedMinimum = options.minimumCases ?? 20;
    const declaredMinimum = Number.isInteger(manifest.minimumCases) ? manifest.minimumCases : requestedMinimum;
    const minimumCases = Math.max(requestedMinimum, declaredMinimum);
    if (!Array.isArray(manifest.cases) || manifest.cases.length < minimumCases) {
        errors.push(`manifest.cases must contain at least ${minimumCases} entries`);
    }
    assertString(errors, manifest.digest, 'manifest.digest', { pattern: /^sha256:[a-f0-9]{64}$/ });
    if (errors.length > 0) throw new ValidationError(errors);

    const root = path.dirname(resolvedManifest);
    const entries = [];
    const ids = new Set();
    for (const [index, candidate] of manifest.cases.entries()) {
        const relativePath = validateRelativePath(candidate, `manifest.cases[${index}]`);
        let casePath;
        try {
            casePath = await realpath(path.resolve(root, relativePath));
        } catch (error) {
            throw new ValidationError(`cannot resolve case ${relativePath}: ${error.message}`);
        }
        if (path.relative(root, casePath).startsWith('..')) throw new ValidationError(`manifest.cases[${index}] escapes the dataset directory`);
        const requireProductionMetadata = Number.parseInt(manifest.version.split('.')[0], 10) >= 2;
        const data = validateCase(await readJson(casePath), relativePath, { requireProductionMetadata });
        if (ids.has(data.id)) throw new ValidationError(`duplicate case id: ${data.id}`);
        ids.add(data.id);
        entries.push({ relativePath, path: casePath, data });
    }
    const actualDigest = computeDatasetDigest(entries);
    if (options.checkDigest !== false && actualDigest !== manifest.digest) {
        throw new ValidationError(`manifest digest mismatch: expected ${manifest.digest}, computed ${actualDigest}`);
    }
    if (Number.parseInt(manifest.version.split('.')[0], 10) >= 2) {
        const coreCases = entries.filter((entry) => entry.data.tier === 'core');
        if (coreCases.length < 20) throw new ValidationError('version 2 datasets must contain at least 20 core cases');
        for (const difficulty of CASE_DIFFICULTIES) {
            if (!entries.some((entry) => entry.data.difficulty === difficulty)) {
                throw new ValidationError(`version 2 datasets must contain difficulty '${difficulty}'`);
            }
        }
    }
    return { manifestPath: resolvedManifest, root, manifest, cases: entries, actualDigest };
}

export function findCase(dataset, id) {
    const entry = dataset.cases.find((candidate) => candidate.data.id === id);
    if (!entry) throw new ValidationError(`unknown case id: ${id}`);
    return entry.data;
}

export function matchesPath(file, pattern) {
    const normalizedFile = file.replaceAll('\\', '/');
    if (pattern.endsWith('/**')) {
        const prefix = pattern.slice(0, -3);
        return normalizedFile === prefix || normalizedFile.startsWith(`${prefix}/`);
    }
    return normalizedFile === pattern;
}
