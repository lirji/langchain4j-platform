# 全能力体检 QA 报告

> 本文保留 11:09–11:53 的原始缺陷基线。对应修复、回归与运行态复验见
> [FIX_REPORT.md](FIX_REPORT.md)；不要把下文 65/15/2 误读为修复后的结果。

执行时间：2026-07-22 11:09–11:53（Asia/Taipei）

执行档位：A（完整安全模式）

代码基线：`main@7dda7f7` + 当前未提交工作区

入口：edge `http://localhost:18080`，前端 `http://localhost:8093`

## 1. 最终结论

**不建议把当前整栈标记为“全部能力完善/可发布”。**

原始问题已经解决并通过真实链路复验：alice/acme 调用 `/chat/auto` 查询“退款订单 204”会路由到
`TOOL -> order_query`，返回订单 204“已退款”；beta 租户查询同号订单为 404，租户隔离正确。

全项目 82 项目录能力的结果为：

| PASS | FAIL | BLOCKED | 合计 |
|---:|---:|---:|---:|
| 65 | 15 | 2 | 82 |

阻止“全能力完善”结论的主要问题集中在部署集成：Workflow 容器内地址错误、Eval 与 Casdoor-only
鉴权不兼容、原生多模态 embedding 开关与依赖不匹配、Voice 开关/目录/凭据三方不一致、下游端口可绕过
网关匿名调用，以及 Obsidian 文档删除后残留 GraphRAG 关系。

## 2. 构建与自动化基线

| 检查 | 结果 | 证据摘要 |
|---|---|---|
| Maven 全量测试 | PASS | 209 个 Surefire suite XML；1043 tests，0 failures，0 errors，5 skipped |
| Maven 打包 | PASS | `mvn -DskipTests package` reactor success |
| 前端测试 | PASS | 66 files / 552 tests 全通过 |
| 前端类型检查 | PASS | `npm run type-check` |
| 前端生产构建 | PASS | `npm run build`；仅有 OIDC 模块静态/动态双导入的分包警告 |
| Compose 运行态 | PASS | 25/25 容器 running |
| 服务探活 | PASS | edge/config 与 13 个业务服务 health/readiness 均 `200 UP`；vision 正确宿主端口为 18090 |
| Catalog | PASS（已重同步） | 9 域/82 项；public、dist、runtime 最终 SHA-256 均为 `b0aed4a5…cc2ef5` |

5 条跳过测试均有明确外部条件：3 条 Knowledge Authz 集成测试因 auth-platform-server `:8200`
未启动跳过；2 条真实 Casdoor JWKS 集成测试因 Maven 进程未注入 client 凭据跳过。运行态 Casdoor token、
alice/acme 与 beta/beta 的 owner/audience/tenant 绑定已另行做黑盒验证。

## 3. 82 项能力逐项结果

说明：PASS 可由真实黑盒、确定性反向用例与已通过的聚焦自动化共同支撑；FAIL 表示默认运行态存在可复现
功能或合同缺陷；BLOCKED 表示 A 模式明确不补云端凭据、不产生外部费用/副作用。

| 域 | PASS | FAIL | BLOCKED |
|---|---|---|---|
| Chat（10） | `chat.sync`, `chat.stream`, `chat.extract`, `chat.auto`, `chat.cascade`, `chat.mcp`, `chat.memory`, `memory.profile.get`, `memory.profile.clear`, `chat.cache.clear` | — | — |
| RAG（11） | `rag.query`, `rag.upload.file`, `rag.upload.file.shared`, `rag.upload.json`, `rag.upload.json.shared`, `rag.documents.list`, `rag.documents.get`, `rag.obsidian.import`, `rag.graph.query`, `rag.graph.entities` | `rag.documents.delete`（Obsidian 双链孤儿数据） | — |
| Agent（18） | `agent.run`, `agent.run.async`, `agent.tasks.list`, `agent.tasks.get`, `agent.tasks.stream`, `agent.tasks.cancel`, `agent.dag.run`, `agent.dag.plan-run`, `agent.dag.run.async`, `agent.dag.plan-run.async`, `agent.chain`, `agent.reflexive`, `agent.reflexive.stream`, `agent.analyst.run`, `agent.analyst.run.async` | `agent.vote`（非法候选数静默触发 3 次模型调用）, `agent.process.run`, `agent.process.run.async`（依赖当前已降级的 Workflow） | — |
| Async（8） | `async.create`, `async.list`, `async.get`, `async.status.update`, `async.lease`, `async.cancel`, `async.stream`, `async.deadletter` | — | — |
| Analytics（3） | `analytics.schema.tables`, `analytics.schema.describe` | `analytics.sql`（空问题 500；schema 还存在重复/冲突列） | — |
| Workflow（7） | `workflow.tasks.list`, `workflow.tasks.claim`, `workflow.tasks.unclaim`, `workflow.instances.get`, `workflow.data.purge` | `workflow.refund.start`, `workflow.tasks.complete`（AI assess/resolve 均连接容器内 localhost 后降级） | — |
| Multimodal（8） | `vision.caption.json`, `vision.caption.file`, `chat.vision` | `rag.image.ingest`, `rag.image.search`, `voice.chat.stream`（空音频 500） | `voice.transcribe`, `voice.chat`（未配置云端 key，按 A 不真实调用） |
| Interop（6） | `interop.agent-card`, `interop.a2a.agent-card`, `interop.mcp.tools`, `interop.mcp.tool`, `interop.mcp.call`, `interop.a2a.call` | — | — |
| Eval（6） | `eval.capabilities` | `eval.retrieval`, `eval.run`, `eval.suite.run`, `eval.dual-run`, `eval.gate`（默认受保护目标均被 Casdoor-only 拒绝） | — |
| Channel（5） | `channel.capabilities`, `channel.messages.send`, `channel.callbacks.async-task`, `channel.callbacks.workflow`, `channel.inbound` | — | — |

