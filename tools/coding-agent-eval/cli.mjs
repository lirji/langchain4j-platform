#!/usr/bin/env node

import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { CommandError, prepareWorktree } from './lib/git.mjs';
import { findCase, loadDataset, ValidationError } from './lib/manifest.mjs';
import { aggregateReports } from './lib/report.mjs';
import { scoreCase } from './lib/scorer.mjs';
import { createBenchmarkPlan, generateBenchmarkReport, initializeBenchmarkRun, runBenchmark } from './lib/benchmark.mjs';
import { auditDatasetHistory } from './lib/history-audit.mjs';

const DEFAULT_MANIFEST = 'docs/qa/coding-agent-golden/manifest.json';
const EXPECTED_SKILLS = [
    'platform-java-feature',
    'platform-legacy-maintenance',
    'platform-prod-debug',
    'platform-diff-review',
    'platform-qa',
    'platform-pr-package'
];
const EXPECTED_AGENTS = [
    'platform-investigator',
    'platform-architect',
    'platform-reviewer',
    'platform-qa'
];

function usage() {
    return `Coding Agent GoldenCase evaluator

Usage:
  node tools/coding-agent-eval/cli.mjs validate [--manifest <file>]
  node tools/coding-agent-eval/cli.mjs audit [--manifest <file>] [--repo <repo>]
  node tools/coding-agent-eval/cli.mjs validate-kit [--root <repo>]
  node tools/coding-agent-eval/cli.mjs list [--manifest <file>] [--kind <kind>] [--json]
  node tools/coding-agent-eval/cli.mjs prepare --case <id> [--manifest <file>] [--repo <repo>] [--oracle]
  node tools/coding-agent-eval/cli.mjs score --case <id> --workspace <dir> [--manifest <file>] [--output <file>]
  node tools/coding-agent-eval/cli.mjs report --input <dir> [--manifest <file>] [--output <file>]
  node tools/coding-agent-eval/cli.mjs benchmark plan --candidate <oracle|codex> --output <dir> [options]
  node tools/coding-agent-eval/cli.mjs benchmark run --run-dir <dir> [--max-cases <n>] [--allow-model-execution]
  node tools/coding-agent-eval/cli.mjs benchmark resume --run-dir <dir> [--max-cases <n>] [--allow-model-execution]
  node tools/coding-agent-eval/cli.mjs benchmark report --run-dir <dir> [--manifest <file>]

Exit codes: 0 success, 2 invalid input/data, 3 missing git history, 4 score verdict failed.`;
}

function parseArgs(tokens) {
    const options = { _: [] };
    for (let index = 0; index < tokens.length; index += 1) {
        const token = tokens[index];
        if (!token.startsWith('--')) {
            options._.push(token);
            continue;
        }
        const key = token.slice(2);
        if (['json', 'oracle', 'help', 'allow-model-execution'].includes(key)) {
            options[key] = true;
            continue;
        }
        const value = tokens[index + 1];
        if (value === undefined || value.startsWith('--')) throw new ValidationError(`missing value for --${key}`);
        if (options[key] !== undefined) throw new ValidationError(`duplicate option --${key}`);
        options[key] = value;
        index += 1;
    }
    return options;
}

