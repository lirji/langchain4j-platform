import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DynamicForm from './DynamicForm.vue'
import type { ParamSpec } from '../../types/catalog'
import { validateParams } from '../../utils/validation'
import { buildTargetUrl, buildJsonBody, buildFormData } from '../../api/client'

const allTypes: ParamSpec[] = [
  { name: 's', in: 'body', type: 'string', required: false, label: '字符串' },
  { name: 't', in: 'body', type: 'text', required: false, label: '文本' },
  { name: 'n', in: 'body', type: 'number', required: false, label: '数字' },
  { name: 'i', in: 'query', type: 'integer', required: false, label: '整数' },
  { name: 'b', in: 'body', type: 'boolean', required: false, label: '布尔' },
  { name: 'sel', in: 'body', type: 'select', required: false, label: '选择', enumValues: ['A', 'B'] },
  { name: 'f', in: 'form-data', type: 'file', required: false, label: '文件' },
  { name: 'j', in: 'body', type: 'json', required: false, label: 'JSON' },
]

describe('DynamicForm 渲染各字段类型', () => {
  it('为每种类型渲染对应控件', () => {
    const wrapper = mount(DynamicForm, { props: { params: allTypes } })
    expect(wrapper.find('input[type="text"]').exists()).toBe(true)
    expect(wrapper.find('textarea').exists()).toBe(true) // text / json 均为 textarea
    expect(wrapper.find('input[type="number"]').exists()).toBe(true)
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(true)
    expect(wrapper.find('select').exists()).toBe(true)
    expect(wrapper.find('input[type="file"]').exists()).toBe(true)
    // select 提供两个选项 + 一个「不设置」
    expect(wrapper.findAll('select option').length).toBe(3)
  })

  it('无参数时提示可直接执行', () => {
    const wrapper = mount(DynamicForm, { props: { params: [] } })
    expect(wrapper.text()).toContain('无需参数')
  })
})

describe('validateParams 校验', () => {
  it('required 空值报错', () => {
    const params: ParamSpec[] = [{ name: 'q', in: 'body', type: 'text', required: true, label: '查询' }]
    const errors = validateParams(params, { q: '' })
    expect(errors.q).toBeTruthy()
  })

  it('number 非数字与 min/max 边界', () => {
    const params: ParamSpec[] = [
      { name: 'topK', in: 'body', type: 'integer', required: false, min: 1, max: 50 },
    ]
    expect(validateParams(params, { topK: 'abc' }).topK).toBeTruthy()
    expect(validateParams(params, { topK: 0 }).topK).toBeTruthy()
    expect(validateParams(params, { topK: 99 }).topK).toBeTruthy()
    expect(validateParams(params, { topK: 5 }).topK).toBeUndefined()
  })

  it('integer 拒绝小数', () => {
    const params: ParamSpec[] = [{ name: 'n', in: 'body', type: 'integer', required: false }]
    expect(validateParams(params, { n: 1.5 }).n).toBeTruthy()
  })

  it('json 非法格式报错，合法通过', () => {
    const params: ParamSpec[] = [{ name: 'input', in: 'body', type: 'json', required: false }]
    expect(validateParams(params, { input: '{bad' }).input).toBeTruthy()
    expect(validateParams(params, { input: '{"a":1}' }).input).toBeUndefined()
  })

  it('maxLength 超限报错', () => {
    const params: ParamSpec[] = [
      { name: 's', in: 'body', type: 'string', required: false, maxLength: 3 },
    ]
    expect(validateParams(params, { s: 'toolong' }).s).toBeTruthy()
  })
})

describe('请求装配', () => {
  it('path 变量 {id} 替换 + query 拼接', () => {
    const params: ParamSpec[] = [
      { name: 'taskId', in: 'path', type: 'string', required: true },
      { name: 'chatId', in: 'query', type: 'string', required: false },
    ]
    const url = buildTargetUrl('/async/tasks/{taskId}/status', params, {
      taskId: 'abc-123',
      chatId: 'default',
    })
    expect(url).toBe('/async/tasks/abc-123/status?chatId=default')
  })

  it('edgeBaseUrl 前缀与空 query', () => {
    const params: ParamSpec[] = [{ name: 'docId', in: 'path', type: 'string', required: true }]
    const url = buildTargetUrl('/rag/documents/{docId}', params, { docId: 'd1' }, 'https://gw')
    expect(url).toBe('https://gw/rag/documents/d1')
  })

  it('buildJsonBody 仅取 in:body，并做类型转换', () => {
    const params: ParamSpec[] = [
      { name: 'message', in: 'body', type: 'text', required: true },
      { name: 'topK', in: 'body', type: 'integer', required: false },
      { name: 'input', in: 'body', type: 'json', required: false },
      { name: 'chatId', in: 'query', type: 'string', required: false },
    ]
    const body = buildJsonBody(params, {
      message: 'hi',
      topK: '5',
      input: '{"foo":"bar"}',
      chatId: 'ignored-query',
    })
    expect(body).toEqual({ message: 'hi', topK: 5, input: { foo: 'bar' } })
    expect('chatId' in body).toBe(false)
  })

  it('buildFormData 仅取 in:form-data 的 File', () => {
    const params: ParamSpec[] = [
      { name: 'file', in: 'form-data', type: 'file', required: true },
      { name: 'message', in: 'body', type: 'text', required: false },
    ]
    const file = new File(['hello'], 'a.txt', { type: 'text/plain' })
    const fd = buildFormData(params, { file, message: 'ignored' })
    expect(fd.get('file')).toBeInstanceOf(File)
    expect(fd.get('message')).toBeNull()
  })
})
