# 03 交互逻辑疑似问题

> **状态（2026-07-17 晚）：17 条成立项已全部修复**，对应 skip 用例全部启用转绿。
> 修复明细见 `TEST_PROGRESS.md` 修复轮表格。本文保留原始分析记录。

以下均基于仓库实际 SFC、共享基建、`public/catalog.json`，并在需要时交叉核对后端 controller/record。它们不作为“当前正确行为”断言；测试草案用 `it.skip('TODO(issue-xx)...')` 描述修复后的期望。

## ISSUE-01（高）：Channel 回调与入站目录缺少后端必需 JSON body

- 预期行为：两个回调 runner 可填写任意 payload 并发送业务 headers；入站 runner 可填写 `ChannelInboundEvent` body 和可选签名。
- 现状：`public/catalog.json` 的 `channel.callbacks.async-task`/`workflow` 只有 `in:header` 参数，`channel.inbound` 也只有 header。`assembleRequest()` 只有存在 `in:body` 参数时才发送 JSON body。后端 `ChannelController` 三个端点分别要求 `@RequestBody Map<String,Object>` 或 `@RequestBody ChannelInboundEvent`。
- 复现：深链任一能力，直接执行。实际请求 `body === undefined` 且没有 `Content-Type`；后端通常返回 400。入站页面也没有 channel/eventType 输入。
- 建议处置：修正 catalog 生成源，为回调增加 payload(json/object) body，为入站加入真实 record 字段；随后启用 CH-10。不要在测试里接受“无 body”为正确。

## ISSUE-02（高）：检索评测目录示例字段与后端 `RetrievalCase` 不一致

- 预期行为：目录示例可直接执行并向后端发送 `id/question/relevantDocIds`。
- 现状：catalog placeholder/examples 使用 `query/expectedDocIds`；后端 record 是 `RetrievalCase(String id, String question, List<String> relevantDocIds)`，`RetrievalEvaluator` 调 `c.question()` 和 `c.relevantDocIds()`。
- 复现：复制默认示例 `[{'query':'退款政策','expectedDocIds':[]}]`（JSON 使用双引号）运行 `/eval/retrieval`；请求虽然是 JSON 数组，但字段不能绑定到后端所需 record 字段，可能产生空 query、空标注或运行时失败。
- 建议处置：以 protocol record 为单一契约修正生成源和文档；加 catalog 合同测试并启用 IE-19。

## ISSUE-03（高）：`runRetrieval()` 只验证可解析，不验证 cases 是数组及元素结构

- 预期行为：`cases` 必须是非空 JSON 数组，元素至少符合后端 `RetrievalCase` 结构；错误在前端阻断且 0 fetch。
- 现状：只执行 `JSON.parse()`，`{}`、`null`、`1`、`"x"` 都可通过并发送。
- 复现：输入 `{}`，点击“运行检索评测”；fetch body 为 `{"cases":{...},"topK":5}`，与后端 `List<RetrievalCase>` 不兼容。
- 建议处置：校验 `Array.isArray`、非空及关键字段类型；错误文案不要只说“不是合法 JSON 数组”却仅做语法校验。启用 IE-15 对应 TODO。

## ISSUE-04（中高）：`callTool()` 接受 JSON 数组/标量作为 arguments

- 预期行为：UI 标注“JSON 对象”，后端 `McpToolCallRequest.arguments` 是 `Map<String,Object>`，数组/标量应在前端阻断。
- 现状：只用 `JSON.parse()` 检查语法。
- 复现：选择 `platform.ping`，arguments 输入 `[]`、`null` 或 `1` 后调用；会产生非对象的 `arguments`。
- 建议处置：解析一次并验证非 null plain object；发送时仍由 `runCapability` 按目录组装。启用 IE-15。

## ISSUE-05（低，Claude 复核后降级）：Retrieval topK 越界值未校验（空值部分为误报）

