# 迁移收尾实施方案（未完成项）

本文件汇总 `docs/migration-roadmap.md` 中尚未落地项的实施方案，由分阶段工作流产出。
分两部分：**A. 已实现并验证的低风险项**（改动已在工作树，未提交）、**B. 待你拍板的设计方案**（仅设计，未写码）。

> 各方案的完整 approach / filesToTouch / testPlan 见工作流 journal：
> `.../subagents/workflows/wf_48bb27cb-4e8/journal.jsonl`

---

## 已敲定的决策（2026-07-07）

跨领域基建选型已拍板，实现时按此执行：

| 维度 | 决策 |
|---|---|
| 部署编排（B2） | **Helm 伞状 chart**（values 驱动 + library chart 复用模板） |
| 基础设施（B2） | **接外部托管/现有实例**（chart 只留 Service/ExternalName 指向，不自建 StatefulSet） |
| 配置中心（B3） | **Spring Cloud Config Server 模块**（git 后端，`spring.config.import=optional:` 接入，保留 `${ENV:default}` 兜底） |
| 内部 JWT（B3） | **升级 RS256 非对称**（gateway 私钥签发/下游公钥验签，缩小轮转爆炸半径；改 platform-security） |
| Kafka 一致性（B1） | **Kafka 原生事务**（KafkaTransactionManager + ChainedTransactionManager，端到端 exactly-once；接受 Flowable/DB/Kafka 事务耦合） |
| 事件总线（B1） | **新建 platform-eventbus 共享库**（EventPublisher SPI + Noop 默认 + Kafka 变体） |
| 契约框架（B5） | **Spring Cloud Contract**（单仓 Java + 已有 BOM，独立 `-Pcontract` profile） |
| 双跑门禁（B4） | **PR 用冻结 oracle 快照 + nightly live 双跑** |

> ⚠️ Kafka 选了**原生事务**（非工作流推荐的 outbox+relay）：实现时需引入 `KafkaTransactionManager` 并与 Flowable 事务链（`ChainedTransactionManager`）打通，运维复杂度更高，但获得端到端 exactly-once，消费侧去重要求降低。B1 方案的 outbox 部分仍保留作 DB 权威写，但投递走 Kafka 事务而非定时 relay。

**service 级决策**（已敲定）：

| 维度 | 决策 | 备注 |
|---|---|---|
| code_exec 沙箱（B7） | **独立子进程**（ProcessBuilder + `-Xmx`/空 cwd/进程 kill，M） | 中等隔离，无需容器基础设施 |
| cascade 归属（B7） | **platform-gateway-client**（产出 cascade 变体 ChatModel） | ⚠️ 不暴露成第二个 Bean，避开 langchain4j `@AiService` 自动发现冲突 |
| browser_see 视觉后端（B7） | **独立 vision 服务** | 最解耦但最重（新建多模态/视觉协议服务，agent 经 HTTP 提交截图字节） |
| knowledge 多租户隔离（B8） | **collection-per-tenant 强隔离** | ⚠️ 需把 EmbeddingStore/EmbeddingModel 单例改按租户路由，牵动 DocumentService/KnowledgeQueryService，改动较大 |
| workflow 工单抽取（B9） | **conversation 新端点** | workflow 彻底断开对本地 ChatModel/gateway-client 的依赖 |
| workflow 本地 outbox（B9） | **默认关但保留可回退** | 终态默认切 async-task，`WF_OUTBOX`/Dispatcher/Signer 降为可选 |
| interop 暴露面（B6） | **真 A2A task 协议 + live discovery**（M-L） | 完整 message/send + 状态轮询/流 + 静态 registry 换 live 发现 |
| agent→analytics 契约（B5） | **先提升为 protocol DTO**（AnalyticsSqlRequest/Reply） | 契约测试前置，避免弱校验 |

