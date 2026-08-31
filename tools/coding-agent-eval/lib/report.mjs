import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { ValidationError } from './manifest.mjs';

export async function aggregateReports(inputDirectory, dataset) {
    const root = path.resolve(inputDirectory);
    const files = (await readdir(root, { withFileTypes: true }))
        .filter((entry) => entry.isFile() && entry.name.endsWith('.json'))
        .map((entry) => entry.name)
        .sort();
    const reports = [];
    const knownCaseIds = new Set(dataset.cases.map((entry) => entry.data.id));
    const seenCaseIds = new Set();
    for (const file of files) {
        const report = JSON.parse(await readFile(path.join(root, file), 'utf8'));
        if (report.schemaVersion !== 'coding-agent-score/v1') continue;
        if (report.toolVersion !== '1.0.0') throw new ValidationError(`${file} has an incompatible toolVersion`);
        if (report.dataset?.digest !== dataset.manifest.digest) throw new ValidationError(`${file} does not match dataset digest`);
        if (!knownCaseIds.has(report.caseId)) throw new ValidationError(`${file} references unknown case ${report.caseId}`);
        if (seenCaseIds.has(report.caseId)) throw new ValidationError(`duplicate score report for case ${report.caseId}`);
        if (!['pass', 'fail'].includes(report.verdict) || typeof report.scores?.total !== 'number') {
            throw new ValidationError(`${file} is not a complete score report`);
        }
        seenCaseIds.add(report.caseId);
        reports.push(report);
    }
    const passed = reports.filter((report) => report.verdict === 'pass').length;
    const totalScore = reports.reduce((sum, report) => sum + Number(report.scores?.total ?? 0), 0);
    return {
        schemaVersion: 'coding-agent-benchmark/v1',
        toolVersion: '1.0.0',
        dataset: {
            id: dataset.manifest.datasetId,
            version: dataset.manifest.version,
            digest: dataset.manifest.digest
        },
        generatedAt: new Date().toISOString(),
        summary: {
            evaluated: reports.length,
            passed,
            failed: reports.length - passed,
            passRate: reports.length === 0 ? 0 : Math.round(passed * 10000 / reports.length) / 100,
            averageScore: reports.length === 0 ? 0 : Math.round(totalScore * 100 / reports.length) / 100
        },
        cases: reports.map((report) => ({ caseId: report.caseId, verdict: report.verdict, total: report.scores.total }))
    };
}
