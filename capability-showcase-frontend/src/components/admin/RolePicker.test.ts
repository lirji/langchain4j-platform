import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import RolePicker from './RolePicker.vue'

function itemByText(wrapper: ReturnType<typeof mount>, needle: string) {
  return wrapper.findAll('label.rp__item').find((l) => l.text().includes(needle))
}

describe('RolePicker', () => {
  it('渲染可选角色 + 保留未知角色（不在 options）', () => {
    const wrapper = mount(RolePicker, {
      props: { modelValue: ['ops', 'legacy-role'], options: ['ops', 'viewer'] },
    })
    // options 里的角色可选
    expect(itemByText(wrapper, 'viewer')).toBeTruthy()
    // modelValue 里、options 未含的角色单独保留展示
    expect(wrapper.text()).toContain('未知角色（保留）')
    expect(wrapper.text()).toContain('legacy-role')
  })

  it('勾选新角色 → emit 追加，且保留未知角色', async () => {
    const wrapper = mount(RolePicker, {
      props: { modelValue: ['ops', 'legacy-role'], options: ['ops', 'viewer'] },
    })
    await itemByText(wrapper, 'viewer')!.find('input').setValue(true)
    const last = wrapper.emitted('update:modelValue')!.at(-1)![0] as string[]
    expect(last).toContain('ops')
    expect(last).toContain('viewer')
    expect(last).toContain('legacy-role')
  })

  it('取消勾选已选角色 → 从 emit 移除，未知角色不受影响', async () => {
    const wrapper = mount(RolePicker, {
      props: { modelValue: ['ops', 'legacy-role'], options: ['ops', 'viewer'] },
    })
    await itemByText(wrapper, 'ops')!.find('input').setValue(false)
    const last = wrapper.emitted('update:modelValue')!.at(-1)![0] as string[]
    expect(last).not.toContain('ops')
    expect(last).toContain('legacy-role')
  })
})
