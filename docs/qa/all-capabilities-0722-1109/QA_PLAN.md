# 全能力体检 QA 计划

时间：2026-07-22 11:09（Asia/Taipei）

目标环境：本机 Docker Compose 测试栈，唯一业务入口 `http://localhost:18080`，前端 `http://localhost:8093`

状态：**用户已确认 A（完整安全模式）；执行完成，结果见同目录 `QA_REPORT.md`**

## 1. 目标与完成标准

对当前工作区所代表的平台做一次全能力完整性检查，回答四个问题：

1. 能力是否真实存在，路由、鉴权、请求契约与文档/前端目录是否一致。
2. 正向路径能否完成；关闭、缺凭据、下游故障时是否能明确降级而非 500 或伪成功。
3. alice/acme、bob/globex、analyst-a/tenantA 之间是否严格租户隔离，scope/RBAC 是否正确。
4. 自动化测试、部署配置、运行态和能力展示前端是否形成可重复回归闭环。

完成标准：

- 9 个前端业务域、82 项目录能力全部至少有一条检查记录。
- 目录之外的平台能力（认证/RBAC、Casdoor、网关、安全、配置、事件、持久化、可观测）均覆盖。
- 每条记录给出 PASS / FAIL / BLOCKED / SKIPPED、请求证据、关键响应与缺陷等级。
- 所有测试数据带统一前缀并清理；不清空既有数据，不执行未获明确授权的危险动作。
- 输出 `QA_REPORT.md`、缺陷清单和按优先级排序的完善建议。

## 2. 现场基线与静态清点

| 项目 | 现场事实 |
|---|---|
| Maven 聚合模块 | 22（7 个共享平台库、config-server、14 个运行时服务） |
| 后端 HTTP 映射 | 125 个 controller method 映射 |
| 前端能力目录 | 9 个域、82 项能力；source/dist/runtime 三份 catalog SHA-256 一致 |
| 目录状态 | ready 65、ready-degraded 1、scope-required 10、flag-off 4、display-only 2 |
| 风险标记 | safe 60、caution 20、destructive 2 |
| 自动化资产 | 后端 203 个 `*Test.java`；前端 66 个 test/spec 文件；7 个 smoke 脚本 |
| 运行栈 | 25 个 Compose 容器均 running；主要基础设施与 14 个服务探活可达 |
| 当前认证 | Casdoor enabled + only；legacy API key/会话 Bearer 在网关正向用例中不可用 |
| 当前源码状态 | 工作区有未提交修改；并非所有运行镜像都已按当前源码重建 |

### 已确认的前置风险（不是最终全量测试结论）

| 等级 | 风险 | 证据与影响 |
|---|---|---|
| P1 | Voice 部署状态三方不一致 | Compose `VOICE_ENABLED=true`；运行 voice 服务 UP；静态/运行 catalog 的 3 项 voice 仍为 `flag-off`，前端 gate 直接禁执行。 |
| P1 | Voice 已启用但实际 provider 凭据为空 | `VOICE_BASE_URL=https://api.openai.com/v1` 且容器 `VOICE_API_KEY=UNSET`；ASR/TTS 真实调用预计 401。 |
| 已排除 | Vision readiness 契约不一致 | 初查误用了宿主机 `:8090`；Compose 实际映射为 `:18090 -> 8090`，正确地址的 `/actuator/health` 与 `/actuator/health/readiness` 均为 `200 UP`。 |
| P2 | 运行镜像与工作区存在版本漂移风险 | 当前有 conversation、frontend、compose、docs 等未提交改动；黑盒结果必须绑定明确构建版本。 |
| P2 | 能力目录不是完整的平台能力目录 | catalog 只覆盖业务试用能力；auth/RBAC、order、网关、安全/配置/事件等需独立审计，不能用 82 项代表整个项目。 |

## 3. 执行档位（审批项）

### A. 完整安全模式（推荐）

- 以当前工作区重建受影响镜像，运行 Maven/前端全量自动化、构建和静态一致性检查。
- 通过 Casdoor 登录，以 `qa-all-0722-1109-*` 隔离数据执行 82 项能力的安全正向/反向用例。
- LLM 每个能力族最多做一条真实调用，其余用确定性路径和结构断言，限制费用与波动。
- 允许创建并清理测试文档、任务、临时 RBAC 用户/角色和退款测试实例。
- 不真实发送外部渠道消息，不执行全量 purge，不补填或使用新的云端 Voice 凭据；这些项验证锁定/降级/配置合同。