## 4. 关键真实链路证据

### 4.1 订单意图路由

- alice/acme：`POST /chat/auto {"message":"查询退款订单 204"}` -> 200，`route=TOOL`，订单状态“已退款”。
- 缺订单号 -> 200，返回补充订单号提示；不存在订单 -> 可解释未找到。
- `GET /orders/204`：alice 200；beta 404。
- `POST /agent/run` 使用 `order_query` 动作，observation 与最终答复均为订单 204“已退款”。

### 4.2 RAG 与租户隔离

- 私有 JSON、私有文件、共享 JSON、共享文件均成功上传并可检索。
- beta 对 acme 私有校验词为 0 命中；beta 对共享校验词命中 `visibility=public`。
- Obsidian zip 导入 2 篇笔记、1 条 wikilink，Graph 查询正确。
- 删除全部文档后文本/向量查询均为空；但 Graph 仍返回已删除笔记的边，证明生命周期缺陷。

### 4.3 异步任务与工作流

- Async：创建 202、重复 409、跨租户 404、lease owner 冲突 409、RUNNING -> SUCCEEDED、取消、
  终态拒绝 lease，以及 `Last-Event-ID` SSE 回放均通过。
- Workflow：同一 dedupeId 两次启动只产生实例 `12531`；claim/unclaim/complete、approve scope 403、
  beta 实例隔离与按 chatId 定向 purge 均正确。
- Workflow 日志明确显示两次 assess 与两次 resolve 都连接
  `http://localhost:8081/conversation/workflow/*` 失败，最终仅靠降级文案完成。

### 4.4 Vision、Interop、Eval、Channel

- Vision 文件、JSON 与 `/chat/vision` 返回一致 caption，模型 `vision-default`；坏 base64 为 400。
- MCP `platform.ping`、未知工具 404、A2A JSON-RPC 错误码、push config 的租户隔离均正确。
- Eval 对 open health case 可通过；同一 runner 对 `/auth/me` 返回 401。`platform-smoke` suite 0/1，
  dual-run 正确识别回归，gate 返回 422；问题是默认服务凭据失效，而非门禁算法。
- Channel 出站总开关为 false；测试请求返回 `ACCEPTED/outbound disabled`，未发生真实网络外发。合法
  async/workflow callback 为 202；入站签名当前按配置关闭。

## 5. 缺陷清单

### P1（发布阻断/核心能力不可用）

| ID | 缺陷 | 复现与根因 |
|---|---|---|
| SEC-01 | 下游服务可绕过网关匿名调用 | 宿主机直连 `:8081/chat` 无内部 JWT 仍 200，并真实调用模型，身份为 anonymous。过滤器只解析身份不拒绝；Compose 又发布下游端口。至少存在配额滥用风险。 |
| WF-01 | Workflow AI 子步骤整栈默认降级 | Compose 未给 workflow 设置 `CONVERSATION_BASE_URL`，容器回落到 `localhost:8081`；assess/resolve 重试耗尽。 |
| EVAL-01 | Eval 与 Casdoor-only 默认配置不兼容 | `EVAL_API_KEY=dev-key-acme` 在 edge `only` 模式恒 401；retrieval 还把 401 吞成空结果，可能输出伪 0 分报告。 |
| MM-01 | 原生图片向量能力启用但依赖不可达 | `RAG_MULTIMODAL_ENABLED=true`，未设可达 base URL，容器回落 `localhost:8000`；ingest/search 连续两次均 500。 |
| VOICE-01 | Voice 开关、目录与 provider 状态矛盾 | 后端 `VOICE_ENABLED=true`，目录 3 项均 `flag-off`；OpenAI base URL 已配但 key 为空，真实 ASR/TTS 不可用。 |
| RAG-01 | 删除 Obsidian 文档残留图谱关系 | wikilink 用 `docId` 作 `sourceId`，删除路径按 `displayName#` 清理，导致孤儿三元组继续可查。 |

### P2（应尽快修复）

