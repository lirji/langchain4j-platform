# TEST_PROGRESS — showcase-mm-platform-interop

## 新增测试清单（2026-07-17 落地，用户选择处置方式 A：先落测试、bug 挂账）

| 文件 | 活跃用例 | skip 挂账 | 说明 |
|---|---|---|---|
| `src/modules/multimodal/MultimodalConsoleView.interaction.test.ts` | MM-01~11、13、16、17、18（15 个执行例） | MM-12/14/15（3 个） | MM-16 为落地新增：flag-off 诚实呈现链路；MM-17/18 为 Codex 验收补强：8 项能力请求合同全覆盖 |
| `src/modules/interop/InteropEvalView.interaction.test.ts` | IE-01~13、20、21、22（含 it.each ×5，20 个执行例） | IE-14~19、23（7 个） | IE-20 锁定「清空 topK 正确省略」（推翻 ISSUE-05 空串误报）；IE-21/22 为错误路径补强 |
| `src/modules/channel/ChannelConsoleView.interaction.test.ts` | CH-01~09（含 it.each ×7，15 个执行例） | CH-10/11/12（3 个） | CH-06 验证 display-only 二次确认合同（钉钉 examples 预设） |

## 跑测记录

| 轮次 | 命令 | 结果 |
|---|---|---|
| 1 | `npx vitest run <三个新文件>` | ✅ 3 文件 46 passed / 12 skipped，一次全绿 |
| 2 | `npm run type-check` | ✅ 无错误 |
| 3 | `npx vitest run`（全量） | ✅ 63 文件 506 passed / 12 skipped，零回归 |
| 4 | Codex 验收补强后 `npx vitest run <三个新文件>` | ❌ MM-17 一处：`/voice/chat?chatId=default` ≠ `/voice/chat`（defaultValue 会预填进 query，属正确行为）→ 修正断言锁定预填 |
| 5 | `npx vitest run <multimodal>` + `npm run type-check` + 全量 | ✅ 63 文件 510 passed / 13 skipped，零回归 |

## Codex 独立验收（阶段五）结论与处置

Codex 只读审查 7 个维度，无「删弱已有测试」「vi.mock 违规」「把 bug 锁成通过断言」问题。按其意见补强：

- ✅ 采纳：MM-04/MM-05/CH-07/IE-20 补成功终态断言（响应值 + HTTP 状态/指标卡）；
- ✅ 采纳：新增 MM-17（voice.transcribe/voice.chat multipart 契约，顺带锁定 chatId defaultValue 预填）、
  MM-18（rag.image.search 成功 JSON + rag.image.ingest multipart 契约）→ 8 项多模态能力全部有真实请求合同；
- ✅ 采纳：新增 IE-21（详情空体 + 调用 500 错误重试）、IE-22（retrieval HTTP 500 + busy 复位重试）；
- ✅ 采纳：新增 IE-23 skip（ISSUE-18 指标大小写去重挂账）；IE-14/15/18、CH-12、MM-14 的 skip 扩展至与
  issue 标签对齐（调用错挂/重列残留/旧指标清理/已展示数据清除/unmount abort/banner 文案）；IE-16 注明两候选契约；
- ❌ 不采纳（说明理由）：CH-07 加 `body === undefined` 断言 —— 会把 ISSUE-01 锁成正确行为，违反本流程铁律，
  body 合同由 CH-10 挂账；afterEach 自动 unmount（enableAutoUnmount）—— 与既有显式 unmount 范式冲突会产生
  二次 unmount 告警，且每例新建 Pinia + `cleanup()` 重置 DOM 已隔离，失败路径残留风险有限，接受为已知限制；
- ⚠️ 部分采纳：SSE 负路径（HTTP 错误/error 事件/流中止）在共享层 `CapabilityRunner.interaction.test.ts` 57 项
  契约已覆盖，视图层不重复；MM-11 标题已改「默认 runner」不夸大范围。

## 发现并上报的疑似 bug（详见 03-suspected-issues.md，经 Claude 跨模型复核）

成立 17 条（高 5 / 中 8 / 低 4），全部以 `it.skip('TODO(issue-xx) ...')` 挂账：

