import fs from 'node:fs/promises';
import path from 'node:path';

const qaDir = path.resolve(import.meta.dirname, '..');
const datasetPath = path.join(qaDir, 'datasets', 'agent-goals-20.json');
const rawDir = path.join(qaDir, 'raw');
const baseUrl = process.env.BASE_URL || 'http://localhost:18080';
const apiKey = process.env.API_KEY || 'resume-bench-key';
const name = process.env.REPORT_NAME || 'agent';
const topology = process.env.TOPOLOGY || 'parallel';
const repeats = Number(process.env.REPEATS || '1');
const limit = Number(process.env.LIMIT || '20');
const cacheBust = process.env.CACHE_BUST === 'true';

const descriptions = [
  '梳理目标涉及的核心架构和组件职责，给出准确事实清单',
  '分析目标涉及的安全、租户隔离和权限边界',
  '分析目标涉及的质量指标、测试方法和验收口径',
  '识别实现中的风险、故障场景和降级策略',
  '综合架构与安全分析，形成可执行的实施建议',
  '综合质量与风险分析，形成验证和上线检查清单',
];

function tasks(kind) {
  if (kind === 'linear') {
    return descriptions.map((description, index) => ({
      id: `t${index + 1}`,
      description,
      dependsOn: index === 0 ? [] : [`t${index}`],
    }));
  }
  return descriptions.map((description, index) => ({
    id: `t${index + 1}`,
    description,
    dependsOn: index < 4 ? [] : (index === 4 ? ['t1', 't2'] : ['t3', 't4']),
  }));
}

async function run(item, repetition) {
  const evaluatedGoal = cacheBust
    ? `${item.goal}\n\n评测样本标识：${name}-${topology}-${item.id}-${repetition}`
    : item.goal;
  const started = performance.now();
  let status = 0;
  let body = null;
  let error = null;
  try {
    const response = await fetch(`${baseUrl}/agent/dag/run`, {
      method: 'POST',
      headers: {'X-Api-Key': apiKey, 'Content-Type': 'application/json'},
      body: JSON.stringify({goal: evaluatedGoal, tasks: tasks(topology)}),
    });
    status = response.status;
    const text = await response.text();
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      body = text;
    }
    if (!response.ok) error = `HTTP ${status}: ${text.slice(0, 500)}`;
  } catch (cause) {
    error = String(cause);
  }
  const durationMs = Math.round(performance.now() - started);
  const answer = body?.synthesis?.finalAnswer || '';
  const requiredHits = item.required.filter(term =>
    answer.toLocaleLowerCase().includes(term.toLocaleLowerCase()));
  const forbiddenHits = item.forbidden.filter(term =>
    answer.toLocaleLowerCase().includes(term.toLocaleLowerCase()));
  const rubricPassed = requiredHits.length === item.required.length && forbiddenHits.length === 0;
  const attempts = Array.isArray(body?.attempts) ? body.attempts : [];
  const lastAttempt = attempts.at(-1);
  return {
    id: item.id,
    goal: item.goal,
    cacheBust,
    topology,
    repetition,
    status,
    durationMs,
    levels: body?.levels ?? null,
    acceptedByThreshold: body?.acceptedByThreshold ?? null,
    aggregate: lastAttempt?.aggregate ?? null,
    attemptCount: attempts.length,
    rubricPassed,
    required: item.required,
    requiredHits,
    forbiddenHits,
    answer,
    error,
  };
}

function percentile(values, p) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.ceil(p * sorted.length) - 1];
}

const goals = JSON.parse(await fs.readFile(datasetPath));
const selected = goals.slice(0, limit);
const results = [];
for (const item of selected) {
  for (let repetition = 1; repetition <= repeats; repetition++) {
    const result = await run(item, repetition);
    results.push(result);
    process.stdout.write(
      `${results.length}/${selected.length * repeats} ${item.id} ${topology} status=${result.status} rubric=${result.rubricPassed} accepted=${result.acceptedByThreshold} ${result.durationMs}ms\n`,
    );
  }
}

const successful = results.filter(item => item.status >= 200 && item.status < 300);
const durations = successful.map(item => item.durationMs);
const summary = {
  benchmark: name,
  topology,
  cases: results.length,
  httpSuccesses: successful.length,
  rubricPassed: results.filter(item => item.rubricPassed).length,
  rubricCompletionRate: results.filter(item => item.rubricPassed).length / results.length,
  criticAccepted: results.filter(item => item.acceptedByThreshold === true).length,
  criticAcceptanceRate: results.filter(item => item.acceptedByThreshold === true).length / results.length,
  replanned: results.filter(item => item.attemptCount > 1).length,
  averageDurationMs: durations.length
    ? durations.reduce((sum, value) => sum + value, 0) / durations.length
    : null,
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
