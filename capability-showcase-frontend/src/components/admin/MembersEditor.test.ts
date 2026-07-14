import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MembersEditor from './MembersEditor.vue'

describe('MembersEditor', () => {
  it('渲染当前成员 chips', () => {
    const wrapper = mount(MembersEditor, { props: { modelValue: ['alice', 'bob'] } })
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('bob')
    expect(wrapper.findAll('.me__chip').length).toBe(2)
  })

  it('输入用户名 + 添加 → emit 追加', async () => {
    const wrapper = mount(MembersEditor, { props: { modelValue: ['alice'] } })
    await wrapper.find('.me__input').setValue('carol')
    await wrapper.find('.me__add .btn').trigger('click')
    const last = wrapper.emitted('update:modelValue')!.at(-1)![0] as string[]
    expect(last).toEqual(['alice', 'carol'])
  })

  it('重复用户名不重复添加', async () => {
    const wrapper = mount(MembersEditor, { props: { modelValue: ['alice'] } })
    await wrapper.find('.me__input').setValue('alice')
    await wrapper.find('.me__add .btn').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined() // 未触发变更
  })

  it('移除成员 → emit 去除', async () => {
    const wrapper = mount(MembersEditor, { props: { modelValue: ['alice', 'bob'] } })
    await wrapper.findAll('.me__chip-x')[0].trigger('click')
    const last = wrapper.emitted('update:modelValue')!.at(-1)![0] as string[]
    expect(last).toEqual(['bob'])
  })
})
