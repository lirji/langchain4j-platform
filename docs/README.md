# Project Documentation

这组文档面向新接手项目的工程师、架构评审者和集成方，用来快速了解平台能力、服务边界、部署方式和接口入口。

> **最新变更**：2026-07 一批方案级变更（Casdoor SSO 整栈默认开且 `only` 严格模式、继承式 RBAC、ES 全文混排 + RRF、order-service、前端移动端/响应式适配、LiteLLM spend 记账/failover、增强开关默认全开）汇总见 [变更记录](变更记录.md)。

## 推荐阅读顺序

1. [能力文档](参考/capabilities.md)：先看平台已经具备哪些业务和工程能力。
2. [架构文档](参考/架构文档.md)：理解微服务拆分、调用链路、共享库、事件流和数据边界。
3. [运行与配置手册](参考/operations.md)：本地启动、环境变量、验证命令和排障入口。
4. [接口与集成速查](参考/api-reference.md)：按服务查看主要 HTTP API、鉴权方式和典型请求。
5. [开发者指南](参考/developer-guide.md)：新增服务、跨服务 DTO、测试和提交建议。
6. [设计模式梳理](参考/设计模式.md)：项目落地的 GoF / 微服务 / 框架 / AI 编排模式，逐条对照真实代码，含"未使用"诚实清单与模式×模块矩阵。
7. [演进路线（历史存档）](迁移/migration-roadmap.md) / [迁移收尾方案与排期](迁移/migration-remaining-plan.md)：迁移主体已完成，路线图转为历史存档；当前状态以 [变更记录](变更记录.md)、[能力文档](参考/capabilities.md) 与 [业务场景总览](scenarios.md) 为准，收尾细节见 [迁移收尾方案](迁移/migration-gap-closure-plan.md)。

## 专题接入指南

> 大部分专题文档由冻结单体 `LangChain4j_project/docs/` 的深度设计文档迁移并适配到微服务架构而来（端点走边缘网关 :8080、配置改环境变量、按服务启动）。

**场景导航**
- [业务场景总览](scenarios.md)：知识库问答 / 智能客服(NL2SQL+工作流+渠道+语音) / 深度 Agent / 多模态 / 互操作——各场景怎么拼、入口端点、跑到什么程度。

**对话与检索**
- [RAG 接入指南](对话与检索/rag-guide.md)：文档/图片上传、向量库 provider、collection-per-tenant 隔离、embedding（hash/openai/ollama）、混合检索、检索增强（rerank/查询扩展/上下文分块/HanLP 分词）、GraphRAG、Obsidian 导入、语义缓存 L1。
- [ES 全文混排指南](对话与检索/es-hybrid-rerank.md)：Elasticsearch 真 BM25 全文分支并入四路混合检索、多源 RRF 融合、smartcn 中文分析器、`knowledge_segments_text` 索引与量纲处理。
- [ES / Kibana 查询速查](对话与检索/es-kibana-查询速查.md)：Kibana Dev Tools 常用 DSL、按租户/分类过滤、排障命令。
- [RAG API 演示](对话与检索/rag-api-demo.md)：`seed-kb.sh` / `rag-demo.sh` 驱动的上传→检索→查单闭环、示例语料与断言。
- [记忆指南](对话与检索/memory-guide.md)：多轮短期记忆窗口（messages/tokens/summary）+ 长期用户画像（`/chat/memory`、`/memory/profile`）。
- [语义缓存指南](对话与检索/semantic-cache.md)：L1 语义响应缓存、阈值与 embedding 独立性、按租户桶、`DELETE /chat/cache` 失效、文档变更跨服务失效。
- [模型级联指南](对话与检索/model-cascade.md)：便宜模型先答 → 置信门 → 升级强模型（`/chat/cascade`）；cheap/strong 逻辑模型经 LiteLLM 映射。
- [NL2SQL / ChatBI 指南](对话与检索/nl2sql-guide.md)：自然语言 → SQL → 执行 → 解读的受控链路、六层 SQL 安全护栏、数字 grounding、多租户库隔离。