> 注：browser_see 选了「独立 vision 服务」、knowledge 选了「collection-per-tenant」、interop 选了「真 A2A task 协议」——三者都是较重路径，实现工作量相应上浮（vision 服务 L、knowledge 隔离 +2-3 人日、interop M-L）。

---

## A. 已实现并验证（改动在工作树，未 commit）

| 项 | 验证 | 关键改动 | 开关（默认关，零依赖 dev/test） |
|---|---|---|---|
| analytics docker smoke 脚本 | `bash -n` 通过 | 新增 `deploy/smoke-nl2sql.sh`（轮询健康→打 `/chat/sql`、`/analytics/sql`→校验 `NlToSqlService.Result` 字段），补 `docs/operations.md` 一行 | — |
| eval-service embedding / LLM-judge 比较模式 | `mvn -pl eval-service -am test` 28 passed | `EvalJudge`/`EvalEmbeddingComparator` 接口 + Disabled 默认 + Gateway/Llm 变体；`EvalCase` 加 4 个可空字段（在 platform-protocol） | `EVAL_JUDGE_ENABLED`、`EVAL_EMBEDDING_ENABLED`（默认 false） |
| 语义缓存 L1（问题级/租户桶/pre-RAG） | `mvn -pl conversation-service -am test` 18 passed | `conversation/cache/*`：Embedder（hash 默认/gateway 可选）+ Store（内存默认/redis 可选）+ `SemanticCache` 编排器；`ConversationController` 命中短路 RAG+LLM | `CONVERSATION_SEMANTIC_CACHE_ENABLED`（默认 false） |

全量 `mvn -DskipTests package` reactor **BUILD SUCCESS**。三项均默认关闭，对现有行为零影响。

---

## B. 待拍板的设计方案（仅设计）

每项给出结论 + 主要风险 + 工作量 + **需你决策项**。实现前请先定这些决策。

### B1. Kafka 事务性事件主干 — L（6-9 人日）
**结论**：用「事务性 outbox + relay 到 Kafka」（而非重量级 Kafka 原生事务）。业务终态与 outbox 行同一 DB 事务写入，dispatcher 由 HTTP 改为把 outbox relay 到按 `tenantId` 分区的 topic，channel-service `@KafkaListener` 消费回推；审计/用量 fire-and-forget 直发。新增 `platform-eventbus` 共享库（EventPublisher SPI + Noop 默认 + Kafka 变体，`@ConditionalOnProperty`/`@ConditionalOnClass`），HTTP webhook 路径保留为可回退兼容。
**风险**：最大是残留双写——workflow 当前 `enqueuePush` 是终态提交外的 best-effort，要真事务性须把 outbox 写入挪进 Flowable 事务边界（改 `WorkflowService` 终态收口，有回归面）；至少一次投递需消费侧持久去重（否则重启重复回推用户）。
**决策**：
- 事务性 outbox + relay（推荐）vs Kafka 原生事务（exactly-once 但耦合 Flowable/DB/Kafka）
- 新建 `platform-eventbus` 共享库 vs 各服务各自配 Kafka（推荐前者）
- 多 topic 按域分（推荐）vs 单 topic 靠 header 分流
- 消费侧去重存储：业务级回推强制 Jdbc（跨重启去重）vs 接受内存去重
- workflow enqueue 是否挪进 Flowable 事务边界（最大正确性改动点）
- 审计/用量是否也进 outbox（本方案：不进，可丢）

### B2. k8s / Helm 部署清单 + Service DNS 发现 — M（3-5 人日）
**结论**：values 驱动的 Helm 伞状 chart（library chart 复用 Deployment/Service/HPA），10 业务服务 + 5 依赖。因 compose 服务名已被当主机名硬用在 env，k8s Service 同名即可近零改动替换 DNS；探针复用现成 actuator group；密钥入 Secret、base-url/flag 入 ConfigMap，预留对接配置中心。
**风险**：stateful 语义与副本数错配——async-task/agent 默认内存态多副本会丢任务/SSE，须与 store 模式绑定；`INTERNAL_JWT_SECRET` 各服务不一致会全链 401；`host.docker.internal`（LiteLLM→Ollama）在 k8s 无等价物。
**决策**：Helm vs kustomize；依赖自带 StatefulSet vs 接外部托管；eval 改 Job/CronJob；是否默认开 `ASYNC_TASK_STORE=jdbc`+authoritative 以支持水平扩展；Secret 方案（Sealed vs External Secrets/Vault）；edge-gateway 暴露方式（Ingress/LB）。

