/** 前端浏览器回归：仅拦截测试契约数据，不访问业务库、OCR 或模型服务。 */
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE_URL || 'playwright')
const base = process.env.KNOWLEDGE_UI_URL || 'http://127.0.0.1:5178'
const output = resolve('target/knowledge-ingestion-ui/browser')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'chrome' })
const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, timezoneId: 'Pacific/Honolulu', reducedMotion: 'reduce' })
const page = await context.newPage()
const errors = [], submits = [], checks = []
page.on('pageerror', error => errors.push(error.message))
const doc = { docId: 'qa-source-document', tenantId: 'qa', displayName: '企业文档解析验收：扫描件、工作表与技术手册', version: 1, segmentCount: 18, sizeBytes: 16384, contentType: 'application/pdf', category: '验收资料', uploadedAt: '2026-10-19T01:02:03Z' }
const job = { jobId: 'qa-job-20261019', documentId: doc.docId, documentVersion: 1, status: 'MANUAL_REVIEW', sinks: { VECTOR: 'PENDING', ELASTICSEARCH: 'PENDING', AUTHORIZATION: 'PENDING', REGISTRY: 'PENDING' }, errorCode: 'DOCUMENT_OCR_UNAVAILABLE', failedStage: 'PREPARE', retryable: false, retryCount: 0, nextRetryAt: null, traceId: 'qa-trace-20261019' }
let retried = false
async function capture(name, width, selector) {
  await page.mouse.move(5, 5)
  await page.setViewportSize({ width, height: 1050 })
  if (width === 414) await page.waitForFunction(() => document.querySelector('.app-nav').getBoundingClientRect().right <= 1)
  await page.locator(selector).evaluate(el => el.scrollIntoView({ block: 'start' }))
  await page.screenshot({ path: `${output}/${name}-${width}.png`, animations: 'disabled' })
  assert.equal(await page.locator('.app-main').evaluate(el => el.scrollWidth > el.clientWidth + 1), false, `${name} viewport ${width} overflows main`)
}
const json = (route, data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) })
await page.route(url => url.pathname.startsWith('/auth/'), route => json(route, { error: 'test_session' }, 401))
await page.route(url => url.pathname.startsWith('/rag/'), async route => {
  const request = route.request(), path = new URL(request.url()).pathname
  if (path === '/rag/config') return json(route, { contractVersion: 2, publicEnabled: true, sharedImagesSupported: false, rag: { semantic: true, embeddingProvider: 'test-fixture', embeddingModel: 'test-fixture', vectorStoreProvider: 'test-fixture', esHybridEnabled: true, fusionStrategy: 'rrf', graphEnabled: false, keywordHybridEnabled: true, multimodalEnabled: false } })
  if (path === '/rag/ingestions' && request.method() === 'POST') {
    submits.push({ key: request.headers()['idempotency-key'], body: request.postData() })
    if (submits.length === 1) return route.abort('failed')
    return json(route, job, 202)
  }
  if (path === `/rag/ingestions/${job.jobId}/retry`) { retried = true; return json(route, { ...job, status: 'PROCESSING', errorCode: null }, 202) }
  if (path === `/rag/ingestions/${job.jobId}`) return json(route, retried ? { ...job, status: 'READY', errorCode: null, failedStage: null, sinks: { VECTOR: 'SUCCEEDED', ELASTICSEARCH: 'SUCCEEDED', REGISTRY: 'SUCCEEDED' } } : job)
  if (path === '/rag/documents') return json(route, { items: [doc], page: 1, size: 10, total: 1, totalPages: 1 })
  if (path === `/rag/documents/${doc.docId}`) return json(route, doc)
  if (path === '/rag/query') return json(route, { hits: [{ docId: doc.docId, displayName: doc.displayName, score: .91, category: doc.category, index: '7', source: 'vector', version: '1', visibility: 'tenant', text: '退款规则：需保留原始凭证。\n    示例代码保留缩进。\n扫描页中的表格数值请对照原文件核验。', sourceMetadata: { source_format: 'pdf', source_kind: 'page', source_page: '3', section: '退款处理流程', source_extraction_method: 'native+tesseract-auto-osd', source_native_chars: '108', source_ocr_chars: '326', source_ocr_confidence: '87.4', chunking_actual_strategy: 'structure', parsing_warnings: 'PDF_OCR_APPLIED,PDF_NATIVE_OCR_MERGED_READING_ORDER_UNVERIFIED' } }] })
  return json(route, [], 200)
})

