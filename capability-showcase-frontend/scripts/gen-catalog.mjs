// 由 capabilities.yml 生成静态 public/catalog.json，作为前端零后端依赖的能力目录来源。
// 在 dev/build 前自动运行（package.json 的 predev/prebuild）。
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import yaml from 'js-yaml'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const srcFile = resolve(root, 'capabilities.yml')
const outDir = resolve(root, 'public')
const outFile = resolve(outDir, 'catalog.json')

const doc = yaml.load(readFileSync(srcFile, 'utf8'))
if (!doc || !Array.isArray(doc.modules)) {
  throw new Error('capabilities.yml 结构异常：缺少 modules 数组')
}
doc.generatedAt = doc.generatedAt ?? null

mkdirSync(outDir, { recursive: true })
writeFileSync(outFile, JSON.stringify(doc, null, 2) + '\n')
console.log(`catalog.json 已生成：${doc.modules.length} 个模块 -> ${outFile}`)