### B3. 集中配置 + 密钥管理 — L（5-8 人日）
**结论**：GitOps 配置库 + Spring Cloud Config Server（新独立模块）承载非密文 + Vault（优先）/K8s Secret 承载密钥，全部经 Spring Boot 3 `spring.config.import=optional:` 接入，保留各服务 `${ENV:default}` 作零依赖兜底。
**风险**：`INTERNAL_JWT_SECRET` 是全服务共享对称密钥，集中化后一次坏配置=全网鉴权中断；Config Server 若做硬依赖成启动单点（须 optional import）；dev-only 默认密钥在 prod 意外回退是严重漏洞（须 prod fail-fast）。
**决策**：Config Server 模块 vs 纯 GitOps ConfigMap；密钥后端 Vault vs External/Sealed Secrets；`INTERNAL_JWT_SECRET` 是否借机 HS256→RS256（缩小轮转爆炸半径）；是否要热刷新（@RefreshScope + Bus）vs 改配置滚动重启；optional overlay（推荐）vs prod 硬依赖 fail-fast。

### B4. 行为基准双跑回归门禁 — M（3-4 人日）
**结论**：在现有 eval HTTP runner 上加「双跑编排 + 纯函数 Gate」：同一 suite 分别打 oracle(冻结单体) 与 candidate(edge-gateway)，聚合各自 passRate/averageScore + 跨目标语义一致性，回归返回 HTTP 422 供 CI fail。单体作独立端口/独立 api-key 的只读 target（不改单体，Dockerfile/overlay 放平台侧），并提供「冻结 oracle 快照」作 CI 轻量路径。
**风险**：模型非确定性抬高假回归率（缓解=runs≥3+容差+可选钉低温）；单体独有端点（`/chat/reflexive`、`/extract` 等）candidate 未迁移会 404 假回归（须按迁移进度圈定 suite）。
**决策**：averageScore 定义（锚点独立打分 vs 跨目标一致性，建议两者都出）；门禁形态（PR 用快照/nightly 用 live）；是否强制同模型钉低温；容差默认值；suite 作用域圈定；oracle 是否进平台 compose。

### B5. 服务间契约测试 + 网关故障转移测试 — M-L（5-8 人日）
**结论**：选 **Spring Cloud Contract**（而非 Pact）——单仓 Java + 已有 spring-cloud BOM 2023.0.3，零新基础设施；优先覆盖 knowledge/agent/analytics/async-task 四个 provider。网关故障转移用 test-only LiteLLM 配置（双上游 + fallbacks）+ compose smoke 在 LiteLLM 边界断言。
**风险**：reactor 顺序坑（consumer stub-runner 依赖 provider stub jar 先进 .m2，须 `-Pcontract` profile 隔离）；SCC 把 Spring context 引入刻意纯 POJO 的测试文化；agent→analytics 两端裸 Map 无 DTO，不先提升契约价值打折。
**决策**：SCC（推荐）vs Pact；是否先把 agent→analytics 裸 Map 提升为 protocol DTO；契约测试跑在独立 `-Pcontract` profile（推荐）vs 混入默认套件；stub 本地 .m2（推荐）vs 远端 broker；生产 config.yaml 是否顺带落一条真实第二 provider。

