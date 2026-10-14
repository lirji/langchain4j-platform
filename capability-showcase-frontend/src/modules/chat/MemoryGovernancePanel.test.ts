import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MemoryGovernancePanel from './MemoryGovernancePanel.vue'
import { buttonByText, cleanup, jsonResponse, setupCatalog } from '../../test/interactionHarness'
import { useSessionStore } from '../../stores/session'
const row = { recordId: 7, id: 'x', text: '希望使用中文', type: 'preference', createdAtEpochMs: 1,
  sourceChatId: 'chat-1', state: 'PENDING', version: 0, pinned: false, expiresAtEpochMs: null }
const state = { enabled: true, generation: 0, items: [row] }
describe('记忆治理', () => {
  beforeEach(() => setupCatalog())
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); cleanup() })
  it('按真实版本确认候选并刷新，遗忘需要确认', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse(state))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(jsonResponse({ ...state, items: [{ ...row, state: 'ACTIVE', version: 1 }] }))
    vi.stubGlobal('fetch', fetcher)
    const view = mount(MemoryGovernancePanel)
    expect(fetcher).not.toHaveBeenCalled()
    await buttonByText(view, '管理记忆').trigger('click'); await flushPromises()
    expect(view.text()).toContain('来源会话：chat-1')
    await buttonByText(view, '确认记忆').trigger('click'); await flushPromises()
    expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({ expectedVersion: 0, action: 'APPROVE' })
    expect(view.text()).toContain('已生效')
    await buttonByText(view, '遗忘').trigger('click')
    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(view.text()).toContain('确认遗忘')
    view.unmount()
  })
  it('冲突保留编辑内容且显示恢复操作', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(jsonResponse(state))
      .mockResolvedValueOnce(jsonResponse({ code: 'conflict' }, 409))
    vi.stubGlobal('fetch', fetcher)
    const view = mount(MemoryGovernancePanel)
    await buttonByText(view, '管理记忆').trigger('click'); await flushPromises()
    await buttonByText(view, '修改').trigger('click')
    await view.get('textarea').setValue('用户的新偏好')
    await view.get('form').trigger('submit'); await flushPromises()
    expect(view.get('[role=alert]').text()).toContain('请刷新后核对')
    expect((view.get('textarea').element as HTMLTextAreaElement).value).toBe('用户的新偏好')
    view.unmount()
  })
  it('身份改变丢弃旧请求结果', async () => {
    let resolve!: (r: Response) => void
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise<Response>(r => { resolve = r })))
    const view = mount(MemoryGovernancePanel)
    await buttonByText(view, '管理记忆').trigger('click')
    useSessionStore().apiKey = 'another-test-key'; await flushPromises()
    resolve(jsonResponse(state)); await flushPromises()
    expect(view.text()).not.toContain('希望使用中文')
    view.unmount()
  })
})
