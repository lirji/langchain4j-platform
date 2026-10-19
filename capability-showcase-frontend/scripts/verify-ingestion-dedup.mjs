/** 隔离浏览器契约回归；所有业务请求被拦截，不向实际知识库上传测试资料。 */
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE_URL || 'playwright')
const output = resolve('target/knowledge-content-dedup/browser')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'chrome' })
const page = await browser.newPage({ viewport: { width: 1440, height: 1050 }, reducedMotion: 'reduce' })
const errors = [], submissions = [], reads = [], checks = []
let mode = 'READY'
const doc = { docId: 'existing-document', tenantId: 'qa', displayName: '已经上传过的部门文档与技术操作手册.pdf',
  version: 1, segmentCount: 8, sizeBytes: 16384, contentType: 'application/pdf', uploadedAt: '2026-10-19T00:00:00Z' }
const job = { jobId: 'own-original-task', documentId: doc.docId, documentVersion: 1, status: 'FAILED', sinks: {}, retryCount: 0 }
const json = (route, data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) })
page.on('pageerror', e => errors.push(e.message))
await page.route(url => url.pathname.startsWith('/auth/'), route => json(route, {}, 401))
await page.route(url => url.pathname.startsWith('/rag/'), async route => {
  const request = route.request(), path = new URL(request.url()).pathname
  if (path === '/rag/config') return json(route, { contractVersion: 2, publicEnabled: false, rag: {} })
  if (path === '/rag/ingestions' && request.method() === 'POST') {
    submissions.push(request.headers()['idempotency-key'])
    return json(route, { error: `INGESTION_DUPLICATE_${mode}`, duplicate: {
      documentId: mode === 'PROCESSING' ? null : doc.docId,
      displayName: mode === 'PROCESSING' ? null : doc.displayName,
      documentVersion: mode === 'PROCESSING' ? null : 1,
      documentAvailable: mode === 'READY', jobId: mode === 'ATTENTION' ? job.jobId : null,
    } }, 409)
  }
  if (path.startsWith('/rag/ingestions/')) { reads.push(path); return json(route, job) }
  if (path === '/rag/documents') return json(route, { items: [doc], page: 1, size: 10, total: 1, totalPages: 1 })
  if (path === `/rag/documents/${doc.docId}`) return json(route, doc)
  return json(route, [])
})
try {
  await page.goto(`${process.env.KNOWLEDGE_UI_URL || 'http://127.0.0.1:5178'}/m/rag/rag.upload.file`)
  await page.getByRole('heading', { name: '文档入库', exact: true }).waitFor()
  await page.evaluate(async () => { const { useSessionStore } = await import('/src/stores/session.ts'); useSessionStore().setApiKey('browser-fixture-only') })
  await page.locator('#ingestion-file').setInputFiles({ name: '改名后的手册.pdf', mimeType: 'application/pdf', buffer: Buffer.from('intercepted fixture') })
  await page.getByRole('button', { name: '提交入库', exact: true }).click()
  await page.getByText('相同内容已入库，本次没有新建文档或任务。可以直接查看已有文档。').waitFor()
  for (const width of [1440, 1024, 414]) {
    await page.setViewportSize({ width, height: 1050 })
    if (width === 414) await page.waitForFunction(() => document.querySelector('.app-nav').getBoundingClientRect().right <= 1)
    await page.getByRole('button', { name: '查看已有文档', exact: true }).scrollIntoViewIfNeeded()
    await page.mouse.move(5, 5)
    await page.screenshot({ path: `${output}/ready-${width}.png`, animations: 'disabled' })
    assert.equal(await page.locator('.app-main').evaluate(el => el.scrollWidth > el.clientWidth + 1), false)
  }
  await page.getByRole('button', { name: '查看已有文档', exact: true }).click()
  await page.locator('.document-facts').waitFor()
  assert.match(await page.locator('.document-facts').innerText(), /existing-document/)
  checks.push('重复文件打开已有文档；1440/1024/414 无页面溢出')
  mode = 'PROCESSING'
  await page.getByRole('button', { name: '重新检查本文件', exact: true }).click()
  await page.getByText('原任务由其他用户提交，任务详情仅对提交者开放。可稍后重新检查本文件。').waitFor()
  assert.equal(await page.getByRole('button', { name: '查看我的原任务', exact: true }).count(), 0)
  assert.deepEqual(reads, [])
  await page.screenshot({ path: `${output}/processing-414.png` })
  checks.push('他人处理中不查询任务、不显示完成入口')
  mode = 'ATTENTION'
  await page.getByRole('button', { name: '重新检查本文件', exact: true }).click()
  await page.getByRole('button', { name: '查看我的原任务', exact: true }).click()
  await page.getByRole('button', { name: '已处理原因，重试原任务', exact: true }).waitFor()
  assert.deepEqual(reads, [`/rag/ingestions/${job.jobId}`])
  assert.equal(new Set(submissions).size, 1)
  checks.push('本人失败任务提供恢复入口；重新检查保留原提交编号')
  const id = await page.locator('#ingestion-id').inputValue()
  await page.getByRole('button', { name: '更换文件', exact: true }).click()
  assert.equal(await page.locator('#ingestion-id').inputValue(), id)
  assert.equal(await page.locator('#ingestion-file').inputValue(), '')
  await page.locator('#ingestion-file').setInputFiles({ name: '改名后的手册.pdf', mimeType: 'application/pdf', buffer: Buffer.from('intercepted fixture') })
  await page.getByRole('button', { name: '提交入库', exact: true }).click()
  await page.getByRole('button', { name: '查看我的原任务', exact: true }).waitFor()
  assert.equal(new Set(submissions).size, 2)
  checks.push('更换文件清除原生选择器，重新选同一文件也能正常提交')
  assert.deepEqual(errors, [])
  await writeFile(`${output}/result.json`, JSON.stringify({ result: 'PASS', evidence: 'mocked HTTP only', checks, pageErrors: errors }, null, 2))
  console.log(JSON.stringify({ result: 'PASS', checks: checks.length, output }))
} catch (failure) {
  await page.screenshot({ path: `${output}/failure.png`, fullPage: true })
  throw failure
} finally { await browser.close() }