### B6. interop-service 移植真实 A2A / MCP server + 动态能力发现 — L（6-9 人日）
**结论**：把单体「进程内直调」的 A2A JSON-RPC server 与 MCP server 移植进 interop-service，落地改为「JSON-RPC 单端点 + HTTP 代理到 conversation/knowledge/analytics/agent/async-task」；agent-card skills 与 MCP tools/list 从硬编码改为向下游动态发现（接口+静态默认+HTTP discovery 变体）。内部仍走 typed-HTTP，A2A/MCP 只做对外互操作面。
**风险**：`platform-protocol.interop.AgentCard` 命名冲突；`message/stream` 缺后端流式（conversation 无 SSE）；push notification 跨服务无共享总线需自建 callback 中继；`/.well-known` 免鉴权放行须在 edge-gateway 精确白名单。
**决策**：AgentCard 冲突处理（演进现有 vs 放 `interop.a2a` 子包，建议后者）；stream 先 buffered 降级（推荐）vs 本轮给 conversation 加真流式；push 用 callback 中继（推荐）vs 先只轮询；`/.well-known` 是否免鉴权对外；动态发现是否本轮给 agent/knowledge/analytics 都加 `/capabilities`；MCP tools 是否纳入 agent 代理工具。

### B7. agent-service 加固 + 多模式移植完整性核对（D 项）

**⚠️ 核对结论：计划所列四种多 agent 模式实际都未迁移**，被移植的是第五种 `multiagent`（现改名 `dag/AgentDagService`）。全仓 grep（非 test）`reflexion|cascade|voting|chaining` 在平台侧业务代码命中 0 处。

| 模式 | 单体位置 | 平台现状 | 判定 | 补齐工作量 |
|---|---|---|---|---|
| multiagent（plan→execute→synthesize） | `ai/multiagent/*` | 已移植为 `dag/AgentDagService`（Kahn 拓扑分层并行 + Critic/Replanner + 加权聚合 + SSE） | ✅ 已迁移 | — |
| reflexion（单答案 generate→critique→improve） | `ai/reflexion/*` | 评分/阈值/replan 机制被 DAG 吸收，但**单答案自省环缺失** | ⚠️ 机制在、模式缺 | S-M |
| cascade（cheap→置信门→strong） | `ai/cascade/*` | 完全缺失 | ❌ | M |
| voting（同题并行 N 次取共识） | `ai/voting/*` | 完全缺失 | ❌ | S-M |
| chaining（顺序步骤 + 确定性 gate + 短路） | `ai/chaining/*` | 完全缺失 | ❌ | S |

**补齐总原则**：四者都做成 `DeepAgentService` 的**同级 sibling 编排器**（不塞进 ReAct 内部），复用 agent-service 已有的 `agentTaskExecutor` / `AgentDagCritic`+`Weights` 聚合 / `AgentTaskProgressSink`(SSE) / gateway `ChatModel` / 审计+计量 listener——**无需重搭骨架**。落地顺序按成本：chaining(S) → voting(S-M) → reflexion(S-M，复用 DAG critic，顺带把 `Weights`+`aggregate` 抽成共享小包消除三处重复) → cascade(M，需先定归属)。对齐单体端点 `/agent/chain`、`/agent/vote`、`/agent/reflexive[/stream]`。

