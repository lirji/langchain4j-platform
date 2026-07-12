import { describe, it, expect } from 'vitest'
import { renderMarkdown } from './markdown'

describe('renderMarkdown', () => {
  it('渲染基础 markdown（标题 / 列表 / 行内代码）', () => {
    const html = renderMarkdown('# 标题\n\n- 一\n- 二\n\n`code`')
    expect(html).toContain('<h1')
    expect(html).toContain('<li>')
    expect(html).toContain('<code>code</code>')
  })

  it('渲染围栏代码块与表格（GFM）', () => {
    const html = renderMarkdown('```\nconst x = 1\n```\n\n| a | b |\n| - | - |\n| 1 | 2 |')
    expect(html).toContain('<pre>')
    expect(html).toContain('<table>')
    expect(html).toContain('<td>1</td>')
  })

  it('消毒：剥离 <script> 与内联事件处理器（禁止裸注入）', () => {
    const html = renderMarkdown('正常文本 <script>alert(1)</script> <img src=x onerror=alert(1)>')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('onerror')
    expect(html).toContain('正常文本')
  })

  it('空输入返回空串', () => {
    expect(renderMarkdown('')).toBe('')
  })
})
