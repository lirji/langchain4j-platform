import fs from 'node:fs/promises';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '../../../..');
const qaDir = path.resolve(import.meta.dirname, '..');
const datasetsDir = path.join(qaDir, 'datasets');
const rawDir = path.join(qaDir, 'raw');
const baseUrl = process.env.BASE_URL || 'http://localhost:18080';
const apiKey = process.env.API_KEY || 'resume-bench-key';
const mode = process.argv[2] || 'eval';

async function request(endpoint, options = {}) {
  for (let attempt = 1; attempt <= 5; attempt++) {
    const started = performance.now();
    const response = await fetch(`${baseUrl}${endpoint}`, {
      ...options,
      headers: {
        'X-Api-Key': apiKey,
        'Content-Type': 'application/json',
        ...(options.headers || {}),
      },
    });
    const text = await response.text();
    const durationMs = performance.now() - started;
    let body;
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      body = text;
    }
    if (response.ok) return {status: response.status, body, durationMs};
    if (response.status === 429 && attempt < 5) {
      const seconds = Number(response.headers.get('retry-after') || body?.retryAfterSeconds || 5);
      process.stdout.write(`rate limited; retrying in ${seconds}s\n`);
      await new Promise(resolve => setTimeout(resolve, seconds * 1000));
      continue;
    }
    throw new Error(`${options.method || 'GET'} ${endpoint} -> ${response.status}: ${text.slice(0, 1000)}`);
  }
  throw new Error(`${options.method || 'GET'} ${endpoint} exhausted retries`);
}

async function ingest() {
  const manifest = JSON.parse(await fs.readFile(path.join(datasetsDir, 'rag-corpus-manifest.json')));
  const results = [];
  for (const [index, item] of manifest.entries()) {
    const text = await fs.readFile(path.join(root, item.source), 'utf8');
    const response = await request('/rag/documents', {
      method: 'POST',
      body: JSON.stringify({
        title: item.displayName,
        text,
        contentType: 'text/markdown',
        category: 'resume-benchmark',
      }),
    });
    results.push({
      source: item.source,
      displayName: item.displayName,
      status: response.status,
      durationMs: Math.round(response.durationMs),
      document: response.body,
    });
    process.stdout.write(`ingested ${index + 1}/${manifest.length} ${item.displayName} ${Math.round(response.durationMs)}ms\n`);
  }
  await fs.mkdir(rawDir, {recursive: true});
  await fs.writeFile(path.join(rawDir, 'rag-ingest.json'), `${JSON.stringify(results, null, 2)}\n`);
}

async function evaluate(name, limit) {
  const golden = JSON.parse(await fs.readFile(path.join(datasetsDir, 'rag-golden-200.json')));
  const cases = limit ? golden.cases.slice(0, limit) : golden.cases;
  const batchSize = Number(process.env.BATCH_SIZE || '20');
  const batches = [];
  const started = performance.now();
  for (let offset = 0; offset < cases.length; offset += batchSize) {
    const payload = {topK: golden.topK, cases: cases.slice(offset, offset + batchSize)};
    const response = await request('/eval/retrieval', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
    batches.push(response.body);
    process.stdout.write(`evaluated ${Math.min(offset + batchSize, cases.length)}/${cases.length}\n`);
  }
  const results = batches.flatMap(batch => batch.results || []);
  const sum = field => results.reduce((total, item) => total + Number(item[field] || 0), 0);
  const report = {
    benchmark: name,
    requestedCases: cases.length,
    wallDurationMs: Math.round(performance.now() - started),
    cases: results.length,
    avgRecall: results.length ? sum('recall') / results.length : 0,
    avgPrecision: results.length ? sum('precision') / results.length : 0,
    meanMrr: results.length ? sum('mrr') / results.length : 0,
    hitRate: results.length
      ? results.filter(item => item.hit === true).length / results.length
      : 0,
    totalDurationMs: sum('durationMs'),
    results,
    batches: batches.map(batch => ({
      cases: batch.cases,
      avgRecall: batch.avgRecall,
      avgPrecision: batch.avgPrecision,
      meanMrr: batch.meanMrr,
      hitRate: batch.hitRate,
      totalDurationMs: batch.totalDurationMs,
    })),
  };
  await fs.mkdir(rawDir, {recursive: true});
  await fs.writeFile(path.join(rawDir, `${name}.json`), `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify({
    benchmark: name,
    cases: report.cases,
    avgRecall: report.avgRecall,
    avgPrecision: report.avgPrecision,
    meanMrr: report.meanMrr,
    hitRate: report.hitRate,
    totalDurationMs: report.totalDurationMs,
    wallDurationMs: report.wallDurationMs,
  }, null, 2));
}

if (mode === 'ingest') {
  await ingest();
} else if (mode === 'pilot') {
  await evaluate(process.env.REPORT_NAME || 'rag-pilot', 20);
} else {
  await evaluate(process.env.REPORT_NAME || 'rag-eval', 0);
}