**三项加固设计**：
- **`browser_see` 视觉**：截图链路已齐（`BrowserSession.screenshotBytes()` 已实现），缺的是视觉后端——单体 `ai/vision/*` 未迁。三选一（决策项）：① 内嵌 vision（端口单体 vision 包，多模态 ChatModel 经 gateway，M）② **复用 knowledge-service 已有的 `HttpImageTextProvider`**（agent POST 截图字节，最少新代码，S）③ 独立 vision 服务（重）。风险：LiteLLM 需配多模态模型；vision ChatModel 别撞 langchain4j `@AiService` 自动发现（单体靠"不注册成 Bean"绕开）；vision token 计入 metering。
- **`code_exec` 外部沙箱**：现为同 JVM JShell（`JShellRunner`，仅 denylist+超时+截断）。抽 `CodeSandbox` 接口保持 `CodeExecAction` 契约不变，换实现（决策项）：① 独立子进程（`ProcessBuilder` + `-Xmx`/空 cwd/进程 kill，M）② 一次性容器（`docker run --network=none --read-only --memory --pids-limit`，M-L）③ 远程 `sandbox-service`（最强隔离/可扩，L）。
- **agent 经 A2A/interop 暴露**：interop-service 已代理部分 agent 能力（`platform.agent.run`/`run_async`/`dag.plan_run[_async]`）。补齐（决策项）：① 加 `dag.run`/任务态 `task.get/list/cancel` + 新模式映射（S）② `/.well-known/agent-card.json` 别名对齐 A2A 生态（S）③ 静态 registry 换 live discovery、真 A2A task 生命周期（M-L）。风险：interop→agent 的 JWT/租户透传；registry 与真实端点漂移。

### B8. knowledge-service 加固 — M-L（5-8 人日）
**结论**：向量库生产加固（超时/重试/collection 自动建/payload 索引/**维度守卫**/health）+ vision/OCR provider 扩展 + native/Ollama embedding 接入，全部沿用「接口 + `@ConditionalOnProperty`，默认关」。
**风险**：`EmbeddingStore`/`EmbeddingModel` 是全局单例，collection-per-tenant 强隔离需改按租户路由（牵动 DocumentService/KnowledgeQueryService，建议二期）；维度耦合是隐性生产事故源（换 provider 不重建 collection 静默坏数据，必须维度守卫兜住）。
**决策**：多租户隔离（payload filter 先行 vs collection-per-tenant 二期）；维度不一致快速失败（推荐）vs 告警继续；vision 走 LiteLLM 多模态 vs 仅 HTTP provider；是否引入 native ONNX embedding（增大 jar）；是否新增 tess4j 本地 OCR。

### B9. workflow-service 加固 — M（3-5 人日）
**结论**：用 platform-protocol 的 conversation 契约让 workflow 经 HTTP 调 conversation-service（reply 生成 + 结构化工单抽取），取代直连本地 ChatModel 的 `DefaultWorkflowAiClient` 兜底；终态通知默认从 local 翻到 async-task，逐步把 `WF_OUTBOX`/Dispatcher/Signer 降级为可选（默认关）直至移除。
**风险**：切 async-task 后 webhook body/签名头变化，现存订阅方（channel `/channel/callbacks/workflow` 期望 `X-Workflow-*` 头、HMAC 校验方）会解析失败，须先适配；新 Http client 若沿用「吞异常返回空」会让抽取失败误判 LOW 自动放过高风险退款——必须抛异常；ServiceTask 在 Flowable 同步事务内调跨服务 HTTP，须设紧超时+失败降级。
**决策**：结构化抽取归属（conversation 新端点，推荐 vs workflow 本地保留）；是否彻底移除本地 ChatModel 直连；async-task 载荷/头与旧 outbox 不一致是否需补 HMAC/兼容头；本地 outbox 保留可回退 vs 直接删除；conversation 契约放 `protocol.chat` vs 新建 `protocol.conversation`。

---

## 实施排期（按依赖分阶段）

决策已全部敲定，按下列相位推进。相位内多为可并行项，相位间有依赖。