**Agent 与编排**
- [Agent 能力与编排指南](Agent编排/agent-guide.md)：深度 Agent(ReAct)、多 Agent DAG（含重规划）、数据分析/业务流程(人在环)智能体、reflexion/voting/chaining、各 ReAct 动作（含 `order_query`）与开关。
- [工作流指南](Agent编排/workflow-guide.md)：两条「workflow」线——Flowable 退款审批业务引擎（`/workflow/**` + 三种终态通知）与 LLM 编排 5 模式（chain/routing/DAG/voting/reflexion，映射到 `/agent/**`）。
- [让 Agent 主动调接口（工具调用 / 自定义动作接入）](Agent编排/让Agent主动调接口.md)：怎么让模型在对话里自己决定调你的接口（如查订单）。两套机制——ReAct 动作 `AgentAction`（`/agent/run`，描述驱动的可插拔注册表）vs langchain4j 原生 `@Tool`（专用助手）；含 `order_query` 手把手示例、`description()` 即工具说明书、副作用双门控、租户/trace 自动透传、`@Tool` 别做成 Spring bean 的坑。
- [Code Interpreter 动作指南](Agent编排/code-exec.md)：agent `code_exec` 动作（默认关）；默认 subprocess 子进程隔离 + 可选 JShell、denylist/超时/输出截断与威胁模型。

**多模态与语音**
- [视觉指南](多模态语音/vision-guide.md)：vision-service `/vision/caption`·`/describe` + 对话入口 `/chat/vision` + agent `browser_see`；图像描述模型、大小/MIME 护栏。
- [语音指南](多模态语音/voice-guide.md)：voice-service 语音闭环 `/voice/transcribe`·`/voice/chat`(+`/stream`)，ASR(whisper)→对话大脑→TTS。

**互操作与渠道**
- [A2A 接入指南](互操作渠道/a2a-guide.md)：agent-card 发现、`/interop/a2a` JSON-RPC task 协议、`message/stream` 真流式 + push 中继、代理到 agent、live discovery。
- [MCP 接入指南](互操作渠道/mcp-guide.md)：agent 作 MCP client（`mcp_call`）与 interop 的 MCP tool surface。
- [钉钉知识库客服接入指南](互操作渠道/dingtalk-guide.md)：钉钉群 @机器人 → 查知识库 → 机器人回复；镜像飞书事件桥、机器人发消息 API 回复、无命中转人工兜底。