### B. 全量真实集成模式

- 包含 A，并实际调用所有已配置 LLM/视觉/语音/MCP/渠道/事件依赖。
- 需要补齐 Voice、外部渠道/MCP 等凭据与目标；会产生费用和外部副作用。
- `workflow.data.purge` 等 destructive 能力仍需逐项二次确认，不因选择 B 自动放行。

### C. 只读审计模式

- 仅跑自动化测试、构建、静态合同、健康检查、GET/发现类与明确无副作用接口。
- 不写数据库、不发 LLM 请求、不创建任务/工作流/文档。
- 能证明代码与基础设施基线，但不能宣称业务闭环全部可用。

## 4. 150 个检查点分布

| 能力域 | 目录能力数 | 附加检查 | 合计 | 重点 |
|---|---:|---:|---:|---|
| 平台/网关/安全/配置/事件 | 0 | 12 | 12 | 路由、401/403、JWT、限流、Config、Kafka/Noop、追踪 |
| Auth / RBAC / Casdoor | 0 | 10 | 10 | OIDC、刷新轮换、scope 展开、ETag、最后管理员护栏 |
| Conversation / Order | 10 | 4 | 14 | 同步/SSE、RAG、缓存、记忆、护栏、意图订单、租户隔离 |
| Knowledge / RAG | 11 | 5 | 16 | 文档生命周期、四路混排、GraphRAG、共享库、授权/隔离 |
| Agent | 18 | 5 | 23 | ReAct、DAG、反思、投票、链式、业务动作、异步镜像 |
| Async Task | 8 | 3 | 11 | 状态机、lease、SSE 续订、webhook outbox、租户隔离 |
| Analytics / Order | 3 | 4 | 7 | schema allowlist、NL2SQL 只读、订单 SQL 租户隔离 |
| Workflow | 7 | 4 | 11 | 启动、认领、驳回/通过、并发、回调、数据边界 |
| Multimodal | 8 | 4 | 12 | Vision、图像 RAG、Voice 开关/凭据/流式协议 |
| Interop / Eval | 12 | 4 | 16 | MCP、A2A、push、retrieval、dual-run、gate |
| Channel | 5 | 3 | 8 | 发现、入站/回调、签名、出站锁定 |
| 前端/UI | 0 | 10 | 10 | catalog/live merge、OIDC、gate、SSE、移动端、无敏感泄漏 |
| **总计** | **82** | **68** | **150** | 每项都有结果与证据 |

## 5. 82 项目录能力逐项覆盖表

下表每个 ID 都作为独立检查点记录；同组可复用准备和清理步骤，但不能只抽样其中一项。

