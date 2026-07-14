# 方案 C：工作区主轨道与上下文面板

## 定位

改变 IA：桌面使用主轨道 `NavRail` + 当前上下文面板 `NavContextPanel`，移动端使用逐层钻取 drawer，不再在一棵树里常驻全部 82 项能力。

## 架构与流程

- Rail 一级项：总览、收藏、`GROUP_ORDER` 工作区、符合 `canAdmin` 的管理、底部开合。
- Context：当前工作区模块、当前模块能力；搜索时显示跨工作区扁平结果。
- route 变化反向推导 active workspace/module/capability，优先级高于本地选择。
- 点击 workspace 只改变浏览上下文，不改内容 URL；点击模块/能力才走既有路由。
- 桌面折叠保留 rail；移动端一级工作区→二级模块→能力，带返回、Esc、scrim、focus trap。

## 视觉与状态

- rail 与 context 采用不同 active 强度，保持一条 active 祖先链。
- 使用受控本地 SVG/短标签/tooltip/读屏名；不依赖 emoji。
- 拟新增 `showcase.navigation.v2`：expanded/rail、selected workspace；移动钻取只在内存。
- 旧 `navCollapsed=1` 可迁移为 rail；旧 group 折叠无法可靠映射，不迁移但保留旧 key 以支持回滚。

## 精确范围

重写 SideNav、App、AppHeader、ui、moduleGroups、CommandPalette、global shortcuts、badge/token；拟新增 navigationModel、NavRail、NavContextPanel、workspace/module/capability list、mobile drilldown 及测试。默认不改路由、catalog 契约、业务页面和后端。

## 成本、扩展性与弱点

- 估算 8–12 开发日并需要可用性原型；风险最高。
- 扩展性最好，折叠后永远有可恢复入口，能力不常驻渲染。
- workspace 点击不改 URL，可能出现左侧浏览上下文与右侧当前页面不一致；移动钻取、前进后退、权限变化和焦点状态机复杂。
- 1024px 附近双栏可能挤压内容；rail 图标语义依赖验证。

## 上线前置与停止条件

必须先完成低保真任务测试。用户不能区分“浏览上下文/当前页面”、回退不校正、移动焦点逃逸、1024px 内容不可用或原型验证未通过时不得实施首发。灰度需小流量静态包，回滚旧包；数据库/接口无回滚。
