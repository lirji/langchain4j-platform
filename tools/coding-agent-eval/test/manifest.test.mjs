import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { computeDatasetDigest, loadDataset, SCHEMA_VERSION, TOOL_VERSION, ValidationError } from '../lib/manifest.mjs';

function validCase(overrides = {}) {
    return {
        schemaVersion: SCHEMA_VERSION,
        id: 'fixture-case',
        kind: 'bug',
        title: 'Fixture case',
        baseRef: '1'.repeat(40),
        oracleRef: '2'.repeat(40),
        prompt: 'Repair the fixture without changing unrelated files.',
        riskTags: ['fixture'],
        allowedPaths: ['src/**'],
        forbiddenPaths: ['.env'],
        verification: [{ name: 'diff', command: ['git', 'diff', '--check'] }],
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

async function writeDataset(cases, manifestOverrides = {}) {
    const root = await mkdtemp(path.join(os.tmpdir(), 'coding-agent-manifest-'));
    await mkdir(path.join(root, 'cases'));
    const entries = [];
    for (const [index, data] of cases.entries()) {
        const relativePath = `cases/case-${index}.json`;
        await writeFile(path.join(root, relativePath), `${JSON.stringify(data, null, 2)}\n`);
        entries.push({ relativePath, data });
    }
    const manifest = {
        schemaVersion: SCHEMA_VERSION,
        datasetId: 'fixture-dataset',
        version: '1.0.0',
        toolVersion: TOOL_VERSION,
        digest: computeDatasetDigest(entries),
        cases: entries.map((entry) => entry.relativePath),
        ...manifestOverrides
    };
    const manifestPath = path.join(root, 'manifest.json');
    await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
    return manifestPath;
}

test('loads a valid dataset and verifies its digest', async () => {
    const manifest = await writeDataset([validCase()]);
    const dataset = await loadDataset(manifest, { minimumCases: 1 });
    assert.equal(dataset.cases.length, 1);
    assert.equal(dataset.actualDigest, dataset.manifest.digest);
});

test('rejects duplicate case IDs', async () => {
    const manifest = await writeDataset([validCase(), validCase()]);
    await assert.rejects(() => loadDataset(manifest, { minimumCases: 1 }), /duplicate case id/);
});

test('rejects an unknown schema version', async () => {
    const manifest = await writeDataset([validCase({ schemaVersion: 'coding-agent-golden/v2' })]);
    await assert.rejects(() => loadDataset(manifest, { minimumCases: 1 }), /schemaVersion/);
});

test('rejects a case path that escapes the dataset directory', async () => {
    const manifest = await writeDataset([validCase()], { cases: ['../outside.json'] });
    await assert.rejects(() => loadDataset(manifest, { minimumCases: 1 }), /traversal/);
});

test('rejects a case symlink that escapes the dataset directory', async () => {
    const manifest = await writeDataset([validCase()]);
    const root = path.dirname(manifest);
    const outside = path.join(path.dirname(root), `${path.basename(root)}-outside-case.json`);
    await writeFile(outside, `${JSON.stringify(validCase())}\n`);
    const linked = path.join(root, 'cases', 'linked.json');
    await symlink(outside, linked);
    const data = JSON.parse(await readFile(manifest, 'utf8'));
    data.cases = ['cases/linked.json'];
    await writeFile(manifest, `${JSON.stringify(data, null, 2)}\n`);
    await assert.rejects(() => loadDataset(manifest, { minimumCases: 1, checkDigest: false }), /escapes the dataset directory/);
});

test('rejects a non-allowlisted executable', async () => {
    const manifest = await writeDataset([validCase({
        verification: [{ name: 'unsafe', command: ['curl', 'https://example.invalid'] }]
    })]);
    await assert.rejects(() => loadDataset(manifest, { minimumCases: 1 }), /not allowed/);
});

test('rejects inline Node.js execution', async () => {
    const manifest = await writeDataset([validCase({
        verification: [{ name: 'unsafe', command: ['node', '-e', 'process.exit(0)'] }]
    })]);
    await assert.rejects(() => loadDataset(manifest, { minimumCases: 1 }), /inline Node/);
});

test('rejects npm exec and Maven deploy operations', async () => {
    const npmManifest = await writeDataset([validCase({
        verification: [{ name: 'unsafe', command: ['npm', 'exec', 'tool'] }]
    })]);
    await assert.rejects(() => loadDataset(npmManifest, { minimumCases: 1 }), /npm operation/);

    const mavenManifest = await writeDataset([validCase({
        verification: [{ name: 'unsafe', command: ['mvn', 'deploy'] }]
    })]);
    await assert.rejects(() => loadDataset(mavenManifest, { minimumCases: 1 }), /disallowed Maven goal/);
});

test('rejects git flags that write files or launch external helpers', async () => {
    const manifest = await writeDataset([validCase({
        verification: [{ name: 'unsafe', command: ['git', 'diff', '--output=outside.txt'] }]
    })]);
    await assert.rejects(() => loadDataset(manifest, { minimumCases: 1 }), /redirect git/);

    const pagerManifest = await writeDataset([validCase({
        verification: [{ name: 'unsafe', command: ['git', 'grep', '--open-files-in-pager=sh'] }]
    })]);
    await assert.rejects(() => loadDataset(pagerManifest, { minimumCases: 1 }), /redirect git/);
});

test('rejects a stale content digest', async () => {
    const manifest = await writeDataset([validCase()], { digest: `sha256:${'0'.repeat(64)}` });
    await assert.rejects(() => loadDataset(manifest, { minimumCases: 1 }), /digest mismatch/);
});

test('validation failures expose a typed error', async () => {
    const manifest = await writeDataset([validCase({ allowedPaths: ['../src/**'] })]);
    await assert.rejects(() => loadDataset(manifest, { minimumCases: 1 }), (error) => error instanceof ValidationError);
});