| 域 | 能力 ID | 主要断言 |
|---|---|---|
| Chat | `chat.sync`, `chat.stream`, `chat.extract`, `chat.auto`, `chat.cascade` | 200/SSE 终态、结构化输出、路由类别、级联字段、tenant/user/chatId |
| Chat | `chat.mcp`, `chat.memory`, `memory.profile.get`, `memory.profile.clear`, `chat.cache.clear` | flag-off 诚实提示、画像隔离与删除、缓存按租户/问题失效 |
| RAG | `rag.query`, `rag.upload.file`, `rag.upload.file.shared`, `rag.upload.json`, `rag.upload.json.shared` | 混排结果、scope、私有/共享分区、上传合同、可检索性 |
| RAG | `rag.documents.list`, `rag.documents.get`, `rag.documents.delete`, `rag.obsidian.import` | 生命周期、分页、编码、删除后不可见、导入边界 |
| RAG | `rag.graph.query`, `rag.graph.entities` | GraphRAG 结果/实体、关闭或下游失败时降级 |
| Agent | `agent.run`, `agent.run.async`, `agent.tasks.list`, `agent.tasks.get`, `agent.tasks.stream`, `agent.tasks.cancel` | ReAct 步骤、任务状态、SSE、取消幂等与归属 |
| Agent | `agent.dag.run`, `agent.dag.plan-run`, `agent.dag.run.async`, `agent.dag.plan-run.async` | DAG 依赖、自动规划、并行/失败传播、异步闭环 |
| Agent | `agent.chain`, `agent.vote`, `agent.reflexive`, `agent.reflexive.stream` | 顺序编排、票数边界、反思轮次、SSE stage/done |
| Agent | `agent.analyst.run`, `agent.analyst.run.async`, `agent.process.run`, `agent.process.run.async` | analytics/workflow/order 动作、同步/异步一致性 |
| Async | `async.create`, `async.list`, `async.get`, `async.status.update`, `async.lease`, `async.cancel`, `async.stream`, `async.deadletter` | 状态机、worker lease、断点续订、终态/死信、租户隔离 |
| Analytics | `analytics.schema.tables`, `analytics.schema.describe`, `analytics.sql` | allowlist、表名校验、只读 SQL、行集结构与拒绝写 SQL |
| Workflow | `workflow.refund.start`, `workflow.tasks.list`, `workflow.tasks.claim`, `workflow.tasks.unclaim` | 发起与工单抽取、scope、assignee/并发规则 |
| Workflow | `workflow.tasks.complete`, `workflow.instances.get`, `workflow.data.purge` | 通过/驳回、实例轨迹、purge 默认锁定且无确认时 0 请求 |
| Multimodal | `vision.caption.json`, `vision.caption.file`, `chat.vision`, `rag.image.ingest`, `rag.image.search` | JSON/multipart、模型结果、scope、图像向量检索 |
| Multimodal | `voice.transcribe`, `voice.chat`, `voice.chat.stream` | 目录/运行态一致性、ASR→chat→TTS、multipart-SSE、缺凭据错误 |
| Interop | `interop.agent-card`, `interop.a2a.agent-card`, `interop.mcp.tools`, `interop.mcp.tool`, `interop.mcp.call`, `interop.a2a.call` | 发现合同、工具详情/调用、JSON-RPC、trace/tenant 传播 |
| Eval | `eval.capabilities`, `eval.retrieval`, `eval.run`, `eval.suite.run`, `eval.dual-run`, `eval.gate` | 指标、case schema、candidate/oracle、门禁阈值与 422 |
| Channel | `channel.capabilities`, `channel.messages.send`, `channel.callbacks.async-task`, `channel.callbacks.workflow`, `channel.inbound` | 发现、危险出站锁定、回调 body/header、入站签名/幂等 |

## 6. 平台附加检查（68 项）

### 6.1 网关、安全与租户（12）

1. Actuator/open-path 不带凭据可访问；业务路径无 token 为 401。
2. Casdoor `only` 下 legacy API key 和 legacy session Bearer 均拒绝。
3. 有效 Casdoor token 换发内部 JWT，tenant/user/scopes/dept 正确。
4. audience 家族和 `(owner,aud)` 不匹配时 fail-closed。
5. 下游拒绝伪造/缺失的内部 JWT，外部无法直接冒用 `X-Internal-Token`。
6. chat/agent/ingest/eval/stream 分类限流返回 429 且不串租户。
7. CORS 对允许 origin 放行、非 allowlist origin 拒绝。
8. traceparent / correlation id 跨 edge→service→下游传播。
9. HS256 当前路径与 RS256 配置合同均有自动化覆盖。
10. 网关 14 类路由前缀逐一映射到正确服务，无路径遮蔽。
11. Config Server 可用与不可用降级合同一致，敏感配置不出现在公开端点。
12. EventBus Noop/Kafka 两档、生产幂等与消费去重回归通过。

### 6.2 Auth / RBAC / Casdoor（10）

1. alice/bob/analyst-a 登录身份、租户和 scope 正确。
2. 刷新 cookie 轮换，旧 refresh token 重放失败。
3. logout 幂等并撤销刷新会话；`/auth/me` 需认证。
4. 注册默认关返回预期 403，public-config 不泄露敏感项。
5. alice 可读管理面，bob/analyst-a 无 `role-admin` 为 403。
6. 临时用户/角色 CRUD 使用 ETag/If-Match：428、412、409 分支准确。
7. 角色继承与 directScopes 展开符合 allowlist。
8. 最后一个启用 role-admin 不可移除/禁用。
9. 降权、禁用、删号会撤销刷新会话。
10. OIDC 登录、回调、静默续期、登出和非法 tenant/audience 均 fail-closed。

### 6.3 业务域附加边界（36）

