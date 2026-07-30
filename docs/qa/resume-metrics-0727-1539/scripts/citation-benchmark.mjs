import fs from 'node:fs/promises';
import path from 'node:path';

const qaDir = path.resolve(import.meta.dirname, '..');
const rawDir = path.join(qaDir, 'raw');
const baseUrl = process.env.BASE_URL || 'http://localhost:18080';
const apiKey = process.env.API_KEY || 'resume-bench-key';
const count = Number(process.env.COUNT || '50');
const metadata = JSON.parse(
  await fs.readFile(path.join(qaDir, 'datasets', 'rag-cases-metadata.json')),
);

async function post(endpoint, body) {
  for (let attempt = 1; attempt <= 6; attempt += 1) {
    const response = await fetch(`${baseUrl}${endpoint}`, {
      method: 'POST',
      headers: {'X-Api-Key': apiKey, 'Content-Type': 'application/json'},
      body: JSON.stringify(body),
    });
    const text = await response.text();
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = text;
    }
    if (response.ok) return parsed;
    if (response.status !== 429 || attempt === 6) {
      throw new Error(`${endpoint} -> ${response.status}: ${text.slice(0, 500)}`);
    }
    const retryAfterSeconds = Number(response.headers.get('retry-after') || '2');
    await new Promise(resolve => setTimeout(resolve, retryAfterSeconds * 1000));
  }
  throw new Error(`${endpoint} exhausted retries`);
}

function citationIds(answer) {
  const ids = [];
  const regex = /\[doc=([^\]]+)]/g;
  for (const match of answer.matchAll(regex)) ids.push(match[1].trim());
  return ids;
}

const results = [];
for (const [index, item] of metadata.slice(0, count).entries()) {
  const retrieval = await post('/rag/query', {query: item.question, topK: 10});
  const validSourceIds = new Set(
    (retrieval.hits || []).map(hit => `${hit.displayName}#${hit.index}`),
  );
  const chat = await post(`/chat?chatId=citation-${index}`, {message: item.question});
  const answer = chat.reply || '';
  const citations = citationIds(answer);
  const valid = citations.filter(id => validSourceIds.has(id));
  const invalid = citations.filter(id => !validSourceIds.has(id));
  results.push({
    id: item.id,
    question: item.question,
    answer,
    citations,
    valid,
    invalid,
    validSourceIds: [...validSourceIds],
  });
  process.stdout.write(
    `${index + 1}/${count} citations=${citations.length} valid=${valid.length} invalid=${invalid.length}\n`,
  );
}

const totalCitations = results.reduce((sum, item) => sum + item.citations.length, 0);
const validCitations = results.reduce((sum, item) => sum + item.valid.length, 0);
const summary = {
  benchmark: 'citation-id-validity',
  cases: results.length,
  totalCitations,
  validCitations,
  invalidCitations: totalCitations - validCitations,
  citationValidity: totalCitations ? validCitations / totalCitations : null,
  answersWithCitation: results.filter(item => item.citations.length > 0).length,
  citationCoverage: results.filter(item => item.citations.length > 0).length / results.length,
};
await fs.mkdir(rawDir, {recursive: true});
await fs.writeFile(
  path.join(rawDir, 'citation-validity.json'),
  `${JSON.stringify({summary, results}, null, 2)}\n`,
);
console.log(JSON.stringify(summary, null, 2));
