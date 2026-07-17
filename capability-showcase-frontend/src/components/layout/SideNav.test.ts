import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { useCatalogStore } from '../../stores/catalog'
import { useFavoritesStore } from '../../stores/favorites'
import { useUiStore } from '../../stores/ui'
import { loadCatalog } from '../../test/fixtures'
import SideNav from './SideNav.vue'

const stub = { template: '<div />' }
function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'overview', component: stub },
      { path: '/m/:moduleId', name: 'module', component: stub },
      { path: '/m/:moduleId/:capId', name: 'capability', component: stub },
    ],
  })
}

async function mountNav(path = '/') {
  const router = makeRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(SideNav, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  setActivePinia(createPinia())
  useCatalogStore().catalog = loadCatalog()
  localStorage.clear()
})

describe('SideNav · 结构渲染', () => {
  it('渲染全部 4 个语义分组、模块名与计数', async () => {
    const wrapper = await mountNav('/')
    const text = wrapper.text()
    expect(text).toContain('对话与检索')
    expect(text).toContain('智能体与编排')
    expect(text).toContain('多模态')
    expect(text).toContain('平台工程与互操作')
    // 模块中文主名（拆分自双语标题）
    expect(text).toContain('对话')
    expect(text).toContain('智能体')
    expect(text).toContain('互操作与评测')
    // 9 个模块行
    expect(wrapper.findAll('.mod').length).toBe(9)
  })

  it('底部渲染五态图例（非纯颜色，有文字）', async () => {
    const wrapper = await mountNav('/')
    const legend = wrapper.find('.nav__legend').text()
    expect(legend).toContain('就绪')
    expect(legend).toContain('需授权')
    expect(legend).toContain('已锁定')
  })
})

describe('SideNav · 唯一 active 与祖先可见', () => {
  it('总览路由：总览高亮，无模块 current（不双亮）', async () => {
    const wrapper = await mountNav('/')
    expect(wrapper.find('.nav__overview.active').exists()).toBe(true)
    expect(wrapper.find('.mod--current').exists()).toBe(false)
  })

  it('模块深链：对应模块 current + 自动展开其能力；总览不再高亮', async () => {
    const wrapper = await mountNav('/m/chat')
    expect(wrapper.find('.nav__overview.active').exists()).toBe(false)
    const current = wrapper.findAll('.mod--current')
    expect(current.length).toBe(1)
    expect(current[0].text()).toContain('对话')
    // 当前模块自动展开 → 其能力行出现
    const firstCap = loadCatalog().modules.find((m) => m.id === 'chat')!.capabilities[0]
    expect(wrapper.text()).toContain(firstCap.title)
  })

})

describe('SideNav · 收藏', () => {
  it('收藏能力渲染在收藏分组（扁平）', async () => {
    setActivePinia(createPinia())
    useCatalogStore().catalog = loadCatalog()
    const chatCap = loadCatalog().modules.find((m) => m.id === 'chat')!.capabilities[0]
    useFavoritesStore().ids = [chatCap.id]
    const wrapper = await mountNav('/')
    const fav = wrapper.find('.nav__fav')
    expect(fav.exists()).toBe(true)
    expect(fav.text()).toContain('收藏')
    expect(fav.text()).toContain(chatCap.title)
  })
})

describe('SideNav · 搜索', () => {
  it('无匹配查询渲染零结果空态 + 清除入口', async () => {
    const wrapper = await mountNav('/')
    useUiStore().filter = 'zzz-绝不匹配-xyz'
    await flushPromises()
    expect(wrapper.text()).toContain('无匹配能力')
    // 清除按钮复位 filter
    await wrapper.find('.empty__clear').trigger('click')
    await flushPromises()
    expect(useUiStore().filter).toBe('')
    expect(wrapper.text()).not.toContain('无匹配能力')
  })

  it('命中查询时相关分组保留、无关模块隐去', async () => {
    const wrapper = await mountNav('/')
    useUiStore().filter = '知识检索'
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('知识库')
    expect(text).not.toContain('无匹配能力')
  })
})

describe('SideNav · 分组折叠持久化', () => {
  it('点击组头折叠并写入 showcase.navGroups', async () => {
    const wrapper = await mountNav('/')
    const head = wrapper.find('.grp__head')
    await head.trigger('click')
    const raw = localStorage.getItem('showcase.navGroups')
    expect(raw).toBeTruthy()
    const parsed = JSON.parse(raw as string)
    expect(Object.values(parsed).some((v) => v === true)).toBe(true)
  })
})

describe('SideNav · 模块行 = 双动作（展开/收起 + 跳转模块工作台）', () => {
  // 现行设计（NavModuleRow.onModuleClick）：点模块行既 toggle 能力列表，又 router.push(/m/:id)
  // 跳到模块工作台落地页。本组测试曾断言早期「只展开不跳转」行为，已按现行设计更新。
  function ragRow(wrapper: ReturnType<typeof mount>) {
    return wrapper.findAll('.mod').find((li) => li.text().includes('知识库'))!
  }
  const ragCapTitle = () => loadCatalog().modules.find((m) => m.id === 'rag')!.capabilities[0].title

  it('点击模块行：展开能力并跳转 /m/:id（模块 current，总览退出高亮，不双亮）', async () => {
    const wrapper = await mountNav('/')
    expect(wrapper.text()).not.toContain(ragCapTitle())
    await ragRow(wrapper).find('.mod__link').trigger('click')
    await flushPromises()
    // 展开
    expect(wrapper.text()).toContain(ragCapTitle())
    // 已跳转到模块工作台：该模块 current、总览不再高亮（唯一主 active 原则）
    expect(ragRow(wrapper).classes()).toContain('mod--current')
    expect(wrapper.find('.nav__overview.active').exists()).toBe(false)
  })

  it('再次点击同一模块行即收起（toggle 保留，路由停留在模块工作台）', async () => {
    const wrapper = await mountNav('/')
    // 第一次点击：展开 + 跳转
    await ragRow(wrapper).find('.mod__link').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(ragCapTitle())
    // 第二次点击：收起（导航为同路由 no-op）
    await ragRow(wrapper).find('.mod__link').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain(ragCapTitle())
    expect(ragRow(wrapper).classes()).toContain('mod--current')
  })

  it('模块行是 button（跳转经 router.push，无裸 href 供中键/新标签绕过）', async () => {
    const wrapper = await mountNav('/')
    const link = ragRow(wrapper).find('.mod__link')
    expect(link.element.tagName).toBe('BUTTON')
    expect(link.attributes('href')).toBeUndefined()
  })
})