- 【高】ISSUE-01 catalog 回调/入站缺 body 参数（后端 `@RequestBody` 必填 → 执行必 400）→ CH-10
- 【高】ISSUE-02 retrieval 示例/占位符字段与后端 `RetrievalCase(id,question,relevantDocIds)` 不符 → IE-19
- 【高】ISSUE-06/07/08 MCP 三步串联乱序覆盖/结果错挂/重列残留竞态 → IE-14
- 【高】ISSUE-11 凭证切换旧租户数据残留、晚到响应回写 → IE-18、CH-12
- 【高】ISSUE-20 rag.image.ingest 带 ingest scope 却漏标 scope-required → MM-15
- 【中】ISSUE-03/04（JSON 只 parse 不验结构）、09（旧结果与新错误并存）、10（null/204 空白）、12（无函数级防重）、14（探测器伪项目）、17（unmount 不 abort）、19 收窄（voice 三能力目录标 ready + banner 反向陈旧）→ IE-15/17、CH-11、MM-12/14
- 【低】ISSUE-05 收窄（仅 topK 越界）、15（firstArray 空数组遮蔽）、16（stale selected id）、18（指标去重大小写）

复核修正（已回写 03/TEST_PLAN §5.5）：ISSUE-19 收窄（vision/rag 默认值 2026-07-17 已翻转为开，仅 voice 默认关）；
ISSUE-05 空串误报（`buildJsonBody` 丢弃 `''`，IE-20 已锁定正确行为）；ISSUE-13 移出（app 级统一现状，非本模块缺陷）。

## 修复轮（2026-07-17 晚，用户指示「开始修复这些问题」）

**17 条挂账 bug 全部修复**，13 个 skip 用例全部启用转绿（另加 IE-24 新用例），全量 63 文件 524 passed / 0 skipped，type-check 干净。

| 修复 | 改动 |
|---|---|
| ISSUE-01 回调/入站缺 body | `capabilities.yml`：callbacks 加 `payload`(json,可选，留空发 `{}`)；inbound 加 `channel`/`eventType`/`payload`（对齐后端 `ChannelInboundEvent`）→ CH-08/CH-10 |
| ISSUE-02 retrieval 示例字段 | catalog placeholder/example/examples 及视图硬编码占位符全部改 `id/question/relevantDocIds`；cases 参数补 example 供预填 → IE-19 |
| ISSUE-19 voice 目录失实 | voice 三能力 `featureFlagDefault:false`+`state:flag-off`；banner 改为「语音默认未注册，图像侧默认已开」→ MM-14（MM-07/17 在测试内置 ready 模拟已开启部署） |
| ISSUE-20 ingest 漏标 scope | `rag.image.ingest` → `state: scope-required` → MM-15 |
| ISSUE-03/05 校验 | `runRetrieval` 结构校验（非空数组+元素为对象）+ topK 1..50 整数校验；本地校验失败清旧结果（ISSUE-09）→ IE-15 |
| ISSUE-04 arguments | `callTool` 校验必须是 plain object → IE-24 |
| ISSUE-06/07/08 MCP 竞态 | `mcpGeneration` 代号：selectTool/loadTools 递增、callTool 快照，旧完成回调失效；loadTools 重置选中/详情/调用状态 → IE-14 |
| ISSUE-10 null 成功空白 | tools/retrieval/discover 三处补「成功，但响应体为空」独立终态 → IE-24、CH-11 |
| ISSUE-11 凭证串味 | 两视图 watch `[session.apiKey, session.credentialMode]`：清页面数据 + 代号失效 + abortAll → IE-18、CH-12 |
| ISSUE-12 防重 | `loadTools/callTool/runRetrieval/discover` 函数入口 busy guard → IE-17、CH-11 |
| ISSUE-14 伪项目 | `parseTools/parseChannels` 过滤 null/无标识项，parseTools 重名去重 → IE-24、CH-11 |
| ISSUE-15 空数组遮蔽 | `firstArray` 取首个**非空**数组，全空回落首个空数组（契约定为方案 A）→ IE-16 |
| ISSUE-16 stale 选中 | multimodal 两个 watcher 规范化：选中项被移除时落回首项 → MM-12 |
| ISSUE-17 卸载不取消 | 两视图 AbortController 池 + `onScopeDispose(abortAll)`，专用请求经 `runContext(signal)` → CH-12 |
| ISSUE-18 指标去重 | `extractMetrics` 按小写规范化 key 去重 → IE-23 |

## 遗留待办

1. app 级：`humanizeError` 调用点统一补传 `credentialMode`（原 ISSUE-13，全应用一致的现状，不属本模块）。
2. 设计说明：gate 目前仍以 `state==='scope-required'` 为读取 `requiredScopes` 的前提（单一 state 维度）；
   若未来 live discovery 会把 state 升级为 ready，需把「部署可用性」与「授权需求」拆成正交维度再裁决。
3. 运行时全栈验证（真实后端跑通 voice flag-off 提示与回调 body）需 `bash deploy/start-all.sh` 重建栈后人工过一遍。
