import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import KnowledgeSourceDetails from './KnowledgeSourceDetails.vue'
import { knowledgeDate } from './knowledgePresentation'

describe('知识来源与解析信息', () => {
  it('区分已尝试失败与因服务不可用而跳过的增强', () => {
    const wrapper = mount(KnowledgeSourceDetails, { props: { metadata: { contextual_status: 'skipped-unavailable' } } })
    expect(wrapper.text()).toContain('已跳过后续增强并保留原文')
    expect(wrapper.text()).not.toContain('已添加检索上下文')
  })
  it('展示真实页表行与 OCR 信息，文档级告警不冒充当前页', () => {
    const wrapper = mount(KnowledgeSourceDetails, { props: { metadata: {
      source_page: '3', source_sheet: '库存表', source_row_start: '10', source_row_end: '20',
      source_extraction_method: 'native+tesseract-auto-osd', source_ocr_confidence: '87.4',
      parsing_warnings: 'FORMULA_CACHED_VALUE_NOT_RECALCULATED,PDF_OCR_APPLIED',
    } } })
    expect(wrapper.text()).toContain('库存表')
    expect(wrapper.text()).toContain('原生文字与 OCR 合并')
    expect(wrapper.text()).toContain('87.4')
    expect(wrapper.text()).toContain('不是实测准确率')
    expect(wrapper.text()).toContain('可能涉及其他页面')
    expect(wrapper.text()).toContain('未重新计算')
  })
  it('历史字段缺失如实说明，不渲染未知内部字段或执行 HTML', () => {
    const wrapper = mount(KnowledgeSourceDetails, { props: { metadata: { internal_secret: 'hidden-value' } } })
    expect(wrapper.text()).toContain('未提供来源定位')
    expect(wrapper.text()).not.toContain('hidden-value')
    const unsafe = mount(KnowledgeSourceDetails, { props: { metadata: { section: '<img src=x onerror=alert(1)>', parsing_warnings: 'FUTURE_WARNING' } } })
    expect(unsafe.find('img').exists()).toBe(false)
    expect(unsafe.text()).toContain('FUTURE_WARNING')
  })
  it('北京时间跨日显示，缺失与非法时间不伪造', () => {
    expect(knowledgeDate('2026-12-31T18:00:00Z')).toBe('2027/01/01 02:00:00')
    expect(knowledgeDate(null)).toBe('未提供')
    expect(knowledgeDate('not-a-date')).toBe('时间不可用')
  })
})
