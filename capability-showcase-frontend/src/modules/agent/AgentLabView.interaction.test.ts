import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AgentLabView from './AgentLabView.vue'
import {
  buttonByText,
  cleanup,
  jsonResponse,
  RouterLinkStub,
  setupCatalog,
  sseResponse,
} from '../../test/interactionHarness'

/**
 * Agent Lab 交互测试：14 个专用模式的 primary 字段映射、steps/async/SSE 三种结果形态、错误路径。
 * 疑似 bug（issue-07/08/09）以 skip+期望行为呈现。
 */

const opts = { global: { stubs: { RouterLink: RouterLinkStub } } }
const CASES = [
  ['同步 ReAct', '/agent/run', 'goal'],
  ['异步', '/agent/run/async', 'goal'],
  ['DAG', '/agent/dag/run', 'goal'],
  ['自动规划', '/agent/dag/plan-run', 'goal'],
  ['DAG 异步', '/agent/dag/run/async', 'goal'],
  ['规划异步', '/agent/dag/plan-run/async', 'goal'],
  ['链式', '/agent/chain', 'input'],
  ['投票', '/agent/vote', 'question'],
  ['反思', '/agent/reflexive', 'question'],
  ['反思流式', '/agent/reflexive/stream', 'question'],
  ['数据分析', '/agent/analyst/run', 'goal'],
  ['数据分析·异步', '/agent/analyst/run/async', 'goal'],
  ['业务流程', '/agent/process/run', 'goal'],
  ['业务流程·异步', '/agent/process/run/async', 'goal'],
] as const

function mode(wrapper: ReturnType<typeof mount>, exact: string) {
  const value = wrapper
    .findAll('.ag__mode')
    .find((b) => b.text().replace(/SSE|async|未启用/g, '').trim() === exact)
  if (!value) throw new Error(`mode not found: ${exact}`)
  return value
}

async function settle(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  await flushPromises()
}