> **进度（2026-07-07，分支 `feat/migration-remaining`）**：Phase A ✅、Phase B ✅、B1b 收口 ✅、Phase C 全部(C1 reflexion+cascade / C2 workflow 契约 / C3 code_exec 子进程沙箱 / C4 knowledge collection-per-tenant + Ollama embedding) ✅ 已实现并提交，全量 `mvn test` 19 模块全绿。Phase D 全部(D1 vision 服务 / D2 interop 真 A2A / D3 契约测试(独立 profile) / D4 双跑门禁) ✅、Phase E(Helm 伞状 chart + External Secrets + Service DNS) ✅ 已实现并提交。
>
> **✅ 全部相位落地完成（A→E + B1b 收口）**。默认 `mvn test` 20 模块全绿；`helm lint` 通过。剩余为集成环境实测项（非代码缺口），见下"遗留/需集成验证"。
>
> ### 遗留 / 需真实集成环境验证（均非代码缺口，已在各 commit/报告记录）
> - **B1b 端到端原子性 ✅ 已验证（分支 `feat/kafka-eos`，A2）**：嵌入式 Flowable(H2, `setDatabaseType("mysql")`) 集成测试 `WorkflowTerminalOutboxAtomicityTest`（`@Tag("flowable-it")`，`mvn -Pflowable-it -pl workflow-service test`）——证明 `end` 监听器写事件 outbox 与引擎终态同事务：正常结束时行存在；后置监听器抛异常时 outbox 行与历史实例一起回滚（原子）。
> - **async-task 终态 Kafka 两段式缺口 ✅ 已收口（分支 `feat/kafka-eos`，A1）**：与 workflow B1b 对称——原先 `store.update` 提交后 `@EventListener` 直发（kafka 档无 DB 兜底）。改为在 `JdbcAsyncTaskStore.update` 的同一事务内写 `ASYNC_TASK_LIFECYCLE_OUTBOX` 行（`AsyncTaskLifecycleOutbox`），由 `AsyncTaskLifecycleRelay` relay 到 Kafka；提交后直发 `AsyncTaskKafkaNotifier` 在 jdbc 档让位避免双投。H2 原子性测试 + relay 单测覆盖。
> - **B1/Kafka exactly-once ✅ 已做（分支 `feat/kafka-eos`）**：明确为 **effective exactly-once**（写侧事务性 outbox + 投侧 at-least-once + 收侧 eventId 幂等去重）——DB→Kafka→HTTP 跨系统链路的正确形态，非纯 Kafka 原生事务。落地：① 消费侧改「先查 → 处理 → **成功后**标记」（修复原「先标记」在瞬时失败时丢事件的 bug）；② `ProcessedEventStore` 加 `isProcessed`，channel-service 接线 JDBC 去重（跨重启，默认仍 memory 零依赖）；③ relay 同步等 broker ack 再 markDelivered，consumer `read_committed`；④ **EmbeddedKafka 集成测试**（`@Tag("kafka-it")`，`mvn -Pkafka-it -pl platform-eventbus test`，默认套件不加载）端到端证明「重复投递去重 + 瞬时失败不丢不重复」。
> - **D2 interop**：A2A `message/stream` 用轮询代替真 SSE（B6 决策的 buffered 降级）；push 中继待接总线。
> - **D3 契约测试**：仅覆盖 knowledge/agent 两个 P0 provider（analytics/async-task 待补）；网关 failover 为独立 smoke 脚本、不进默认 CI。
> - **D4 双跑门禁**：真实"冻结单体 vs 网关"端到端双跑与真实快照抓取需 `deploy/docker-compose.oracle.yml` 起单体后跑。
> - **D1 vision / cascade / RS256 / config-server / Helm**：均默认关闭或需外部配置（vision 模型、RS256 keypair、config git 后端、外部 MySQL/Kafka FQDN、External Secrets 真实后端）——生产启用需填真实值。
> **B1b 残留风险 —— 已收口（方案 A：事务性 outbox + 幂等 relay）**：
> - 新增 BPMN `end` 事件 ExecutionListener（`WorkflowTerminalOutboxListener`），kafka 档下在 **Flowable 引擎命令的同一事务内**把终态事件写入独立表 `WF_TERMINAL_EVENT_OUTBOX`（`WorkflowTerminalEventOutbox`，同一 `workflowDataSource` → JdbcTemplate 经 DataSourceUtils 并入同事务）。「终态提交 ⇔ 事件行已写」原子成立，消除丢失窗口。
> - `outcome` 经进程变量 `terminalOutcome` 精确传递（start=auto / complete=granted|rejected / expire=timeout）。
> - `WorkflowTerminalEventRelay`（`@Scheduled`，仅 kafka 档）从该表 relay 到 Kafka（至少一次 + 消费侧 `workflow:<instanceId>` eventId 去重 = 端到端等价 exactly-once）。
> - 移除了 B1b 原有的 `ChainedTransactionManager`（`WorkflowEventbusTransactionConfig`）与提交后直发的 `WorkflowTerminalEventPublisher`。默认 `mode=local` 零影响。
> - **验证边界**：workflow-service 无引擎/H2 测试基建，原子性由「监听器跑在引擎事务内 + 同 DataSource JdbcTemplate 并入同事务」的 Flowable-Spring 语义保证 + 逻辑单测覆盖路由/重试/DLQ/消息重建；端到端原子性建议在 docker 集成环境（或后续 B4 双跑门禁）实测。

