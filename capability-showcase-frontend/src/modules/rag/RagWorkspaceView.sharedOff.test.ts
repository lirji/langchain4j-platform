import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RagWorkspaceView from './RagWorkspaceView.vue'
import { cleanup, jsonResponse, RouterLinkStub, setupCatalog } from '../../test/interactionHarness'

/**
 * 共享库构建期 kill switch（VITE_SHARED_KB_UI_ENABLED=false）分支：
 * 即使后端运行时 publicEnabled=true，构建期关闭时共享 tab 与共享上传入口都不得出现（双控之构建侧）。
 * 独立文件：SHARED_KB_UI_ENABLED 是 import-time 常量，须经模块级 mock 覆盖，不能与普通 RAG 文件共享模块缓存。
 */

vi.mock('../../config', async (original) => ({
  ...(await original<typeof import('../../config')>()),
  SHARED_KB_UI_ENABLED: false,
}))

const opts = { global: { stubs: { RouterLink: RouterLinkStub } } }

async function settle(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  await flushPromises()
}

describe('RagWorkspaceView 构建期共享库开关 off', () => {
  beforeEach(() => setupCatalog())
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    cleanup()
  })

  it('运行时 publicEnabled=true 也不出现共享 tab / 共享上传入口（构建侧 kill switch 优先）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      if (url === '/rag/config')
        return Promise.resolve(jsonResponse({
          contractVersion: 2,
          publicEnabled: true,
          sharedImagesSupported: false,
          rag: null,
        }))
      return Promise.resolve(jsonResponse({ items: [], page: 1, size: 10, total: 0, totalPages: 1 }))
    }))
    const wrapper = mount(RagWorkspaceView, { props: { moduleId: 'rag' }, ...opts })
    await settle()
    expect(wrapper.text()).not.toContain('共享知识库')
    expect(wrapper.find('.rag__uplabel--shared').exists()).toBe(false)
    // 租户库入库入口仍在
    expect(wrapper.text()).toContain('当前租户库')
    wrapper.unmount()
  })
})
