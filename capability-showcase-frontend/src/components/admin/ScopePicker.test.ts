import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ScopePicker from './ScopePicker.vue'

function itemByText(wrapper: ReturnType<typeof mount>, needle: string) {
  return wrapper.findAll('label.sp__item').find((l) => l.text().includes(needle))
}

describe('ScopePicker', () => {
  it('可编辑：按域分组渲染 fieldset/legend', () => {
    const wrapper = mount(ScopePicker, { props: { modelValue: [] } })
    const legends = wrapper.findAll('legend').map((l) => l.text())
    expect(wrapper.findAll('fieldset').length).toBeGreaterThan(1)
    expect(legends).toContain('对话')
    expect(legends).toContain('平台管理')
  })

  it('未知 scope 落"其它/未知"组并保留在 emit 值', async () => {
    const wrapper = mount(ScopePicker, { props: { modelValue: ['chat', 'legacy:foo'] } })
    // 未知 scope 单独成组，原样展示
    expect(wrapper.text()).toContain('legacy:foo')
    expect(wrapper.text()).toContain('其它 / 未知')

    // 取消勾选已知 scope chat：未知 scope 仍保留在回写值里
    const chatItem = itemByText(wrapper, 'chat')
    expect(chatItem).toBeTruthy()
    await chatItem!.find('input').setValue(false)

    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const last = emitted!.at(-1)![0] as string[]
    expect(last).toContain('legacy:foo')
    expect(last).not.toContain('chat')
  })

  it('勾选未选中的已知 scope → 追加到 emit 值', async () => {
    const wrapper = mount(ScopePicker, { props: { modelValue: ['chat'] } })
    const ingestItem = itemByText(wrapper, 'ingest')
    await ingestItem!.find('input').setValue(true)
    const last = wrapper.emitted('update:modelValue')!.at(-1)![0] as string[]
    expect(last).toContain('chat')
    expect(last).toContain('ingest')
  })

  it('readonly：仅展示已选 scope，无任何交互输入', () => {
    const wrapper = mount(ScopePicker, { props: { modelValue: ['chat', 'legacy:foo'], readonly: true } })
    expect(wrapper.findAll('input').length).toBe(0)
    // 已知 scope 展示中文 label；未知 scope 原样保留
    expect(wrapper.text()).toContain('对话')
    expect(wrapper.text()).toContain('legacy:foo')
  })
})
