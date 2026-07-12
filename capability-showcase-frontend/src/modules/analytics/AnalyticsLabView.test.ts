import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useCatalogStore } from '../../stores/catalog'
import { loadCatalog } from '../../test/fixtures'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import AnalyticsLabView from './AnalyticsLabView.vue'

const RouterLink = { name: 'RouterLink', props: ['to'], template: '<a><slot /></a>' }
const mountOpts = { global: { stubs: { RouterLink } } }

function setupCatalog(): void {
  setActivePinia(createPinia())
  useCatalogStore().catalog = loadCatalog()
}

describe('AnalyticsLabView', () => {
  beforeEach(setupCatalog)
  afterEach(() => document.body.querySelectorAll('*').forEach((n) => n.remove()))

  it('着陆页渲染 Schema 浏览器 + NL2SQL 台 双区', () => {
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...mountOpts })
    const text = wrapper.text()
    expect(text).toContain('Schema 浏览器')
    expect(text).toContain('NL2SQL 台')
    // schema 浏览默认可用的诚实说明
    expect(text).toContain('只读元数据')
  })

  it('NL2SQL 已启用（ready）：闸门不再以 flag-off 锁定，暴露执行入口', () => {
    // compose 默认 NL2SQL_ENABLED=true → analytics.sql 由 flag-off 升为 ready。
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...mountOpts })
    const text = wrapper.text()
    // 不再是 flag-off：不出现「需开启 app.nl2sql.enabled」的禁用文案
    expect(text).not.toContain('app.nl2sql.enabled')
    // NL2SQL 台暴露执行入口（未填 Key 时经闸门另行提示补 Key）
    expect(text).toContain('生成并执行')
  })

  it('未填 Key 时 Schema 加载按钮禁用（诚实前置提示）', () => {
    const wrapper = mount(AnalyticsLabView, { props: { moduleId: 'analytics' }, ...mountOpts })
    expect(wrapper.text()).toContain('尚未加载 schema')
    expect(wrapper.text()).toContain('请先登录')
  })

  it('深链具体能力时聚焦单个运行器', () => {
    const wrapper = mount(AnalyticsLabView, {
      props: { moduleId: 'analytics', capId: 'analytics.schema.tables' },
      ...mountOpts,
    })
    expect(wrapper.findAllComponents(CapabilityRunner).length).toBe(1)
    // 聚焦模式不渲染工作台分区
    expect(wrapper.text()).not.toContain('NL2SQL 台')
  })

  it('未知能力 id 优雅报错', () => {
    const wrapper = mount(AnalyticsLabView, {
      props: { moduleId: 'analytics', capId: 'analytics.nope' },
      ...mountOpts,
    })
    expect(wrapper.text()).toContain('能力不存在')
  })
})