**平台工程（横切）**
- [登录、RBAC 与公共知识库指南](平台工程/rbac-and-public-kb.md)：auth-service 账号密码登录 → 会话令牌 + 刷新 cookie、边缘 `SessionBearerAuthFilter` 换发内部 JWT、角色→scope 展开、`role-admin`/`public-ingest` 平台 scope、后端 `/auth/admin/**` 管理面（If-Match 乐观锁；前端随包 RBAC 控制台已移除）、`__public__` 公共/共享知识库；另含**外接 auth-platform 的文档级 ReBAC / 部门层级隔离与 Casdoor 多租户 SSO 登录**（Casdoor SSO 整栈默认开、`only` 严格模式；文档级 ReBAC `app.rag.authz.mode` 默认 `disabled`，见文中授权章节）。
- [公网化 OIDC 改造方案](平台工程/公网化-OIDC-改造方案.md)：外部 IdP（Casdoor）SSO 落地方案——**已落地并成为默认**：edge `CasdoorTokenExchangeFilter` 验 Casdoor JWT 换发内部 JWT（`edge.casdoor.enabled` **默认开**、`mode` **默认 `only`** 严格，`dual` 作灰度回滚窗口）+ 前端方案 C 多租户登录（Shared Application + 选组织）+ 接 SpiceDB 文档级 ReBAC；与 auth-service 自建会话登录、api-key 并存（切 `dual` 时回退）。
- [事件总线与终态可靠投递(EOS)指南](平台工程/eventbus-guide.md)：事务性 outbox + relay + 消费侧去重 = effective exactly-once（workflow/async-task 两侧）。
- [长任务处理指南](平台工程/长任务处理指南.md)：async-task-service 租约式任务中心（提交/lease/回报、SSE 断点续传、webhook outbox、Kafka 生命周期事件）、各服务接入现状、通用长任务模式对照与当前限制。
- [可观测性指南](平台工程/observability-guide.md)：跨服务 traceId 透传、OTel GenAI span（Spring Boot 原生 tracing 开关）、Prometheus 指标、`/actuator/{tokenbudget,cost}`。
- [LiteLLM 网关能力指南](平台工程/litellm-gateway-guide.md)：spend 记账 + 管理 UI（自带 Postgres）、租户归因三档（`platform.gateway.tenant-attribution`=none/user/virtual-key，默认 none）、per-tenant virtual key 预算/TPM/RPM 硬保底、Redis 响应缓存、正式 fallback（chat-default→ollama）、LiteLLM↔Java 同 trace 的 OTel；含签发/轮换/备份/回滚 runbook 与 8 步冒烟。
- [成本归因与配额指南](平台工程/cost-attribution.md)：per-tenant USD 成本归因 + token 预算，redis 默认的分布式计数（水平扩容正确性）、`/actuator/{tokenbudget,cost}`；与 LiteLLM spend 双轨分工见 LiteLLM 网关能力指南。
- [评测指南](平台工程/eval-guide.md)：eval-service `/eval/**` 回归客户端、检索召回评测（Recall@k/MRR/Hit@k）、baseline suite、对冻结单体双跑 oracle 门禁。
- [部署指南](平台工程/deployment-guide.md)：本地 docker-compose、k8s/Helm 伞状 chart、External Secrets、Service DNS、Config Server。
- [能力展示与试用控制台](平台工程/能力展示控制台.md)：前后端分离的独立前端 `capability-showcase-frontend/`（Vue3 静态 SPA，可独立部署，**含移动端/响应式适配**）；82 条能力跨 9 模块、direct mode 跨域直调业务能力、能力五态诚实呈现、catalog 静态数据驱动；登录默认走 Casdoor OIDC（`VITE_AUTH_MODE` 源码默认 `apikey`、整栈构建期烘焙 `oidc`），亦支持账号密码/api-key；后端仅 edge-gateway 加 CORS。
- [能力前端模块拆分建议](平台工程/能力前端模块拆分建议.md)：平台能力 → 可独立拆出的前端模块（Chat/RAG/Agent/Async/Analytics/Workflow/Multimodal/Interop-Eval/Channel）的边界、优先级与拆分性。
- [数据存储清单](参考/databases.md)：MySQL/Redis/Qdrant/Elasticsearch/Kafka/H2 的端口、账号密码、所属服务与落库开关；含可选向量库（pgvector/milvus/chroma/doris）。
- [数据库与中间件清单](平台工程/数据库与中间件清单.md)：面向运维的中间件全景（含 auth 库、ES/Kibana、会话密钥、登录账号、前端端口）与端口对照。

## 当前定位

`langchain4j-platform` 是从原单体 `LangChain4j_project` 拆分出来的微服务平台。业务 API 统一从 `edge-gateway` 进入（Casdoor OIDC Bearer **或** 登录会话 Bearer **或** api-key → 内部 JWT；整栈默认 Casdoor `only` 严格模式，`dual` 时回退老路），所有 LLM 调用统一走 LiteLLM/OpenAI-compatible 网关。当前覆盖 auth（登录 + RBAC）、conversation、knowledge、agent、analytics、workflow、async-task、channel、interop、eval、vision、voice、order 等限界上下文，配套 config-server 配置中心、platform-eventbus 事件总线（Kafka 事务性 outbox + relay 的可靠终态投递），RAG 检索接 Qdrant 向量库 + Elasticsearch(smartcn) 全文混排，另有前后端分离的能力展示前端 `capability-showcase-frontend`。授权侧除内建 RBAC/多租户硬隔离外，已落地**外接 auth-platform（Casdoor 身份 + SpiceDB ReBAC）**：Casdoor 多租户 SSO 登录（方案 C，整栈默认开、`only` 严格）+ RAG 文档/部门层级细粒度授权（`app.rag.authz.mode`，默认 `disabled`，可切 `shadow`/`enforce`）。

## 文档维护原则

- 只把已经落地并有代码支撑的内容写入“当前能力”。
- 计划中或未完成的能力写入“限制与后续演进”。
- 服务间请求/响应 DTO 优先引用 `platform-protocol`。
- 配置项以各服务 `application.yml` 和 `deploy/docker-compose.yml` 为准。