- 【Claude 跨模型复核修正 2026-07-17】原报告称清空输入会发送 `topK:""` —— 不成立：`values.topK=''` 会被
  `client.buildJsonBody()` 的 `v === '' → continue` 丢弃，线上请求体正确省略 topK。此部分可写成**通过态断言**。
- 仍成立的残留缺口：HTML `min/max` 不阻止 0、51、1.5 之类越界/非整数值进入请求体（无 JS 层校验）。
- 复现：`setValue('0')`/`setValue('51')`/`setValue('1.5')`，点击运行，检查 fetch body 含越界 topK。
- 建议处置：在 `runRetrieval()` 做有限整数与 1..50 范围校验。对应 IE-15（仅保留越界部分）。

## ISSUE-06（高）：工具详情选择存在乱序覆盖

- 预期行为：快速选择 A 后选择 B，无论响应顺序如何，界面最终只显示 B 的详情，busy 直到 B 结束。
- 现状：`selectTool()` 没有 generation/AbortController；A 晚到可覆盖 B，任一先完成都会把共享 `detailBusy=false`。
- 复现：A 请求 pending；点击 B；先 resolve B 为 `NEW-B`，再 resolve A 为 `STALE-A`。选中仍为 B，但详情变成 A。
- 建议处置：递增 request id 或 abort 前请求，并只让最新请求落状态。启用 IE-14。

## ISSUE-07（高）：调用期间切换工具会把旧结果挂在新工具名下

- 预期行为：调用 A 时锁定选择或把结果与调用快照关联；切到 B 后不得把 A 结果显示成 B 的结果。
- 现状：`callTool()` 捕获局部 `tool=A`，但 UI 的标题目标来自响应式 `selectedTool`；工具按钮在 `callBusy` 时仍可点击。
- 复现：调用 A pending → 点 B → B 详情完成 → A 调用返回。页面显示 target B，但结果属于 A。
- 建议处置：调用态禁选或保存 `{tool,result}` 并校验 generation；切 tool 清理 call 状态。启用 IE-14。

## ISSUE-08（中高）：重新列工具不复位 selected/detail/call 状态

- 预期行为：重新加载后，若旧工具不存在，应取消选择并清理详情、arguments 错误和调用结果。
- 现状：`loadTools()` 只清 tools/raw/error；`selectedTool`、detail 与 call 状态保留。
- 复现：首轮选 A 并调用成功；第二轮返回仅 B。列表无 A，右侧仍展示 A 且可再次调用。
- 建议处置：加载开始或提交新列表时原子校正选择与派生状态。启用 IE-14。

## ISSUE-09（中）：旧 Retrieval 成功结果会与新的本地校验错误并存

- 预期行为：新一轮提交即使在本地失败，也应明确清理/标旧上轮指标，避免把旧结果误认为本轮结果。
- 现状：cases JSON parse 失败分支只写 `retrievalError`，不清 `retrievalRaw/retrievalRan`，旧 StatCard/ResultTable 继续显示。
- 复现：先成功返回 avgRecall=1，再把 cases 改 `{bad` 并点击；警报出现但 avgRecall=1 仍在。
- 建议处置：提交入口先清理本轮相关输出，或显式标注结果对应的输入快照。启用 IE-15。

## ISSUE-10（中）：成功但 null/204 响应落入空白终态

- 预期行为：工具列表、发现、检索都应显示“成功但无响应体/无数据”的明确空态。
- 现状：`data ?? null` 后 fallback 条件要求 raw 非 null；同时 loaded/discovered/ran 已为 true，初始 EmptyState 也不再显示，区域空白。MCP call 反而已有“调用成功，无响应体”。
- 复现：让 `/interop/mcp/tools`、`/eval/retrieval` 或 `/channel/capabilities` 返回 204/null。
- 建议处置：为成功无 body 建独立终态；不要把 null 同“尚未请求”混用。启用 IE-15、CH-11。

## ISSUE-11（高，租户安全）：凭证切换不清页面数据，pending 旧租户响应可回写