| ID | 缺陷 | 证据 |
|---|---|---|
| ANA-01 | Analytics 空问题返回 500 | LangChain4j 报 `method 'answer' does not have a user message`，应入口校验为稳定 400。 |
| ANA-02 | Analytics `orders` schema 重复且类型冲突 | describe 同时出现 `id INT` 与 `id VARCHAR` 等两套列，可能误导 NL2SQL。 |
| AGENT-01 | Vote 边界值会产生意外费用 | `candidates:0` 未返回 4xx，而是静默采用默认 3 并真实调用 3 次模型。 |
| VOICE-02 | Voice stream 非法输入合同错误 | 空音频同步端点为 400，`/voice/chat/stream` 却返回 500。 |
| A2A-01 | Agent Card 鉴权声明与整栈不一致 | card 仅声明 `X-Api-Key`，但 Casdoor-only 下该凭据恒 401；实际黑盒用 Bearer 才能调用。 |
| OPS-01 | edge 对被重建下游的 Docker IP 刷新不可靠 | 单独 recreate auth 后 edge 连续 500，并仍指向旧容器 IP；recreate edge 后恢复。 |

低优先级构建提示：Vite 报 `src/auth/oidc.ts` 同时静态和动态导入，导致动态导入不能形成独立 chunk；
不影响本轮功能与构建通过。

## 6. UI 结果

真实浏览器用例为 **BLOCKED（环境原因）**：浏览器控制运行时返回可用实例列表为空，按工具约束未改用
其他自动化后端冒充 UI 黑盒。

可替代证据均通过：552 条前端测试覆盖 OIDC store/router、登录页、catalog gate、SSE、动态表单、9 个工作台、
移动 viewport、abort/晚到响应、脱敏与 curl 生成；类型检查和生产构建通过。最终重新构建并部署前端后，
public/dist/runtime catalog 已重新同步。仍需在有浏览器实例的环境补一次真实 OIDC 登录、桌面/移动导航和截图验收。

## 7. 安全与配置结果

- edge open path、无 token 401、非法 Bearer 401、Casdoor-only 拒绝 legacy API key/session Bearer均符合预期。
- alice 管理 RBAC 通过，beta 管理面 403；临时 role/user 的创建、If-Match 428、更新与删除闭环通过。
- refresh rotation 通过；旧 refresh token 重放 401；registration disabled 为 403；logout 204。
- CORS：`http://localhost:8093` preflight 200，非 allowlist origin 403。
- `X-Trace-Id` 可从 edge 响应回传；传入自定义 trace id 时保持一致。
- eval family 限流实际返回过 429；完整限流矩阵与 HS/RS JWT 双档由自动化测试覆盖。
- `RAG_AUTHZ_MODE=disabled` 的默认合同正常；enforce + 外部 SpiceDB 的 3 条真实集成测试本轮跳过。

## 8. 数据清理

已清理：

- 临时 RBAC 用户与角色，删除后均 404。
- 6 个 QA RAG 文档（私有/共享/文件/Obsidian），文本与向量查询均确认 0 命中。
- 发现缺陷后，按 tenant=`acme` + 两个精确 `sourceId` 从测试图数据库删除 1 条孤儿三元组；API 复查为空。
- Workflow 仅按 `chatId=qa-all-0722-1109-workflow` 清理 1 个实例，清理后 404。
- alice 的本轮 chat cache 与 memory profile。

保留的可识别测试记录：

- Async JDBC 中 `qa-all-0722-1109-async`（SUCCEEDED）与 `qa-all-0722-1109-cancel`（CANCELLED）。
  当前 API 只有取消、没有终态物理删除端点；未绕过产品合同删除。
- A2A 内存 push config `qa-all-0722-1109-a2a`，仅含不可达 localhost 测试 URL 和假 token，服务重启即清空。

未创建图片向量（embedding 在入库前失败），未真实调用 Voice 云服务，未发送任何真实外部渠道消息，未执行
宽范围 purge、DROP、TRUNCATE 或全表删除。

## 9. 建议修复顺序

1. 先封闭下游端口/强制内部 JWT；为服务间调用建立明确的 service identity，避免 anonymous 与 legacy key。
2. 修 Compose：Workflow 指向 `conversation-service:8081`；Eval 改用内部服务身份；多模态无 provider 时关闭开关；
   Voice 无 key 时后端与 catalog 一起关闭。
3. 修 Obsidian 图谱删除键，并新增“导入 -> 删除 -> graph 0 命中”的集成回归。
4. 修 Voice stream、Analytics 空问题、schema 去重和 Vote 参数范围，所有非法输入稳定返回 4xx。
5. 让 A2A card、live discovery 与实际 Casdoor 模式一致；增加 Compose 启动后的全能力 smoke，重点覆盖
   service DNS 重建、OIDC-only 的 Eval、Workflow 两次下游调用和 capability flag/config 一致性。

修完 P1 后应重跑本计划；只有 82 项无 FAIL、Voice/外部 authz 明确启用或诚实关闭，并补齐真实浏览器验收，
才适合把项目标记为“全部能力完善”。
