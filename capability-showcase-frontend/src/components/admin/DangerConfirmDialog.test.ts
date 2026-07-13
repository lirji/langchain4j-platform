import { describe, it, expect, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DangerConfirmDialog from './DangerConfirmDialog.vue'

const mountOpts = { global: { stubs: { teleport: true } } }

afterEach(() => document.body.querySelectorAll('*').forEach((n) => n.remove()))

describe('DangerConfirmDialog', () => {
  it('requireText 非空时：需键入匹配文本才允许 confirm', async () => {
    const wrapper = mount(DangerConfirmDialog, {
      props: { open: true, title: '删除角色 ops', message: '不可撤销', requireText: 'ops' },
      ...mountOpts,
    })
    const confirmBtn = wrapper.find('.btn--danger')
    // 初始禁用
    expect(confirmBtn.attributes('disabled')).toBeDefined()

    // 键入错误文本仍禁用
    await wrapper.find('input.form-control').setValue('wrong')
    expect(wrapper.find('.btn--danger').attributes('disabled')).toBeDefined()

    // 键入匹配文本 → 可确认
    await wrapper.find('input.form-control').setValue('ops')
    expect(wrapper.find('.btn--danger').attributes('disabled')).toBeUndefined()
    await wrapper.find('.btn--danger').trigger('click')
    expect(wrapper.emitted('confirm')).toBeTruthy()
  })

  it('无 requireText：直接可 confirm', async () => {
    const wrapper = mount(DangerConfirmDialog, {
      props: { open: true, title: '删除用户', message: '不可撤销' },
      ...mountOpts,
    })
    const confirmBtn = wrapper.find('.btn--danger')
    expect(confirmBtn.attributes('disabled')).toBeUndefined()
    await confirmBtn.trigger('click')
    expect(wrapper.emitted('confirm')).toBeTruthy()
  })

  it('Esc → 触发 cancel（focus trap，open false→true 装配监听）', async () => {
    const wrapper = mount(DangerConfirmDialog, {
      props: { open: false, title: '删除用户', message: '不可撤销' },
      ...mountOpts,
    })
    await wrapper.setProps({ open: true })
    await flushPromises()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('cancel')).toBeTruthy()
  })

  it('点击取消按钮 → cancel', async () => {
    const wrapper = mount(DangerConfirmDialog, {
      props: { open: true, title: '删除用户', message: '不可撤销' },
      ...mountOpts,
    })
    await wrapper.findAll('button').find((b) => b.text() === '取消')!.trigger('click')
    expect(wrapper.emitted('cancel')).toBeTruthy()
  })
})
