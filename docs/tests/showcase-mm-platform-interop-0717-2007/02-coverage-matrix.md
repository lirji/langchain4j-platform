# 02 覆盖矩阵

标记：`已有` = 现有浅测试或共享层测试已锁定；`缺口` = 本次应新增；`TODO/Issue` = 当前行为疑似错误，只写 `it.skip` 期望行为，修复后启用。测试编号对应 `TEST_PLAN.md` 草案中的 `MM-*`、`IE-*`、`CH-*`。

## MultimodalConsoleView

| 方法/分支/场景 | 现有覆盖 | 状态 | 本次测试 |
|---|---|---|---|
| 模块不存在 | 无 | 缺口 | MM-10 |
| capId 存在且属于模块 | stream 深链冒烟 | 部分 | MM-08：runner 唯一、multipart-sse 提示与真实流 |
| capId 不存在/不属于模块 | 无 | 缺口 | MM-10 |
| 图像/语音能力全部存在、固定顺序 | 仅两个关键 chip | 缺口 | MM-01：8 项真实合同与顺序 |
| 某分区部分能力缺失 | 无 | 缺口 | MM-09 |
| 两分区均无能力 → EmptyState | 无 | 缺口 | MM-09 |
| 默认首项与两个分区选择互不干扰 | 默认 runner 已有 | 部分 | MM-02 |
| chip aria-selected/data-active/runner key 切换 | 仅 voice 流式切换 | 缺口 | MM-02 |
| 目录移除当前选中项 | 无 | TODO/Issue | MM-12（selected id 应规范化） |
| vision.caption.file multipart、文件预览、响应 | client/FileField 有纯共享覆盖 | 缺口 | MM-03 |
| vision.caption.json JSON body | 无视图交互覆盖 | 缺口 | MM-04 |
| chat.vision query + FormData | client 有组装覆盖 | 缺口 | MM-05 |
| rag.image.search JSON；rag.image.ingest scope hint | 无 | 缺口 | MM-06 |
| voice.transcribe/voice.chat/voice.chat.stream 合同 | 仅 stream 文案 | 缺口 | MM-01、MM-07 |
| multipart-sse FormData + Accept + token/done | client/SSE 分层测试已有 | 缺口 | MM-07 |
| required 文件缺失 → 0 fetch | CapabilityRunner 通用校验已有 | 回归缺口 | MM-03 |
| 未登录 gate → 禁用/0 fetch | gate 纯函数已有 | 视图缺口 | MM-11 |
| 后端默认关闭的 feature flag → flag-off/0 fetch/精确 flag | banner 声称锁定，但真实 catalog 标 ready/default true | TODO/Issue | MM-14 |
| rag.image.ingest feature-off 与 ingest scope 双重裁决 | 无；单一 state 无法同时表达 | TODO/Issue | MM-15 |
| HTTP 错误与切 chip 清理旧 runner 状态 | 无 | 缺口 | MM-06 |
| pending 请求时按钮 busy；切 chip 中止旧请求 | useCapabilityRun 有代号/abort | 视图缺口 | MM-13 |
| 文件 object URL 切换/卸载回收 | FileField 代码存在 | 缺口 | MM-03 |

## InteropEvalView：MCP、卡片、深链