async function handleBenchmark(tokens) {
    const [subcommand, ...remaining] = tokens;
    if (!subcommand) throw new ValidationError('benchmark requires plan, run, resume, or report');
    const options = parseArgs(remaining);
    if (subcommand === 'plan') {
        assertOptions(options, [
            'manifest', 'candidate', 'output', 'isolation', 'case-limit', 'timeout-seconds',
            'model', 'workflow-version', 'run-id', 'repo'
        ], ['candidate', 'output']);
        const dataset = await loadDataset(options.manifest ?? DEFAULT_MANIFEST);
        const plan = await createBenchmarkPlan(dataset, {
            candidate: options.candidate,
            isolation: options.isolation ?? 'host',
            caseLimit: options['case-limit'] ?? 20,
            timeoutSeconds: options['timeout-seconds'] ?? 480,
            model: options.model,
            workflowVersion: options['workflow-version'],
            runId: options['run-id'],
            sourceRepo: options.repo ?? process.cwd()
        });
        const initialized = await initializeBenchmarkRun(options.output, plan);
        await writeJson(null, { status: 'planned', runDirectory: initialized.root, plan });
        return 0;
    }
    if (subcommand === 'run' || subcommand === 'resume') {
        assertOptions(options, ['manifest', 'run-dir', 'max-cases', 'allow-model-execution'], ['run-dir']);
        const dataset = await loadDataset(options.manifest ?? DEFAULT_MANIFEST);
        const checkpoint = await runBenchmark(dataset, options['run-dir'], {
            maxCases: options['max-cases'],
            allowModelExecution: options['allow-model-execution'] === true
        });
        await writeJson(null, checkpoint);
        return 0;
    }
    if (subcommand === 'report') {
        assertOptions(options, ['manifest', 'run-dir'], ['run-dir']);
        const dataset = await loadDataset(options.manifest ?? DEFAULT_MANIFEST);
        await writeJson(null, await generateBenchmarkReport(dataset, options['run-dir']));
        return 0;
    }
    throw new ValidationError(`unknown benchmark subcommand: ${subcommand}`);
}

function assertOptions(options, allowed, required = []) {
    const unknown = Object.keys(options).filter((key) => key !== '_' && !allowed.includes(key));
    if (unknown.length > 0 || options._.length > 0) {
        throw new ValidationError(`unknown argument(s): ${[...unknown.map((key) => `--${key}`), ...options._].join(', ')}`);
    }
    for (const key of required) {
        if (!options[key]) throw new ValidationError(`missing required option --${key}`);
    }
}

async function writeJson(output, value) {
    const content = `${JSON.stringify(value, null, 2)}\n`;
    if (!output) {
        process.stdout.write(content);
        return;
    }
    await writeFile(path.resolve(output), content, { flag: 'wx' });
}

