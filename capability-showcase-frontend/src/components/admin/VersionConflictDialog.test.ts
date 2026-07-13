import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import VersionConflictDialog from './VersionConflictDialog.vue'

const mountOpts = { global: { stubs: { teleport: true } } }

afterEach(() => document.body.querySelectorAll('*').forEach((n) => n.remove()))

function mountDialog() {
  return mount(VersionConflictDialog, {
    props: {
      open: true,
      draft: { roles: ['admin', 'ops'], enabled: true },
      current: { roles: ['admin'], enabled: true },
      fields: [
        { key: 'roles', label: '角色' },
        { key: 'enabled', label: '启用' },
      ],
    },
    ...mountOpts,
  })
}

describe('VersionConflictDialog', () => {
  it('逐字段展示 draft vs current 差异（差异行高亮）', () => {
    const wrapper = mountDialog()
    const text = wrapper.text()
    expect(text).toContain('角色')
    expect(text).toContain('admin、ops') // 草稿
    expect(text).toContain('⚠ 差异') // roles 行有差异
    // 差异行加高亮 class
    expect(wrapper.find('.vc-row--diff').exists()).toBe(true)
  })

  it('绝不提供"无脑覆盖"按钮', () => {
    const wrapper = mountDialog()
    expect(wrapper.text()).not.toContain('覆盖')
    const labels = wrapper.findAll('button').map((b) => b.text())
    expect(labels.some((l) => l.includes('覆盖'))).toBe(false)
  })

  it('点击"加载最新" → emit reload；关闭 → emit close', async () => {
    const wrapper = mountDialog()
    await wrapper.findAll('button').find((b) => b.text().includes('加载最新'))!.trigger('click')
    expect(wrapper.emitted('reload')).toBeTruthy()

    await wrapper.find('.modal__close').trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })
})