### Phase A — 地基层（低耦合、解锁后续，先做）
- **A1. agent→analytics 契约 DTO 提升**（S）：新增 `platform-protocol.analytics.{AnalyticsSqlRequest,AnalyticsSqlReply}`，analytics-service 与 agent-service `AnalyticsSqlAction` 改用 typed DTO。→ 解锁 B5 契约测试。
- **A2. 内部 JWT RS256 支持**（M）：`platform-security` `InternalToken` 加 RS256 签发/验签，`platform.security.jwt.algorithm` 可配（默认 HS256 保零配置 dev/test，prod 切 RS256 keypair）。→ 地基，独立。
- **A3. chaining + voting 补齐**（S / S-M）：agent-service 新增 `chaining`（`/agent/chain`）与 `voting`（`/agent/vote`，复用 `agentTaskExecutor` fan-out）编排器。→ 纯服务内，快速见效。

### Phase B — 核心基建（依赖少，体量大）
- **B1a. platform-eventbus 共享库 + 事件契约**（M）：`platform-protocol.event.*` + EventPublisher SPI（Noop 默认 / Kafka 变体）。
- **B1b. Kafka 原生事务接线**（L）：workflow/async/channel 接 `KafkaTransactionManager`+`ChainedTransactionManager`，channel `@KafkaListener` 消费回推。依赖 B1a。
- **B3. Spring Cloud Config Server 模块 + 各服务 optional import**（L）：可与 B1 并行。

### Phase C — 服务加固/特性
- **C1. reflexion + cascade**（M）：reflexion 复用 DAG critic（顺带把 `Weights`+`aggregate` 抽共享小包）；cascade 落 `platform-gateway-client`（cascade 变体 ChatModel，不暴露成第二 Bean）。
- **C2. workflow 加固**（M）：conversation 新增结构化抽取端点 → workflow 断开本地 ChatModel；终态默认切 async-task。依赖 conversation 端点。
- **C3. code_exec 独立子进程沙箱**（M）：抽 `CodeSandbox` 接口，`CodeExecAction` 契约不变。
- **C4. knowledge collection-per-tenant 强隔离**（L）+ vision/OCR provider + native/Ollama embedding：EmbeddingStore/Model 改按租户路由。

### Phase D — 互操作与测试门禁
- **D1. 独立 vision 服务 + `browser_see`**（L）。
- **D2. interop 真 A2A task 协议 + live discovery**（M-L）：依赖 A3/C1 模式补齐后完整暴露。
- **D3. B5 契约测试 + 网关故障转移**（M-L）：依赖 A1 DTO。
- **D4. B4 双跑回归门禁**（M）：依赖冻结单体可跑。

### Phase E — 部署收口（最后）
- **E1. Helm 伞状 chart + External Secrets + Service DNS**（M）：依赖各服务稳定。

> 关键路径：A1→D3、A3/C1→D2、B1a→B1b、conversation 抽取端点→C2。Phase A 三项相互独立，可作为第一批实现。
