# 移动端适配验收走查报告（feat/frontend-mobile-adapt）

- 日期: 2026-07-18；执行方式: Playwright 1.61 Chromium，iPhone 8 仿真（375×667, touch, DPR2——
  `hover:none` / `pointer:coarse` 媒体查询真实生效，ENV 用例自检确认）
- 被测: capability-showcase-frontend dev（http://127.0.0.1:5173，经 vite 代理 → edge :18080，
  `EDGE_CASDOOR_MODE=dual` 窗口期，alice 演示卡 apikey 模式登录）
- 用例来源: `docs/plans/mobile-adapt-plan.md` §9 验收标准（范围仅此走查，非全站 QA）
- 用例设计说明: 验收清单在计划中已定稿并经用户批准，故跳过 Codex 独立设计与执行前呈报卡点

## 结果总表: **15/15 通过**

| 编号 | 用例 | 结果 | 实测 |
|---|---|---|---|
| ENV | 仿真环境 hover:none/pointer:coarse 生效 | ✅ | 两查询均 true, width=375 |
| A5 | 演示卡描述可换行不截断 | ✅ | white-space:normal, 无裁剪 |
| A1-login/overview/chat/rag/tasks/runner | 六页面无 body 横向滚动 | ✅×6 | scrollWidth ≤ clientWidth |
| LOGIN | alice 演示卡一键登录 | ✅ | 跳转总览 |
| A2-chips | 模式 chips 单行横滚 | ✅ | nowrap + overflow-x:auto |
| A2-reply | chat 真实 LLM 对话成功 | ✅ | 助手气泡有内容、流终态正常 |
| A2-actions | 消息操作无 hover 常显 | ✅ | opacity=1 |
| A4-send / A4-exec | 发送/执行按钮触控高度 | ✅ | 均实测 44px |
| A3-scroll | 能力页执行后自动滚到响应区 | ✅ | 响应区 top=0（scrollTop 444） |

截图: 本目录 `01-login.png` ~ `05-runner.png`（05 为 async.list 运行器执行后）。

## 过程发现（非缺陷）

1. `chat.sync` 深链渲染聊天台而非通用运行器（会话能力设计如此），运行器用例改用 `/m/tasks/async.list`。
2. 本机 5173 有双监听：recsys console vite 占 `[::1]:5173`（IPv6），showcase `--host` 实例占 `*:5173`——
   `localhost` 会串到 recsys，**本机访问 showcase 须用 `127.0.0.1:5173`**（手机走 LAN IP 不受影响）。

## 环境

- commit: feat/frontend-mobile-adapt @ 804ffc2（+ 未提交的 QA 产物）
- 后端: docker 全栈，edge dual 模式（走查后已恢复 enforce/only）
- LLM: 经 LiteLLM 真实调用 ×2（chat 对话）