- 预期行为：API key/Bearer 身份变化时清掉 tools/detail/call/retrieval/channels；旧请求被 abort 或 generation 拒绝，绝不向新会话展示旧租户数据。
- 现状：两个页面状态不 watch 会话身份，专用 fetch 也无 abort/generation。`session.setApiKey()` 后旧数据仍显示；旧请求晚到仍写 refs。
- 复现：tenant-A key 发起 discover/retrieval pending → `session.setApiKey('tenant-B-key')` → A 响应返回含 `tenant-A-secret`，新会话页面显示该值。
- 建议处置：监听稳定 credential identity/version，清状态并使请求代号失效；API key 本身不可写日志/DOM。启用 IE-18、CH-12。

## ISSUE-12（中高）：专用动作缺少函数级 busy 防重与最新请求守卫

- 预期行为：双击/同 tick 触发最多一次 fetch；若允许刷新并发，则只接受最新请求。
- 现状：按钮虽绑定 busy disabled，但 `loadTools()`、`discover()`、`runRetrieval()` 函数入口不检查 busy；同一渲染 tick 的重复事件可在 DOM disabled 更新前进入。也没有 generation。
- 复现：不等待 nextTick 连续 `trigger('click')` 两次，或直接从暴露 VM/事件触发；观察两次 fetch，旧响应可能覆盖新响应。
- 建议处置：入口第一行 `if (busy) return`，并配合 request generation/abort。启用 IE-17、CH-11。

## ISSUE-13（观察项，Claude 复核后移出本模块清单）：Bearer 模式错误文案可能退化为 API Key 视角

- 【Claude 跨模型复核修正 2026-07-17】grep 全仓：**没有任何调用点**给 `humanizeError` 传 `credentialMode`
  （含 chat/rag/tasks 等已有模块与 useCapabilityRun）。这是全应用统一现状，`humanizeError` 会按构建期
  `AUTH_MODE` 回落出合理文案，并非本次三个视图特有缺陷。移出本次 bug 清单，保留为 app 级增强待办。

- 预期行为：Casdoor Bearer 的 401/403 使用登录/角色文案。
- 现状：`callCap()` 与 `discover()` 调用 `humanizeError(e, cap)`，没有传 `session.credentialMode`；`humanizeError` 的精确分支依赖 options。默认 Vitest 是 apikey，因此普通测试不会暴露。
- 复现：OIDC 构建下登录后让接口返回 401/403；检查专用页面文案。
- 建议处置：传 `{ credentialMode: session.credentialMode }`；以独立 OIDC 配置测试验证。由于项目禁止模块 mock，本蓝图只保留待验证 TODO，不强行在默认 apikey suite 伪造。对应 IE-18/CH-12。

## ISSUE-14（中）：探测器会把畸形项变成可交互的伪项目

- 预期行为：null/undefined/空对象应过滤、回退原始响应或明确标为不可选；工具 key/name 应唯一稳定。
- 现状：`parseTools([null,{},{}])` 得到 `"null"` 和多个 `"(工具)"`，后者产生重复 Vue key 且可发详情/调用；`parseChannels([null,{}])` 得到 `"null"`/`"(渠道)"`。
- 复现：返回上述数组并点击伪工具。
- 建议处置：过滤无有效 identifier 的项；重复 name 做去重或使用稳定服务端 id。启用 IE-15、CH-11。

## ISSUE-15（低中，待产品确认）：多键 envelope 中前置空数组会遮蔽后置有效数组

- 预期行为：若响应同时有 `tools:[]` 与 `results:[...]`，应明确优先级；更稳妥是选择第一个非空数组，或将冲突视为不可解析。
- 现状：`firstArray()` 遇到第一个数组立即返回，即使为空。
- 复现：`{tools:[], results:[{name:'real'}]}` 或 `{channels:[], data:['webhook']}`。
- 建议处置：与 API 契约确认 envelope 优先级后再定断言；当前只列 TODO，不武断锁定。IE-16。

## ISSUE-16（低中）：Multimodal 目录变化后 selected id 未规范化