try {
  await page.goto(`${base}/m/rag/rag.upload.file`)
  await page.getByRole('heading', { name: '文档入库', exact: true }).waitFor()
  // 测试身份只存在于隔离浏览器内存，不把模拟凭证写入页面或本地配置。
  await page.evaluate(async () => { const { useSessionStore } = await import('/src/stores/session.ts'); useSessionStore().setApiKey('browser-fixture-only') })
  await page.locator('#ingestion-id').fill(doc.docId)
  await page.locator('#ingestion-name').fill(doc.displayName)
  await page.locator('#ingestion-file').setInputFiles({ name: '企业扫描资料.pdf', mimeType: 'application/pdf', buffer: Buffer.from('fixture: transport intercepted') })
  await page.getByRole('button', { name: '提交入库', exact: true }).click()
  await page.getByRole('button', { name: '确认原提交结果' }).waitFor()
  assert.equal(await page.locator('#ingestion-id').isDisabled(), true)
  await page.getByRole('button', { name: '确认原提交结果' }).click()
  await page.getByRole('heading', { name: '需要人工处理' }).waitFor()
  assert.equal(submits.length, 2); assert.equal(submits[0].key, submits[1].key)
  assert.ok(submits[0].body.includes(doc.docId)); checks.push('断网重发复用幂等键与文档身份')
  for (const width of [1440, 1024, 414]) {
    await capture('upload', width, '.rag__page-heading')
    await capture('task', width, '.ki-detail')
  }
  await page.getByRole('button', { name: '已处理原因，重试原任务' }).click()
  // 状态变化会重新排布按钮，移开旧指针，避免停在悬浮位移边缘反复触发 hover。
  await page.mouse.move(5, 5)
  await page.getByRole('heading', { name: '已可检索', exact: true }).waitFor()
  await page.getByRole('button', { name: '查看已入库文档' }).click()
  await page.locator('.document-facts').waitFor()
  assert.match(await page.locator('.document-facts').innerText(), /2026\/10\/19 09:02:03/)
  checks.push('人工恢复→READY→文档详情，跨时区展示北京时间')
  await page.locator('.rag__nav').getByText('知识检索', { exact: true }).click()
  await page.getByRole('textbox', { name: '检索查询' }).fill('退款')
  await page.getByRole('button', { name: '检索', exact: true }).focus()
  await page.keyboard.press('Enter')
  await page.getByRole('heading', { name: '来源与解析信息' }).waitFor()
  assert.match(await page.locator('.source-detail').innerText(), /不是实测准确率/)
  assert.equal(await page.locator('.rag__hit-text').evaluate(el => getComputedStyle(el).whiteSpace), 'pre-wrap')
  for (const width of [1440, 1024, 414]) {
    await capture('retrieval', width, '.rag__hits')
    await capture('warnings', width, '.source-warnings')
  }
  checks.push('检索深链显示页码、OCR 评分与文档告警；宽中窄屏无页面溢出')
  await page.locator('.rag__nav').getByText('文档库', { exact: true }).click()
  await page.getByRole('button', { name: '刷新文档', exact: true }).click()
  await page.locator('.rag__doc').waitFor()
  for (const width of [1440, 1024, 414]) await capture('documents', width, '.rag__doctable')
  await page.locator('.rag__doc').getByRole('button', { name: '上传新版本' }).click()
  assert.equal(await page.locator('#ingestion-id').inputValue(), doc.docId)
  assert.equal(await page.locator('#ingestion-version').inputValue(), '2')
  checks.push('文档列表上传新版本沿用原 ID 并递增已发布版本')
  assert.deepEqual(errors, [])
  await writeFile(`${output}/result.json`, JSON.stringify({ result: 'PASS', evidence: 'isolated mocked HTTP contracts; no live model/OCR/business writes', viewports: [1440, 1024, 414], checks, pageErrors: errors }, null, 2))
  console.log(JSON.stringify({ result: 'PASS', checks: checks.length, output }))
} catch (error) {
  await page.screenshot({ path: `${output}/failure.png`, fullPage: true })
  await writeFile(`${output}/failure.txt`, `${error}\nPage errors: ${JSON.stringify(errors)}`)
  throw error
} finally { await browser.close() }
