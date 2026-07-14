import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GroupPicker from './GroupPicker.vue'

function itemByText(wrapper: ReturnType<typeof mount>, needle: string) {
  return wrapper.findAll('label.gp__item').find((l) => l.text().includes(needle))
}

describe('GroupPicker', () => {
  it('渲染可选组 + 保留未知组（不在 options）', () => {
    const wrapper = mount(GroupPicker, {
      props: { modelValue: ['ops-team', 'legacy-group'], options: ['ops-team', 'sales'] },
    })
    expect(itemByText(wrapper, 'sales')).toBeTruthy()
    expect(wrapper.text()).toContain('未知用户组（保留）')
    expect(wrapper.text()).toContain('legacy-group')
  })

  it('勾选新组 → emit 追加，且保留未知组', async () => {
    const wrapper = mount(GroupPicker, {
      props: { modelValue: ['ops-team', 'legacy-group'], options: ['ops-team', 'sales'] },
    })
    await itemByText(wrapper, 'sales')!.find('input').setValue(true)
    const last = wrapper.emitted('update:modelValue')!.at(-1)![0] as string[]
    expect(last).toContain('ops-team')
    expect(last).toContain('sales')
    expect(last).toContain('legacy-group')
  })

  it('取消勾选已选组 → 从 emit 移除，未知组不受影响', async () => {
    const wrapper = mount(GroupPicker, {
      props: { modelValue: ['ops-team', 'legacy-group'], options: ['ops-team', 'sales'] },
    })
    await itemByText(wrapper, 'ops-team')!.find('input').setValue(false)
    const last = wrapper.emitted('update:modelValue')!.at(-1)![0] as string[]
    expect(last).not.toContain('ops-team')
    expect(last).toContain('legacy-group')
  })
})