- 预期行为：当前能力被 live catalog 移除时，selected id 更新为新首项；旧能力后来重现不应自动夺回选择。
- 现状：computed 会视觉回退首项，但 watcher 仅在 id 为空时初始化，ref 仍保存已移除 id。
- 复现：选择 `chat.vision` → 从 catalog 移除 → UI 回退；再把旧能力加回，选择无用户操作地跳回 `chat.vision`。
- 建议处置：watch caps 时验证当前 id 是否仍存在，否则写入首项/空值。启用 MM-12。

## ISSUE-17（中）：专用请求在组件卸载时不取消

- 预期行为：离开页面/深链后自定义 MCP、retrieval、discover 请求应 abort，且不得晚到写状态。
- 现状：它们调用 `session.runContext()` 时没有 signal，也没有 `onScopeDispose`；通用 `CapabilityRunner/useCapabilityRun` 有取消，但专用逻辑没有。
- 复现：发起 pending 请求后 `wrapper.unmount()`；fetch signal 为空或未 aborted，随后仍执行状态赋值。
- 建议处置：每类动作持有 AbortController，unmount 时 abort，并结合 generation。与 ISSUE-11/12 一起处理。

## ISSUE-18（低中）：Retrieval 指标去重大小写敏感

- 预期行为：`Recall` 与 `recall` 若语义相同，只展示一个确定来源；根层/嵌套层优先级应明确。
- 现状：`seen` 直接用原 key，正则不区分大小写，因此两张卡都可出现。
- 复现：响应 `{Recall:0.8, metrics:{recall:0.9}}`。
- 建议处置：按规范化 key 去重，或在契约层明确允许大小写不同指标；当前标记待验证，不写强通过断言。

## ISSUE-19（中，Claude 复核后收窄）：voice 三能力目录标 ready 与后端默认关冲突；banner 文案反向陈旧

- 【Claude 跨模型复核修正 2026-07-17】原报告称八项多模态能力后端均默认关 —— **大半不成立**：2026-07-17
  各服务 yml 默认值已翻转，`VISION_ENABLED:true`、`CONVERSATION_VISION_ENABLED:true`、`RAG_MULTIMODAL_ENABLED:true`
  均默认**开**（yml 里“默认关”注释是陈旧注释，值为准）。vision/chat.vision/rag.image.* 五项目录标 ready 是对的。
- 仍成立的部分：`voice-service` 是 `VOICE_ENABLED:false` 默认**关**，但目录把 `voice.transcribe`/`voice.chat`/
  `voice.chat.stream` 标 `state=ready`、`featureFlagDefault=true` —— 默认部署下执行会打到未注册端点（404）。
- 另一处反向陈旧：`MultimodalConsoleView` 顶部 banner 说“多数能力默认未注册” —— 现在多数默认已开，文案误导。
- 建议处置：capabilities.yml 把 voice 三项改 `featureFlagDefault=false`、`state=flag-off`；更新 banner 文案。
  MM-14 相应收窄为仅断言 voice 三项 fail-closed + 其余五项 ready。

## ISSUE-20（高，权限）：`rag.image.ingest` 的 flag-off 与 scope-required 无法由单一 state 同时表达

- 预期行为：默认 feature-off 时先禁止；开启后，Bearer 缺 `ingest` scope 仍应被前置 gate 禁止，API key 模式至少显示 scope hint。
- 现状：目录包含 `requiredScopes:['ingest']`，却是 `state:'ready'`；`executionGate()` 只在 `state==='scope-required'` 时读取 `requiredScopes`。即便把静态 state 修为 flag-off，live 启用后的 state 若变 ready 仍会绕过 scope 预判。
- 复现：用缺 ingest scope 的 Bearer 会话聚焦/选择 `rag.image.ingest`；若 capability state 为 ready，gate 返回 allowed。
- 建议处置：将“部署可用性”和“授权需求”拆成正交维度；gate 对非 flag-off capability 只要 `requiredScopes.length>0` 就执行凭证模式裁决，不依赖 `state`。启用 MM-15；不要把当前放行写成正确断言。