function parseFrontmatter(content, file) {
    const match = content.match(/^---\n([\s\S]*?)\n---\n/);
    if (!match) throw new ValidationError(`${file} has invalid YAML frontmatter markers`);
    const values = {};
    for (const line of match[1].split('\n')) {
        const pair = line.match(/^([a-zA-Z0-9_-]+):\s*(.+)$/);
        if (pair) values[pair[1]] = pair[2].replace(/^['"]|['"]$/g, '').trim();
    }
    return values;
}

async function validateKit(rootOption) {
    const root = path.resolve(rootOption ?? process.cwd());
    const errors = [];
    for (const skill of EXPECTED_SKILLS) {
        const directory = path.join(root, '.agents', 'skills', skill);
        try {
            const skillFile = path.join(directory, 'SKILL.md');
            const content = await readFile(skillFile, 'utf8');
            const frontmatter = parseFrontmatter(content, skillFile);
            if (frontmatter.name !== skill) errors.push(`${skillFile} name must equal ${skill}`);
            if (!frontmatter.description || frontmatter.description.includes('[TODO:')) errors.push(`${skillFile} needs a completed description`);
            if (content.includes('[TODO:')) errors.push(`${skillFile} contains an unfinished placeholder`);
            const yamlFile = path.join(directory, 'agents', 'openai.yaml');
            const yaml = await readFile(yamlFile, 'utf8');
            if (!yaml.includes(`$${skill}`)) errors.push(`${yamlFile} default_prompt must mention $${skill}`);
            if (!/^interface:\n/m.test(yaml) || !/^  display_name: ".+"$/m.test(yaml) || !/^  short_description: ".+"$/m.test(yaml)) {
                errors.push(`${yamlFile} is missing interface metadata`);
            }
        } catch (error) {
            errors.push(`cannot validate skill ${skill}: ${error.message}`);
        }
    }
    for (const agent of EXPECTED_AGENTS) {
        const file = path.join(root, '.codex', 'agents', `${agent}.toml`);
        try {
            const content = await readFile(file, 'utf8');
            if (!content.includes(`name = "${agent}"`)) errors.push(`${file} has the wrong name`);
            if (!/^description = ".+"$/m.test(content)) errors.push(`${file} needs a description`);
            if (!content.includes('sandbox_mode = "read-only"')) errors.push(`${file} must be read-only`);
            if (!/developer_instructions = """[\s\S]+"""/.test(content)) errors.push(`${file} needs developer_instructions`);
            for (const requiredConcept of ['evidence', 'Stop', 'edit']) {
                if (!content.toLowerCase().includes(requiredConcept.toLowerCase())) errors.push(`${file} must define ${requiredConcept} behavior`);
            }
        } catch (error) {
            errors.push(`cannot validate agent ${agent}: ${error.message}`);
        }
    }
    if (errors.length > 0) throw new ValidationError(errors);
    return { status: 'valid', skills: EXPECTED_SKILLS.length, agents: EXPECTED_AGENTS.length, root };
}

async function main() {
    const [command, ...tokens] = process.argv.slice(2);
    if (!command || command === '--help' || command === 'help') {
        process.stdout.write(`${usage()}\n`);
        return 0;
    }
    if (command === 'benchmark') return handleBenchmark(tokens);
    const options = parseArgs(tokens);
    if (options.help) {
        process.stdout.write(`${usage()}\n`);
        return 0;
    }

    if (command === 'validate-kit') {
        assertOptions(options, ['root']);
        await writeJson(null, await validateKit(options.root));
        return 0;
    }

    if (command === 'validate') {
        assertOptions(options, ['manifest']);
        const dataset = await loadDataset(options.manifest ?? DEFAULT_MANIFEST);
        await writeJson(null, {
            status: 'valid',
            datasetId: dataset.manifest.datasetId,
            version: dataset.manifest.version,
            cases: dataset.cases.length,
            digest: dataset.actualDigest
        });
        return 0;
    }

    if (command === 'audit') {
        assertOptions(options, ['manifest', 'repo']);
        const dataset = await loadDataset(options.manifest ?? DEFAULT_MANIFEST);
        await writeJson(null, await auditDatasetHistory(dataset, options.repo ?? process.cwd()));
        return 0;
    }

    if (command === 'list') {
        assertOptions(options, ['manifest', 'kind', 'json']);
        const dataset = await loadDataset(options.manifest ?? DEFAULT_MANIFEST);
        const cases = dataset.cases.map((entry) => entry.data).filter((entry) => !options.kind || entry.kind === options.kind);
        if (options.json) {
            await writeJson(null, cases.map(({ id, kind, title, riskTags }) => ({ id, kind, title, riskTags })));
        } else {
            process.stdout.write(cases.map((entry) => `${entry.id}\t${entry.kind}\t${entry.title}`).join('\n') + (cases.length ? '\n' : ''));
        }
        return 0;
    }

    if (command === 'prepare') {
        assertOptions(options, ['case', 'manifest', 'repo', 'oracle'], ['case']);
        const dataset = await loadDataset(options.manifest ?? DEFAULT_MANIFEST);
        const metadata = await prepareWorktree(options.repo ?? process.cwd(), findCase(dataset, options.case), options.oracle === true);
        await writeJson(null, metadata);
        return 0;
    }

    if (command === 'score') {
        assertOptions(options, ['case', 'manifest', 'workspace', 'output'], ['case', 'workspace']);
        const dataset = await loadDataset(options.manifest ?? DEFAULT_MANIFEST);
        const report = await scoreCase(findCase(dataset, options.case), path.resolve(options.workspace));
        await writeJson(options.output, {
            ...report,
            dataset: { id: dataset.manifest.datasetId, version: dataset.manifest.version, digest: dataset.manifest.digest }
        });
        return report.verdict === 'pass' ? 0 : 4;
    }

    if (command === 'report') {
        assertOptions(options, ['input', 'manifest', 'output'], ['input']);
        const dataset = await loadDataset(options.manifest ?? DEFAULT_MANIFEST);
        await writeJson(options.output, await aggregateReports(options.input, dataset));
        return 0;
    }

    throw new ValidationError(`unknown command: ${command}\n${usage()}`);
}

try {
    process.exitCode = await main();
} catch (error) {
    const exitCode = error instanceof CommandError && error.result?.exitCode === 3 ? 3 : 2;
    process.stderr.write(`${error.name ?? 'Error'}: ${error.message}\n`);
    process.exitCode = exitCode;
}