- Conversation/Order 4：注入拦截、PII 脱敏、订单号缺失/不存在/下游故障、acme/globex 同号隔离。
- RAG 5：同名覆盖、跨租户不可见、公共库 scope、embedding/ES/Graph 单路失败降级、authz disabled/shadow/enforce 配置合同。
- Agent 5：最大步数、工具 allowlist、禁用 code/browser/MCP、动作权限、异步 authoritative/mirror 组合。
- Async 3：非法状态迁移、lease owner 冲突、SSE `Last-Event-ID` 恢复。
- Analytics/Order 4：未知表、注入/DDL 拒绝、结果上限、订单 owner/tenant SQL 双条件。
- Workflow 4：重复 claim、非 assignee 完成、非法终态、回调失败不吞流程状态。
- Multimodal 4：超大/错误 MIME、视觉模型下游失败、voice 缺 key、流式中止。
- Interop/Eval 4：MCP 不可达、A2A 错误码、评测空/畸形 case、gate 阈值边界。
- Channel 3：签名错误、重复事件幂等、outbound 关闭时无外发。

### 6.4 前端/UI（10）

1. 82 项 catalog 与生成源、dist、运行制品一致。
2. live discovery 失败不阻断 catalog；成功只标记已有能力，不臆造表单。
3. flag-off/scope-required/display-only 三类 gate 均 0 绕过。
4. API key/Bearer 只进 header，不进入 URL/body/history/curl/DOM。
5. OIDC only/dual/apikey 三模式的路由守卫与登录回跳正确。
6. 凭证/租户切换清理旧结果并中止晚到请求。
7. SSE 跨 chunk、error、done、abort、2000 条上限与断点续订正确。
8. 文件 multipart 不手设 Content-Type，object URL 释放。
9. 桌面/移动端核心工作台、菜单、键盘与可访问状态可用。
10. 运行态开关与 catalog 状态一致，重点验证 Voice 当前漂移。

## 7. 通用执行模板与预期

每条 API 用例均按以下模板留证：

1. 记录构建 commit、工作区 diff 摘要、镜像 ID、环境开关（密钥仅记 SET/UNSET）。
2. 使用 Casdoor 登录获得 alice/acme 身份；涉及权限/隔离时追加 bob/globex 和 analyst-a/tenantA。
3. 发送最小合法请求，记录 method/path、状态码、trace id、脱敏响应和耗时。
4. 校验业务字段、tenant/user、状态机/SSE 终态及下游副作用。
5. 对关键能力追加无凭据、缺 scope、跨租户、非法输入或 feature-off 分支。
6. 删除本用例产生的测试数据，确认清理后不可见；记录未能清理的对象。

统一期望：

- 无凭据 401，缺 scope 403，参数/状态错误使用稳定 4xx，不返回堆栈。
- feature-off 返回明确 404/禁用提示/可解释降级，具体以端点合同为准，不得 500。
- 所有读写均以内部 JWT 的 tenant 为准，不信任 body/query 中伪造 tenantId。
- LLM 文案不断言逐字一致，只断言结构、事实来源、路由和安全边界。
- SSE 必须有明确 done/error/blocked 终态，取消后不再写 UI/任务状态。

## 8. 测试数据与清理

- 统一前缀：`qa-all-0722-1109-`。
- RAG：创建私有/共享文本及小图，结束逐文档删除并查询确认。
- Auth：创建临时 role/user，按 ETag 删除；绝不修改 alice 的唯一管理员保障。
- Async/Agent：创建短任务，等待终态或取消，删除可删除项；记录 JDBC/outbox 残留。
- Workflow：使用独立 chatId/orderId，完成后只按该 chatId 清理；不调用无过滤的广域清理。
- 缓存/画像：只操作 alice 的专用 QA chatId，并在结束时清掉对应画像/缓存。
- 不执行数据库 DROP/TRUNCATE/全表 DELETE，不重置 volume，不覆盖用户现有数据。

## 9. 预计命令与工具

- 后端：`mvn test`、`mvn -DskipTests package`，失败后按模块定向复跑。
- 前端：`npm test`、`npm run type-check`、`npm run build`。
- 运行态：Docker Compose、curl/jq、现有 `deploy/smoke-*.sh`；只向 localhost/test 环境发请求。
- UI：浏览器自动化检查登录、目录、工作台和移动端；不保存账号密码或 token 到报告。
- 证据：请求/响应脱敏后写入 QA 报告；日志仅截取与失败 trace id 相关片段。

## 10. 停止条件

- 发现可能影响既有数据的清理范围不明确。
- 需要新增云端密钥、真实外部收件人、渠道 webhook 或收费配额。
- Casdoor/LLM/数据库等依赖持续不可达，且本地替代会改变测试目标。
- 运行镜像版本与当前工作区无法对齐，继续会造成结论失真。

以上情况只暂停受影响用例，其余安全用例继续执行并在报告标为 BLOCKED。
