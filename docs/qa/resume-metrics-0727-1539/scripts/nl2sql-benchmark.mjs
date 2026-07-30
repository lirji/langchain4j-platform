import fs from 'node:fs/promises';
import path from 'node:path';

const qaDir = path.resolve(import.meta.dirname, '..');
const datasetPath = path.join(qaDir, 'datasets', 'nl2sql-golden-150.json');
const rawDir = path.join(qaDir, 'raw');
const baseUrl = process.env.BASE_URL || 'http://localhost:18080';
const apiKey = process.env.API_KEY || 'dev-key-tenantA-admin';
const name = process.env.REPORT_NAME || 'nl2sql';
const pilot = process.argv.includes('--pilot');

function flatten(value) {
  if (Array.isArray(value)) return value.flatMap(flatten);
  if (value && typeof value === 'object') return Object.values(value).flatMap(flatten);
  return [value];
}

function numeric(value) {
  if (value === null || value === undefined || value === '') return null;
  const number = Number(String(value).replace(/,/g, ''));
  return Number.isFinite(number) ? number : null;
}

function matches(rows, expected) {
  const values = flatten(rows);
  if (expected.kind === 'scalar') {
    const targetNumber = numeric(expected.value);
    return values.some(value => {
      const actualNumber = numeric(value);
      if (targetNumber !== null && actualNumber !== null) {
        return Math.abs(actualNumber - targetNumber) < 0.000001;
      }
      return String(value) === String(expected.value);
    });
  }
  if (expected.kind === 'contains') {
    return expected.values.every(target => {
      const targetNumber = numeric(target);
      return values.some(value => {
        const actualNumber = numeric(value);
        if (targetNumber !== null && actualNumber !== null) {
          return Math.abs(actualNumber - targetNumber) < 0.000001;
        }
        return String(value).includes(String(target));
      });
    });
  }
  if (expected.kind === 'numberTolerance') {
    const target = Number(expected.value);
    const tolerance = Number(expected.tolerance);
    return values.some(value => {
      const actual = numeric(value);
      return actual !== null && Math.abs(actual - target) <= tolerance;
    });
  }
  return false;
}

async function runCase(item) {
  const started = performance.now();
  let status = 0;
  let body = null;
  let error = null;
  try {
    const response = await fetch(`${baseUrl}/analytics/sql`, {
      method: 'POST',
      headers: {'X-Api-Key': apiKey, 'Content-Type': 'application/json'},
      body: JSON.stringify({question: item.question}),
    });
    status = response.status;
    const text = await response.text();
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      body = text;
    }
    if (!response.ok) error = `HTTP ${status}`;
  } catch (cause) {
    error = String(cause);
  }
  const durationMs = Math.round(performance.now() - started);
  const executionSuccess = status >= 200 && status < 300 && body?.sql != null;
  const resultCorrect = executionSuccess && matches(body?.rows || [], item.expected);
  return {
    ...item,
    status,
    durationMs,
    executionSuccess,
    resultCorrect,
    guardBlocked: Boolean(body?.guardBlocked),
    sql: body?.sql ?? null,
    rows: body?.rows ?? [],
    error,
  };
}

function percentile(values, p) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.ceil(p * sorted.length) - 1];
}

const allCases = JSON.parse(await fs.readFile(datasetPath));
const cases = pilot
  ? allCases.filter((_, index) => index % 10 === 0)
  : allCases;
const results = [];
for (const [index, item] of cases.entries()) {
  const result = await runCase(item);
  results.push(result);
  process.stdout.write(
    `${index + 1}/${cases.length} ${item.id} status=${result.status} exec=${result.executionSuccess} correct=${result.resultCorrect} ${result.durationMs}ms\n`,
  );
}

const durations = results.map(item => item.durationMs);
const summary = {
  benchmark: name,
  cases: results.length,
  executionSuccesses: results.filter(item => item.executionSuccess).length,
  executionSuccessRate: results.filter(item => item.executionSuccess).length / results.length,
  correct: results.filter(item => item.resultCorrect).length,
  resultAccuracy: results.filter(item => item.resultCorrect).length / results.length,
  guardBlocked: results.filter(item => item.guardBlocked).length,
  guardBlockedRate: results.filter(item => item.guardBlocked).length / results.length,
  averageDurationMs: durations.reduce((sum, value) => sum + value, 0) / durations.length,
  p50DurationMs: percentile(durations, 0.5),
  p95DurationMs: percentile(durations, 0.95),
};

await fs.mkdir(rawDir, {recursive: true});
await fs.writeFile(
  path.join(rawDir, `${name}.jsonl`),
  `${results.map(item => JSON.stringify(item)).join('\n')}\n`,
);
await fs.writeFile(
  path.join(rawDir, `${name}-summary.json`),
  `${JSON.stringify(summary, null, 2)}\n`,
);
console.log(JSON.stringify(summary, null, 2));
