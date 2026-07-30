import fs from 'node:fs/promises';
import path from 'node:path';

const qaDir = path.resolve(import.meta.dirname, '..');
const rawDir = path.join(qaDir, 'raw');
const baseUrl = process.env.BASE_URL || 'http://localhost:18080';
const apiKey = process.env.API_KEY || 'resume-bench-key';
const count = Number(process.env.COUNT || '30');
const metadata = JSON.parse(
  await fs.readFile(path.join(qaDir, 'datasets', 'rag-cases-metadata.json')),
);
const questions = metadata.slice(0, count).map(item => item.question);

async function request(endpoint, method = 'GET', body) {
  for (let attempt = 1; attempt <= 6; attempt++) {
    const started = performance.now();
    const response = await fetch(`${baseUrl}${endpoint}`, {
      method,
      headers: {'X-Api-Key': apiKey, 'Content-Type': 'application/json'},
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    if (response.status !== 429) {
      return {
        status: response.status,
        durationMs: Math.round(performance.now() - started),
        body: text,
      };
    }
    let retryAfter = Number(response.headers.get('retry-after') || 5);
    try {
      retryAfter = Number(JSON.parse(text).retryAfterSeconds || retryAfter);
    } catch {
      // keep header/default
    }
    process.stdout.write(`rate limited; retrying in ${retryAfter}s\n`);
    await new Promise(resolve => setTimeout(resolve, retryAfter * 1000));
  }
  throw new Error(`${method} ${endpoint}: exhausted rate-limit retries`);
}

function percentile(values, p) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.ceil(p * sorted.length) - 1];
}

const cold = [];
const warm = [];
for (const [index, question] of questions.entries()) {
  // 每一对开始前清桶，保证 cold 不会被前一个相似问题提前命中。
  await request('/chat/cache', 'DELETE');
  const first = await request(`/chat?chatId=cache-cold-${index}`, 'POST', {message: question});
  cold.push({question, ...first});
  process.stdout.write(`cold ${index + 1}/${questions.length} ${first.status} ${first.durationMs}ms\n`);
  const second = await request(`/chat?chatId=cache-warm-${index}`, 'POST', {message: question});
  warm.push({question, ...second});
  process.stdout.write(`warm ${index + 1}/${questions.length} ${second.status} ${second.durationMs}ms\n`);
}

function stats(items) {
  const values = items.map(item => item.durationMs);
  return {
    count: values.length,
    averageDurationMs: values.reduce((sum, value) => sum + value, 0) / values.length,
    p50DurationMs: percentile(values, 0.5),
    p95DurationMs: percentile(values, 0.95),
  };
}

const summary = {
  benchmark: 'semantic-cache-exact-repeat',
  cold: stats(cold),
  warm: stats(warm),
  speedup: stats(cold).averageDurationMs / stats(warm).averageDurationMs,
};
await fs.mkdir(rawDir, {recursive: true});
await fs.writeFile(
  path.join(rawDir, 'cache-latency.json'),
  `${JSON.stringify({summary, cold, warm}, null, 2)}\n`,
);
console.log(JSON.stringify(summary, null, 2));