| 方法/分支/场景 | 现有覆盖 | 状态 | 本次测试 |
|---|---|---|---|
| 模块/能力深链存在与不存在 | gate 深链冒烟 | 部分 | IE-13 |
| 无任何相关能力 → EmptyState | 无 | 缺口 | IE-13 |
| Agent Card/A2A 与 eval.* 卡片 ID、准确路由 | 只校验两个 eval ID | 缺口 | IE-12 |
| eval.gate 422 文案 + runner 显示 422 body | 只有文案 | 缺口 | IE-11 |
| `callCap` 能力缺失 | 无 | 缺口 | IE-10 |
| `callCap` 未登录 gate → 0 fetch | button 禁用未测 | 缺口 | IE-01 |
| `callCap` runContext 注入 API key、精确 URL/body | client 共享覆盖 | 视图缺口 | IE-02 |
| `callCap` HTTP/网络错误 humanize | errors 纯函数已有 | 视图缺口 | IE-05、IE-09 |
| Bearer 401/403 使用 credentialMode 文案 | 无；调用点未传 mode | TODO/Issue | IE-18 |
| parseTools：根数组 | 无 | 缺口 | IE-03 |
| parseTools：tools/items/data/results | 无 | 缺口 | IE-03 |
| parseTools：name/tool/id 与 description/summary/title | 无 | 缺口 | IE-02、IE-03 |
| parseTools：空数组 | 无 | 缺口 | IE-04 |
| parseTools：不可解析 → ResponseViewer | 无 | 缺口 | IE-04 |
| parseTools：null/undefined/无名对象/重复 name | 无 | TODO/Issue | IE-15 |
| firstArray：前键空数组、后键非空 | 无 | TODO/Issue | IE-16 |
| loadTools busy/按钮禁用/重试清错 | 无 | 缺口 | IE-05 |
| loadTools 双击与乱序结果 | 无 | TODO/Issue | IE-17 |
| loadTools 后旧 selected/detail/call 状态复位 | 无 | TODO/Issue | IE-14 |
| selectTool 自动 GET 编码路径、选中态、详情 | 无 | 缺口 | IE-02 |
| selectTool A/B 乱序 | 无 | TODO/Issue | IE-14 |
| tool 详情 error/空 body | 无 | 缺口 | IE-10 |
| callTool 精确 `{tool, arguments:Object}` body | 无 | 缺口 | IE-02 |
| callTool 合法 JSON、空白默认 `{}` | 无 | 缺口 | IE-02、IE-06 |
| callTool 非法 JSON → 错误/0 fetch | 无 | 缺口 | IE-06 |
| callTool JSON 数组/标量应拒绝 | 无 | TODO/Issue | IE-15 |
| 调用中切 tool 不得错配结果 | 无 | TODO/Issue | IE-14 |
| callTool busy/错误后重试/无 body 成功 | 无 | 缺口 | IE-06 |

## InteropEvalView：Retrieval

| 方法/分支/场景 | 现有覆盖 | 状态 | 本次测试 |
|---|---|---|---|
| retrievalGate 未登录禁用/0 fetch | 无 | 缺口 | IE-01 |
| cases 合法数组、topK/category trim 后精确 body | 无 | 缺口 | IE-07 |
| cases 语法非法 → 错误/0 fetch | 无 | 缺口 | IE-08 |
| cases 合法但非数组/元素错误形状 | 无 | TODO/Issue | IE-15 |
| catalog cases 示例与后端 record 契约 | 无 | TODO/Issue | IE-19 |
| topK/category 可选参数省略 | 无 | TODO/Issue（空 topK 当前会发空串） | IE-15 |
| topK 1..50 整数边界 | 只有 HTML min/max | TODO/Issue | IE-15 |
| extractMetrics 根层与 metrics/summary/aggregate/overall/result/scores | 无 | 缺口 | IE-07、IE-09 |
| 数值与数字字符串；非有限/非数值忽略 | 无 | 缺口 | IE-09 |
| recall/mrr/hit/precision/ndcg/f1/map 探测与格式化 | 无 | 缺口 | IE-07 |
| 重名 metric 去重/大小写变体 | 无 | 边界缺口 | IE-09；大小写语义待验证 |
| extractCaseRows 根数组与 cases/perCase/caseResults/results/details/items | 无 | 缺口 | IE-09 |
| 行中过滤 null/primitive | 无 | 缺口 | IE-09 |
| 指标+行同时存在 | 无 | 缺口 | IE-07 |
| 仅指标、仅行 | 无 | 缺口 | IE-09 |
| 两者皆无 → ResponseViewer | 无 | 缺口 | IE-09 |
| 成功 null body | 无 | TODO/Issue | IE-15 |
| HTTP 失败清 busy、显示 humanized 错误 | 无 | 缺口 | IE-09 |
| 旧成功后非法 JSON清理旧指标/表格 | 无 | TODO/Issue | IE-15 |
| 双击/乱序请求只保留最新结果 | 无 | TODO/Issue | IE-17 |
| 凭证/租户切换清空旧结果且旧响应失效 | 无 | TODO/Issue | IE-18 |

