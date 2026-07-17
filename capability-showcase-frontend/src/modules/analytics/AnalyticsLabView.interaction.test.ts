import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/errors'
import AnalyticsLabView from './AnalyticsLabView.vue'
import {
  buttonByText,
  capability,
  cleanup,
  deferred,
  RouterLinkStub,
  setupCatalog,
} from '../../test/interactionHarness'

/**
 * Analytics Lab 交互测试：schema 浏览（envelope 兼容/编码/错误隔离）+ NL2SQL（SQL/行集/兜底/curl 不泄密）。
 * 疑似 bug（issue-02 选表乱序竞态）以 skip+期望行为呈现。
 */

const mocks = vi.hoisted(() => ({ run: vi.fn() }))
vi.mock('../../api/client', async (original) => ({
  ...(await original<typeof import('../../api/client')>()),
  runCapability: mocks.run,
}))

const opts = { global: { stubs: { RouterLink: RouterLinkStub } } }
const result = (data: unknown) => ({ status: 200, data, headers: new Headers() })

async function settle(): Promise<void> {
  await flushPromises()
  await Promise.resolve()
  await flushPromises()
}

describe('AnalyticsLabView interaction', () => {
  beforeEach(() => {
    setupCatalog()
    mocks.run.mockReset()
  })
  afterEach(() => {
    vi.restoreAllMocks()
    cleanup()
  })

  it.each([
    [['orders', 'customers'], ['orders', 'customers']],
    [{ tables: [{ table: 'orders' }, { TABLE_NAME: 'legacy' }] }, ['orders', 'legacy']],
  ])('表清单兼容 string/object/envelope：%j', async (payload, names) => {
    mocks.run.mockResolvedValueOnce(result(payload))
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await buttonByText(wrapper, '加载表清单').trigger('click')
    await settle()
    expect(mocks.run).toHaveBeenCalledWith(capability('analytics.schema.tables'), {}, expect.any(Object))
    expect(wrapper.findAll('.al__table-name').map((n) => n.text())).toEqual(names)
    wrapper.unmount()
  })

  it('选择表发送原 table 值并把 array-of-objects 渲染成列结构表', async () => {
    mocks.run
      .mockResolvedValueOnce(result(['order/items 中']))
      .mockResolvedValueOnce(result([
        { name: 'id', type: 'BIGINT' },
        { name: 'tenant_id', type: 'VARCHAR' },
      ]))
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await buttonByText(wrapper, '加载表清单').trigger('click')
    await settle()
    await wrapper.get('.al__table-item').trigger('click')
    await settle()
    expect(mocks.run.mock.calls[1].slice(0, 2)).toEqual([
      capability('analytics.schema.describe'),
      { table: 'order/items 中' },
    ])
    expect(wrapper.get('.al__describe').text()).toContain('tenant_id')
    expect(wrapper.findAll('.rt__tr')).toHaveLength(2)
    wrapper.unmount()
  })

  it('NL2SQL trim question，展示 generated SQL、object rows、空值和 raw 字段', async () => {
    mocks.run.mockResolvedValueOnce(result({
      question: 'q',
      sql: 'SELECT category, COUNT(*) c FROM docs GROUP BY category',
      rowCount: 2,
      rows: [{ category: 'a', c: 2 }, { category: null, c: 1 }],
      answer: 'done',
      guardBlocked: false,
    }))
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await wrapper.get('[aria-label="NL2SQL 自然语言问题"]').setValue('  count by category  ')
    await buttonByText(wrapper, '生成并执行').trigger('click')
    await settle()
    expect(mocks.run).toHaveBeenCalledWith(
      capability('analytics.sql'),
      { question: 'count by category' },
      expect.any(Object),
    )
    expect(wrapper.get('.al__sql-code').text()).toContain('SELECT category')
    expect(wrapper.findAll('.rt__tr')).toHaveLength(2)
    expect(wrapper.get('.rt__table').text()).toContain('—')
    await buttonByText(wrapper, '原始响应').trigger('click')
    expect(wrapper.text()).toContain('guardBlocked')
    wrapper.unmount()
  })

  it.each([
    [{ columns: ['name', 'count'], rows: [['a', 2]] }, true, ['name', 'count', 'a', '2']],
    [[1, null, 'x'], true, ['value', '1', 'x']],
    [{ rows: [] }, false, ['查询成功，结果集为空']],
    [{ opaque: { x: 1 } }, false, ['opaque']],
  ])('NL2SQL 行集/兜底形态 %j', async (payload, asTable, expected) => {
    mocks.run.mockResolvedValueOnce(result(payload))
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await wrapper.get('[aria-label="NL2SQL 自然语言问题"]').setValue('q')
    await buttonByText(wrapper, '生成并执行').trigger('click')
    await settle()
    // 行集断言限定在结果表内，避免「1」「x」被页面其它文本误命中。
    const scope = asTable ? wrapper.get('.rt__table').text() : wrapper.text()
    for (const text of expected) expect(scope).toContain(text)
    wrapper.unmount()
  })

  it('schema/sql 错误各自显示，不清空另一分区的成功结果', async () => {
    mocks.run
      .mockResolvedValueOnce(result(['orders']))
      .mockRejectedValueOnce(new ApiError(404, 'missing', { message: 'table hidden' }))
      .mockRejectedValueOnce(new ApiError(503, 'down', { message: 'model offline' }))
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await buttonByText(wrapper, '加载表清单').trigger('click')
    await settle()
    await wrapper.get('.al__table-item').trigger('click')
    await settle()
    expect(wrapper.get('.al__describe [role="alert"]').text()).toContain('table hidden')
    await wrapper.get('[aria-label="NL2SQL 自然语言问题"]').setValue('q')
    await buttonByText(wrapper, '生成并执行').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('model offline')
    expect(wrapper.text()).toContain('orders')
    wrapper.unmount()
  })

  it('curl 预览只有占位凭证和当前 question，不泄露 test-key', async () => {
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await wrapper.get('[aria-label="NL2SQL 自然语言问题"]').setValue('safe q')
    await buttonByText(wrapper, '预览 curl').trigger('click')
    const curl = wrapper.get('.al__curl-code').text()
    expect(curl).toContain('/chat/sql')
    expect(curl).toContain('safe q')
    expect(curl).not.toContain('test-key')
    wrapper.unmount()
  })

  it('issue-02 回归：后到的旧 describe 响应不能覆盖新表', async () => {
    const old = deferred<ReturnType<typeof result>>()
    const fresh = deferred<ReturnType<typeof result>>()
    mocks.run
      .mockResolvedValueOnce(result(['orders', 'customers']))
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(fresh.promise)
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...opts })
    await buttonByText(wrapper, '加载表清单').trigger('click')
    await settle()
    const tables = wrapper.findAll('.al__table-item')
    await tables[0].trigger('click')
    await tables[1].trigger('click')
    fresh.resolve(result([{ name: 'customer_id' }]))
    await settle()
    old.resolve(result([{ name: 'order_id' }]))
    await settle()
    expect(wrapper.get('.al__describe-title').text()).toContain('customers')
    expect(wrapper.get('.al__describe').text()).toContain('customer_id')
    expect(wrapper.get('.al__describe').text()).not.toContain('order_id')
    wrapper.unmount()
  })
})