describe('AgentLabView interaction', () => {
  beforeEach(() => setupCatalog())
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    cleanup()
  })

  it.each(CASES)('%s 映射到 %s 且 primary field 是 %s', async (label, path, primary) => {
    const fetchMock = vi.fn().mockImplementation((url: string) =>
      Promise.resolve(
        url === '/agent/reflexive/stream'
          ? sseResponse([
              'event: answer\ndata: {"n":1,"answer":"A"}\n\nevent: done\ndata: {"finalAnswer":"A","attempts":[],"acceptedByThreshold":true}\n\n',
            ])
          : jsonResponse(url.endsWith('/async') ? { taskId: 'job-1' } : { answer: 'ok' }),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    if (label !== '同步 ReAct') await mode(wrapper, label).trigger('click')
    await wrapper.get('[aria-label="目标输入"]').setValue(' 目标文本 ')
    if (wrapper.find('.ag__tasks').exists()) {
      await wrapper.get('.ag__tasks').setValue('[{"id":"t1","goal":"sub"}]')
    }
    if (wrapper.find('.ag__advanced input[type="text"]').exists()) {
      await wrapper.get('.ag__advanced input[type="text"]').setValue('https://example.test/hook')
    }
    if (wrapper.find('.ag__advanced input[type="number"]').exists()) {
      await wrapper.get('.ag__advanced input[type="number"]').setValue('4')
    }
    await buttonByText(wrapper, label === '反思流式' ? '开始流式' : '执行').trigger('click')
    await settle()
    const call = fetchMock.mock.calls.find(([url]) => url === path)! as [string, RequestInit]
    const body = JSON.parse(String(call[1].body)) as Record<string, unknown>
    expect(body[primary]).toBe('目标文本')
    for (const other of ['goal', 'input', 'question'].filter((x) => x !== primary)) {
      expect(body[other]).toBeUndefined()
    }
    if (path.includes('/dag/run')) expect(body.tasks).toEqual([{ id: 't1', goal: 'sub' }])
    if (path === '/agent/vote') expect(body.n).toBe(4)
    wrapper.unmount()
  })

  it('同步 steps/finalAnswer 显示领域时间线，原始响应可展开', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      answer: '最终结论',
      steps: [{ thought: '分析', action: 'order_query', actionInput: { id: 1 }, observation: 'ok' }],
      extra: 9,
    })))
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    await wrapper.get('[aria-label="目标输入"]').setValue('查订单')
    await buttonByText(wrapper, '执行').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('最终结论')
    expect(wrapper.text()).toContain('order_query')
    expect(wrapper.findAll('.st__item')).toHaveLength(1)
    await buttonByText(wrapper, '原始响应').trigger('click')
    expect(wrapper.text()).toContain('extra')
    wrapper.unmount()
  })

  it('异步成功必须抽取 taskId 并进入已提交面板，不误画 steps', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ taskId: 'task-42', status: 'PENDING' })))
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    await mode(wrapper, '异步').trigger('click')
    await wrapper.get('[aria-label="目标输入"]').setValue('g')
    await buttonByText(wrapper, '执行').trigger('click')
    await settle()
    expect(wrapper.get('.ag__async').text()).toContain('task-42')
    expect(wrapper.find('.ag__steps').exists()).toBe(false)
    const links = wrapper.findAll('a[data-to]').map((a) => a.attributes('data-to') ?? '')
    expect(links.some((to) => to.includes('agent.tasks.stream'))).toBe(true)
    wrapper.unmount()
  })

  it('反思 SSE 逐阶段渲染答案/评分/最终答案并完成', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event: attempt-start\ndata: {"n":1}\n\n',
      'event: answer\ndata: {"n":1,"answer":"draft"}\n\n',
      'event: critique\ndata: {"n":1,"aggregate":0.8,"correctness":0.8,"completeness":0.8,"clarity":0.8,"mainIssue":"more"}\n\n',
      'event: done\ndata: {"finalAnswer":"final","attempts":[],"acceptedByThreshold":true}\n\n',
    ])))
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    await mode(wrapper, '反思流式').trigger('click')
    await wrapper.get('[aria-label="目标输入"]').setValue('q')
    await buttonByText(wrapper, '开始流式').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('draft')
    expect(wrapper.text()).toContain('80%')
    expect(wrapper.text()).toContain('final')
    expect(wrapper.text()).toContain('已完成')
    wrapper.unmount()
  })

  it('HTTP 错误走 ResponseViewer 且保留状态码，不出现异步成功面板', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ message: 'agent unavailable' }, 503)))
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    await wrapper.get('[aria-label="目标输入"]').setValue('g')
    await buttonByText(wrapper, '执行').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('agent unavailable')
    expect(wrapper.text()).toContain('503')
    expect(wrapper.find('.ag__async').exists()).toBe(false)
    wrapper.unmount()
  })

  it('当前 process 能力是 ready：按钮不带 is-off 且确实可调用', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ answer: 'ok' }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    const process = mode(wrapper, '业务流程')
    expect(process.classes()).not.toContain('is-off')
    await process.trigger('click')
    await wrapper.get('[aria-label="目标输入"]').setValue('refund')
    expect(buttonByText(wrapper, '执行').attributes('disabled')).toBeUndefined()
    await buttonByText(wrapper, '执行').trigger('click')
    await settle()
    expect(fetchMock.mock.calls.some(([url]) => url === '/agent/process/run')).toBe(true)
    wrapper.unmount()
  })

  it('issue-07 回归：DAG 的 goal/tasks 任一为空都禁用，非法 JSON 显示字段错误', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    await mode(wrapper, 'DAG').trigger('click')
    // 只填 goal：tasks required 缺失 → 禁用
    await wrapper.get('[aria-label="目标输入"]').setValue('goal only')
    expect(buttonByText(wrapper, '执行').attributes('disabled')).toBeDefined()
    // 只填 tasks（goal 清空）：goal required 缺失 → 依然禁用
    await wrapper.get('[aria-label="目标输入"]').setValue('')
    await wrapper.get('.ag__tasks').setValue('[{"id":"t1","goal":"g"}]')
    expect(buttonByText(wrapper, '执行').attributes('disabled')).toBeDefined()
    // 非法 JSON：显示字段错误且禁用
    await wrapper.get('.ag__tasks').setValue('{bad')
    expect(wrapper.text()).toContain('不是合法的 JSON')
    expect(buttonByText(wrapper, '执行').attributes('disabled')).toBeDefined()
    // 合法 JSON 但非数组：同样报错
    await wrapper.get('.ag__tasks').setValue('{"id":"t1"}')
    expect(wrapper.text()).toContain('不是合法的 JSON 数组')
    expect(fetchMock).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('issue-08 回归：vote n=0/10 越界均禁用不发请求', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    await mode(wrapper, '投票').trigger('click')
    await wrapper.get('[aria-label="目标输入"]').setValue('q')
    await wrapper.get('.ag__advanced input[type="number"]').setValue('0')
    expect(buttonByText(wrapper, '执行').attributes('disabled')).toBeDefined()
    await wrapper.get('.ag__advanced input[type="number"]').setValue('10')
    expect(buttonByText(wrapper, '执行').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('采样路数 n 需为 1..9')
    expect(fetchMock).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('issue-09 回归：切模式清理旧结果，不能按新 async 模式解释旧 id', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ id: 'old', steps: ['s'] })))
    const wrapper = mount(AgentLabView, { props: { moduleId: 'agent' }, ...opts })
    await wrapper.get('[aria-label="目标输入"]').setValue('g')
    await buttonByText(wrapper, '执行').trigger('click')
    await settle()
    await mode(wrapper, '异步').trigger('click')
    expect(wrapper.find('.ag__async').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('old')
    wrapper.unmount()
  })
})