## ChannelConsoleView

| 方法/分支/场景 | 现有覆盖 | 状态 | 本次测试 |
|---|---|---|---|
| 模块/深链不存在、全部能力缺失 | 无 | 缺口 | CH-09 |
| 未登录：发现按钮禁用、提示、0 fetch | 只测默认入口 | 缺口 | CH-01 |
| discover 精确 GET、API key/runContext | 无 | 缺口 | CH-02 |
| parseChannels 根数组 | 无 | 缺口 | CH-03 |
| channels/capabilities/configured/data/items/results | 无 | 缺口 | CH-03 |
| label 优先 channel/name/id/type/provider | 无 | 缺口 | CH-02、CH-03 |
| detail type/provider/enabled/target/description | 无 | 缺口 | CH-02 |
| 空数组 → 没有已配置渠道 | 无 | 缺口 | CH-04 |
| 不可解析 → ResponseViewer | 无 | 缺口 | CH-04 |
| null/undefined/无名对象 | 无 | TODO/Issue | CH-11 |
| success null body 的 UI 终态 | 无 | TODO/Issue | CH-11 |
| HTTP/网络错误、busy finally、重试清错 | 无 | 缺口 | CH-05 |
| 双击/乱序请求只保留最新 | 无 | TODO/Issue | CH-11 |
| 凭证/租户切换清结果、旧响应失效 | 无 | TODO/Issue | CH-12 |
| 出站 display-only 默认锁定、强点 0 fetch | 文案/Badge 已有 | 缺口 | CH-06 |
| 出站确认后精确 JSON 请求 | CapabilityRunner 通用危险合同已有（旧六模块） | 本模块缺口 | CH-06 |
| 回调两个 runner 的业务 header 与凭证隔离 | 只查 catalog 有 header | 缺口 | CH-07 |
| 回调必需 payload body | 目录无 body 参数 | TODO/Issue | CH-10 |
| inbound 卡片/深链、签名 header、必需 JSON body | 仅卡片存在 | TODO/Issue | CH-08、CH-10 |
| custom runner pending/unmount abort | 通用层有 onScopeDispose | 视图缺口 | CH-05/CH-07（代表路径） |

## 跨模块安全、回归与 flaky 风险

| 横切面 | 状态 | 对应测试/写法 |
|---|---|---|
| 不绕过 executionGate | 缺口 | MM-11、IE-01、CH-01/06，均断言 0 fetch |
| API key 只在 header，不进 URL/body | 缺口 | MM-04/07、IE-02/07、CH-02/07 |
| display-only 二次确认 | 缺口 | CH-06 |
| 租户结果不跨凭证残留 | 当前可疑 | IE-18、CH-12（skip） |
| feature flag on/off | 后端代码确认多模态相关 flag 缺省关闭，但 catalog 当前 8 项为 ready/default true | ISSUE-19；MM-14（skip，修生成源后启用） |
| 定时/随机 | 不需要 fake timer | 使用 deferred 和 `flushPromises`；不断言 elapsedMs |
| 顺序依赖/Pinia/localStorage | 高风险 | 每例 `setupCatalog()`，afterEach `cleanup()` + unstub/restore |
| fetch 队列误配 | 高风险 | 优先按 URL 分派；顺序队列仅用于同 URL 明确重试 |
| Vue 异步刷新 | 高风险 | `settle()`；busy 断言先 `flushPromises/nextTick`，不写固定 sleep |
| File/object URL 泄漏 | 风险 | stub create/revoke，卸载后断言回收 |
| ThreadLocal/H2/静态 Spring 状态 | 不适用 | 本次为 jsdom 前端测试；以 Pinia/global/DOM 清理替代 |
