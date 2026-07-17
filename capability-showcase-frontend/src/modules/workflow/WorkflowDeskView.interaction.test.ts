import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import WorkflowDeskView from './WorkflowDeskView.vue'
import { buttonByText, capability, cleanup, jsonResponse, setupCatalog } from '../../test/interactionHarness'

/**
 * Workflow 审批工作台交互测试：发起串联、待办主从、认领/取消认领/通过/驳回、boolean false 保留。
 * 一次性调用走 vi.stubGlobal('fetch') 顺序 mock（模块级 mock api/client 存在 importActual 双实例问题）。
 * 疑似 bug（issue-01 错误/成功消息被刷新吞掉）以 skip+期望行为呈现。
 * 注意：任务行按钮文本含「未认领/已认领」，动作按钮必须限定在 .wf__detail-actions 作用域内查找。
 */

const task = (assignee?: string) => ({
  taskId: 't-1',
  name: '退款审批',
  instanceId: 'wf-1',
  priority: 'P0-high',
  summary: 'refund 100',
  assignee,
})

async function settle(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  await flushPromises()
}

/** 详情卡动作按钮（认领/取消认领/通过/驳回）——避免误命中任务行里的「未认领/已认领」文本。 */
function detailButton(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper
    .get('.wf__detail-actions')
    .findAll('button')
    .find((b) => b.text().includes(text) && (text !== '认领' || !b.text().includes('取消')))
  if (!button) throw new Error(`detail action button missing: ${text}`)
  return button
}

describe('WorkflowDeskView interaction', () => {
  beforeEach(() => setupCatalog())
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    cleanup()
  })

  it('StartResult 只接受对象，展示 dedupe/instance/task/status/reply', async () => {
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    const runner = wrapper
      .findAllComponents(CapabilityRunner)
      .find((r) => (r.props('cap') as { id: string }).id === 'workflow.refund.start')!
    runner.vm.$emit('result', {
      cap: capability('workflow.refund.start'),
      status: 200,
      data: {
        instanceId: 'wf-1',
        taskId: 't-1',
        status: 'PENDING_APPROVAL',
        reply: '待审批',
        deduplicated: true,
      },
    })
    await settle()
    expect(wrapper.get('.wf__started').text()).toContain('wf-1')
    expect(wrapper.get('.wf__started').text()).toContain('t-1')
    expect(wrapper.get('.wf__started').text()).toContain('去重命中')
    expect(wrapper.get('.wf__started').text()).toContain('待审批')
    wrapper.unmount()
  })

  it('刷新待办 GET /workflow/tasks，解析数组、过滤无 taskId、自动选首项并映射优先级', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse([task(), { name: 'invalid' }, null]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    await buttonByText(wrapper, '刷新待办').trigger('click')
    await settle()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/workflow/tasks')
    expect(init.method).toBe('GET')
    expect(new Headers(init.headers).get('X-Api-Key')).toBe('test-key')
    expect(wrapper.findAll('.wf__task')).toHaveLength(1)
    expect(wrapper.get('.wf__task').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('.wf__prio').text()).toBe('P0')
    expect(wrapper.get('.wf__prio').attributes('data-tone')).toBe('danger')
    expect(wrapper.get('.wf__detail-card').text()).toContain('refund 100')
    wrapper.unmount()
  })

  it('claim/unclaim 发送精确 taskId，成功后刷新并呈现服务端 assignee', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse([task()]))
      .mockResolvedValueOnce(jsonResponse(task('alice')))
      .mockResolvedValueOnce(jsonResponse([task('alice')]))
      .mockResolvedValueOnce(jsonResponse(null, 204))
      .mockResolvedValueOnce(jsonResponse([task()]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    await buttonByText(wrapper, '刷新待办').trigger('click')
    await settle()
    await detailButton(wrapper, '认领').trigger('click')
    await settle()
    const [claimUrl, claimInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(claimUrl).toBe('/workflow/tasks/t-1/claim')
    expect(claimInit.method).toBe('POST')
    expect(wrapper.get('.wf__detail-card').text()).toContain('@alice')
    await detailButton(wrapper, '取消认领').trigger('click')
    await settle()
    const [unclaimUrl, unclaimInit] = fetchMock.mock.calls[3] as [string, RequestInit]
    expect(unclaimUrl).toBe('/workflow/tasks/t-1/unclaim')
    expect(unclaimInit.method).toBe('POST')
    expect(wrapper.get('.wf__detail-card').text()).toContain('未认领')
    wrapper.unmount()
  })

  it('驳回保留 approved:false、trim comment，成功清输入并刷新移除已完成任务', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse([task('alice')]))
      .mockResolvedValueOnce(jsonResponse({ instanceId: 'wf-1', status: 'REJECTED', approved: false }))
      .mockResolvedValueOnce(jsonResponse([]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    await buttonByText(wrapper, '刷新待办').trigger('click')
    await settle()
    const comment = wrapper.get('[aria-label="任务 t-1 审批意见"]')
    await comment.setValue('  evidence missing  ')
    await detailButton(wrapper, '驳回').trigger('click')
    await settle()
    const [completeUrl, completeInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(completeUrl).toBe('/workflow/tasks/t-1/complete')
    // 深相等：approved:false 不能因 falsy 被省略，comment 必须 trim。
    expect(JSON.parse(String(completeInit.body))).toEqual({ approved: false, comment: 'evidence missing' })
    expect((comment.element as HTMLTextAreaElement).value).toBe('')
    expect(wrapper.text()).toContain('没有待办任务')
    wrapper.unmount()
  })

  it('无凭证时自定义动作按钮禁用且不会调用 API', async () => {
    setupCatalog('')
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    expect(buttonByText(wrapper, '刷新待办').attributes('disabled')).toBeDefined()
    await buttonByText(wrapper, '刷新待办').trigger('click')
    expect(fetchMock).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('issue-01 回归：claim 409 后即使 inbox 刷新成功，动作错误仍保留', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse([task()]))
      .mockResolvedValueOnce(jsonResponse({ message: '已被 bob 认领' }, 409))
      .mockResolvedValueOnce(jsonResponse([task('bob')]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    await buttonByText(wrapper, '刷新待办').trigger('click')
    await settle()
    await detailButton(wrapper, '认领').trigger('click')
    await settle()
    expect(wrapper.get('[role="alert"]').text()).toContain('已被 bob 认领')
    expect(wrapper.get('.wf__detail-card').text()).toContain('@bob')
    wrapper.unmount()
  })

  it('issue-01 回归：complete 成功提示不被随后 refresh 清掉', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse([task()]))
      .mockResolvedValueOnce(jsonResponse({ approved: true }))
      .mockResolvedValueOnce(jsonResponse([]))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(WorkflowDeskView, { props: { moduleId: 'workflow' } })
    await buttonByText(wrapper, '刷新待办').trigger('click')
    await settle()
    await detailButton(wrapper, '通过').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('任务 t-1 已通过')
    wrapper.unmount()
  })
})
