# 能力文档

## 一句话概览

本项目是一个基于 Spring Boot、LangChain4j、LiteLLM 和 DDD 限界上下文拆分的企业级 AI 微服务平台。它把对话、语义缓存、模型级联、多轮记忆与长期画像、意图路由、安全护栏（PII/注入）、知识库 RAG/GraphRAG（四路混排：向量 + 内存关键词 + Elasticsearch BM25 全文 + 图谱，RRF 融合；含 rerank / 查询扩展 / 上下文增强 / 公共共享库）、NL2SQL、多种 Agent 编排模式（ReAct / DAG / 反思 / 投票 / 链式）、工作流审批、异步任务中心、渠道接入、A2A/MCP 互操作、多模态视觉、语音闭环（ASR→对话→TTS）和回归评测拆成独立服务，并通过两层网关、账号登录 + RBAC、租户鉴权、事件总线、集中配置、共享协议和可观测能力连接起来，另配一个前后端分离的能力展示前端。

## 访问方式与端口约定

- **唯一对外入口是 `edge-gateway`（:8080）**：校验 `X-Api-Key`**或** `Authorization: Bearer <会话 accessToken>` → 签发短时内部 JWT（`X-Internal-Token`）→ 按路径路由到下游。下文所有"经网关"的业务端点都可以用 `http://localhost:8080` + api-key（或登录后的会话 Bearer）访问。
- **服务直连端口**（本地调试/服务间调用）：conversation `:8081`、workflow `:8082`、analytics `:8083`、knowledge `:8084`、agent `:8085`、async-task `:8086`、channel `:8087`、interop `:8088`、eval `:8089`、vision `:8090`、voice `:8091`、**auth `:8092`（登录 + RBAC）**、order `:8093`（按订单号只读查订单，agent order_query 下游；compose 宿主机映射 8094）。
- **前端与检索基础设施**：能力展示前端 `capability-showcase-frontend`（:8093，Vue3 静态 SPA / nginx，前后端分离，浏览器跨域直调网关）；RAG 检索基础设施 Elasticsearch（:9200，BM25 全文混排）+ Kibana（:5601，查询 UI）+ Qdrant（:6333/:6334，向量库）。
- **不经 edge-gateway 暴露**：`config-server`（:8888，集中配置基础设施，仅服务内部经 `spring.config.import` 拉取）。
- **默认开关基线**：两套「默认」需分清——**application.yml 裸跑默认**（2026-07 起大多数**行为增强**已默认打开：RAG 增强、多轮记忆/长期画像、注入/PII 护栏、语义缓存、模型级联、意图路由、看图对话、RAG rerank/查询扩展/上下文增强/GraphRAG/ES 全文/公共库/多模态、DAG 重规划、agent 动作 vision/analytics/workflow/order、interop discovery、eval judge/embedding、vision 等；仅 MCP、code-exec、浏览器、语音、渠道入站桥/事件、RAG enforce 授权、自助注册、成本归因等仍默认关；同时 embedding 仍用确定性 `hash`、部分存储用内存，保单测零外部依赖）与 **docker-compose demo 默认**（再叠加真实基础设施：qdrant/redis/jdbc/ES + nomic embedding + hanlp 分词 + 登录 RBAC）。下文标注以 application.yml 默认为准，并在有差异处注明 compose 覆盖。

## 核心能力矩阵

| 能力域 | 能力（一句话） | 关键端点 / 载体 | 默认开关 |
|---|---|---|---|
| 边缘网关 | API key 鉴权、租户识别、内部 JWT 签发、服务路由、限流 | `edge-gateway`（:8080） | 常开 |
| 内部 JWT | 跨服务身份令牌，支持 HS256（默认）或 RS256 非对称签发/验签 | `platform-security` | HS256（默认）；RS256 需配 keypair |
| 登录与会话 | 账号密码登录 → 会话 accessToken（Bearer）+ httpOnly 刷新 cookie；边缘 `SessionBearerAuthFilter` 换发内部 JWT，下游零改动 | `/auth/login`·`/auth/refresh`·`/auth/logout`·`/auth/me`（auth :8092） | RBAC 关时仍可登录（用直配 scopes）；注册 `AUTH_REGISTRATION_ENABLED=false` |
| RBAC 权限管理 | 角色→scope 展开、用户/角色 CRUD 管理面、`role-admin`/`public-ingest` 平台 scope、乐观锁 If-Match | `/auth/admin/users/**`、`/auth/admin/roles/**` | yml 默认关；compose demo `AUTH_RBAC_ENABLED=true`+写开放 |
| LLM 网关 | 所有模型调用统一走 OpenAI 兼容端点，provider 路由/failover 在 LiteLLM | `platform-gateway-client` + LiteLLM | 常开 |
| 集中配置 | Spring Cloud Config Server，native（默认）/git 后端，`optional:` 接入不阻断启动 | `config-server`（:8888） | native 后端默认可用；git 需 `SPRING_PROFILES_ACTIVE=git` |
| 事件总线 | EventPublisher SPI，Noop 默认 / Kafka 变体，幂等生产 + 消费去重（effective exactly-once） | `platform-eventbus` | `platform.eventbus.enabled=false`（默认 Noop） |
| 对话 | `/chat` 入口，默认带 RAG 上下文增强 | `/chat`（conversation :8081） | RAG 增强 `CONVERSATION_RAG_ENABLED=true`（默认开） |
| 语义缓存 L1 | 问题级 / 租户桶 / pre-RAG 语义缓存，命中即短路 RAG+LLM；含失效端点 | `/chat` 旁路、`DELETE /chat/cache` | `CONVERSATION_SEMANTIC_CACHE_ENABLED=true`（默认开） |
| 模型级联 | 便宜模型先答、低置信才升级强模型 | `/chat/cascade`（conversation） | `CHAT_CASCADE_ENABLED=true`（默认开） |
| 意图路由 | LLM-as-Router 自动分类问题再分派应答 | `/chat/auto`（conversation） | `CONVERSATION_ROUTER_ENABLED=true`（默认开） |
| 视觉对话 | 看图对话，图片+问题委托 vision-service | `/chat/vision`（conversation multipart） | `CONVERSATION_VISION_ENABLED=true`（默认开） |
| MCP 对话 | 工具来自外部 MCP server 的对话 | `/chat/mcp`（conversation） | `CONVERSATION_MCP_ENABLED=false` |
| 结构化抽取 | 自由文本 → 结构化 POJO（工单等） | `/extract?type=`（conversation） | 端点常开（抽取器注册表） |
| 多轮记忆 | 按会话隔离的滑窗记忆（messages/tokens/summary），内存或 Redis | `/chat`、`/chat/stream` 记忆键 | `CONVERSATION_MEMORY_STORE=in-memory`（常开，内存滑窗） |
| 长期画像 | 跨会话用户画像抽取/召回、合规查看与删除 | `/chat/memory`、`GET/DELETE /memory/profile` | `CONVERSATION_MEMORY_PROFILE_ENABLED=true`（默认开） |
| 安全护栏 | 提示注入前置拦截、PII 输出脱敏，流式/非流式一致 | `/chat` 前后置 | 注入 `CONVERSATION_GUARDRAIL_INJECTION_ENABLED=true`（默认开，block）、PII `CONVERSATION_GUARDRAIL_PII_ENABLED=true`（默认开） |
| 知识库 RAG | 文档上传、Tika 抽取、分块、四路混排检索（向量 + 内存关键词 + ES BM25 + 图谱）、可配置排序权重 | `/rag/documents/**`、`/rag/query`（knowledge :8084） | 上传/查询常开；hybrid `RAG_HYBRID_ENABLED=true` |
| ES 全文混排 | Elasticsearch 真 BM25 全文分支并入混合检索，多源 **RRF 融合**（免疫量纲差），smartcn 中文分析器 | `/rag/query` 召回、索引 `knowledge_segments_text` | `RAG_ES_ENABLED=true`（默认开）；`RAG_FUSION_STRATEGY` 空 → ES 开启时有效默认 `rrf` |
| 公共/共享知识库 | 查询在隔离查各自租户基础上并入 `__public__` 公共分区；写共享库需 `public-ingest` scope | `/rag/documents?visibility=public`、`/rag/query`、`/rag/config` | `RAG_PUBLIC_ENABLED=true`（默认开） |
| 检索重排 | 召回放大后二次打分重排（LLM-as-judge 或 Jina reranker） | `/rag/query` 后置 | `RAG_RERANK_ENABLED=true`（默认开，llm 档；jina 需 key） |
| 查询扩展 | 用 LLM 生成 query 变体扩大召回 | `/rag/query` 前置 | `RAG_QUERY_EXPANSION_ENABLED=true`（默认开） |
| 上下文增强 | 入库时逐 chunk 加文档级上下文前缀再嵌入（Contextual Retrieval） | `/rag/documents` ingest 期 | `RAG_CONTEXTUAL_ENABLED=true`（默认开） |
| 中文分词 | keyword hybrid 支持 HanLP 中文分词（默认 simple 零依赖） | `/rag/query` keyword 路 | `RAG_HYBRID_TOKENIZER=simple`（默认）/`hanlp` |
| Obsidian 导入 | 把 zip 打包的 Obsidian vault 批量导入 RAG（`[[双链]]`→GraphRAG 三元组） | `POST /rag/obsidian/import`（multipart） | 端点常开 |
| 多租户隔离 | collection-per-tenant 强隔离（每租户独立 collection/namespace） | knowledge 向量库 | `RAG_VECTOR_STORE_ISOLATION=collection-per-tenant`（默认） |
| 向量存储 | qdrant 默认；in-memory/pgvector/milvus/chroma/doris 可选，均 collection-per-tenant | knowledge | `RAG_VECTOR_STORE_PROVIDER=qdrant`（yml/compose 默认，可退 in-memory） |
| Embedding | 确定性 hash / OpenAI-compat（LiteLLM）/ Ollama | knowledge | `RAG_EMBEDDING_PROVIDER=hash`（yml 默认）；compose 走 `ollama`/`nomic-embed-text`（768 维） |
| GraphRAG | 确定性三元组抽取、实体链接、邻居查询、可选融合到 `/rag/query` | `/rag/graph/query`、`/rag/graph/entities` | `RAG_GRAPH_ENABLED=true`（默认开） |
| 图谱持久化 | in-memory 或 JDBC/MySQL | knowledge | `RAG_GRAPH_STORE=jdbc`（默认） |
| NL2SQL / ChatBI | 自然语言转 SQL、只读查询保护 | `/chat/sql`、`/analytics/sql`（analytics :8083） | 常开 |
| 工作流审批 | Flowable 退款审批流、人工审批、超时、终态通知 | `/workflow/**`（workflow :8082） | 常开；终态通知模式可切 async-task |
| 深度 Agent（ReAct） | 单次/异步 ReAct loop，rag/sql/time/delegate 等动作 | `/agent/run`、`/agent/run/async`（agent :8085） | `AGENT_ENABLED=true` |
| DAG 多 Agent | 显式 DAG / 自动规划 DAG / 异步，拓扑分层并行，可选 critique+replan | `/agent/dag/run[/async]`、`/agent/dag/plan-run[/async]` | 常开；replan `AGENT_DAG_REPLAN_ENABLED=true`（默认开） |
| 链式 Agent | 顺序步骤 + 步间确定性 gate + 不过即短路 | `/agent/chain` | 端点常开；`steps` 默认空需先配置 |
| 投票 Agent | 同题并行 N 次，majority 或 synthesis 取共识 | `/agent/vote` | 端点常开（N=3，majority） |
| 反思 Agent | 单答案 answer→critique→improve 自省环，加权聚合达阈值即停 | `/agent/reflexive[/stream]` | 端点常开 |
| Agent 工具 | 可选受限代码执行、MCP client、无头浏览器动作、视觉看图 | agent 动作注册表 | 代码执行 / MCP / 浏览器动作默认关闭；视觉后端默认开 |
| 异步任务中心 | 通用任务状态、租户隔离、取消、SSE 断点续订、worker lease、webhook outbox | `/async/tasks/**`、`/async/webhook-outbox/dead`（async-task :8086） | 常开；JDBC 存储 `ASYNC_TASK_STORE=in-memory`（默认） |
| 渠道接入 | 渠道能力、webhook/飞书/钉钉/voice 出站、飞书&钉钉入站事件桥（→ /chat → 回复）、callback、HMAC 签名、可选 Kafka event | `/channel/**`（channel :8087） | 出站/签名/事件桥/Kafka 全默认关 |
| 互操作（A2A/MCP） | 真 A2A JSON-RPC task 协议、agent-card、MCP tool surface、可选 live discovery | `/interop/**`、`/interop/a2a`、`/.well-known/agent-card.json`（interop :8088） | 常开；live discovery `INTEROP_DISCOVERY_ENABLED=true`（默认开） |
| 回归评测 | HTTP case/suite 执行、检索质量评测、双跑门禁、contains/JSON-path/semantic/oracle 断言 | `/eval/**`（eval :8089） | 常开；judge/embedding 比较默认开 |
| 多模态视觉 | 图片 caption/描述（多模态 ChatModel 经 LiteLLM），供 knowledge/agent 复用 | `/vision/caption`、`/vision/describe`（vision :8090） | `VISION_ENABLED=true`（默认开；须配 `VISION_MODEL` 否则 fail-fast） |
| 语音闭环 | 音频 → ASR(whisper) → `/chat` → TTS，支持整轮 / SSE 半流式 / 纯转写 | `/voice/chat`、`/voice/chat/stream`、`/voice/transcribe`（voice :8091） | `VOICE_ENABLED=false`（默认整服务不装配） |
| 订单查询（工具调用示例） | 按订单号确定性只读查订单（状态/金额/客户/日期），持久化 MySQL、参数化按租户隔离；供深度 Agent 的 `order_query` 动作在对话里自主调用 | `/orders/{orderNo}`（order :8093）；agent 侧 `order_query` 动作 | order-service 常开；agent 侧 `AGENT_ORDER_ENABLED=true`（默认开） |
| 审计与计量 | 审计日志、LLM audit listener、token budget、cost attribution | `platform-audit`、`platform-metering` | audit/budget 常开、cost 默认关 |
| 可观测性 | trace id 生成与跨服务透传 | `platform-observability` | 常开 |
| 能力展示前端 | 前后端分离的独立静态 SPA（Vue3/nginx），凭证感知导航、direct mode 跨域直调、能力五态诚实呈现、RAG 租户/共享双视图、移动端/响应式适配 | `capability-showcase-frontend`（:8093） | 独立部署；**前端 RBAC 管理控制台已移除**（身份/授权外置 Casdoor + auth-platform），后端 `/auth/admin/**` API 仍在 |

## 技术栈总览（技术清单）

覆盖整个项目用到的语言、框架、AI/数据/消息组件、协议与工具。版本以根 `pom.xml`（统一依赖管理）和 `deploy/docker-compose.yml` 为准。

### 语言与运行时 / 构建
- **Java 21**（`maven.compiler.release=21`），全模块统一。
- **Maven** 多模块：父 `pom.xml`（`packaging=pom`）聚合 7 个共享库 + 13 个服务（含 `auth-service`、`order-service`）+ edge-gateway/config-server，统一版本管理。**无 Maven wrapper**，用系统 `mvn`。前端 `capability-showcase-frontend` 是独立的 Vite/Vue3 项目，不在 Maven reactor 内。
- **Spring Boot 3.3.5**（`spring-boot-starter-parent`）、**Spring Cloud 2023.0.3**。
- 打包可执行 fat jar（`spring-boot-maven-plugin`）；Docker 镜像基于 `eclipse-temurin:21-jre`。
- **刻意未用**：JPA/Hibernate/MyBatis、Flyway/Liquibase、Lombok、任何 linter/格式化/静态分析（风格靠约定）。

### Web 与网关
- **Spring Cloud Gateway**（WebFlux / Project Reactor，仅 `edge-gateway`）—— 唯一对外入口，路由表 + 全局过滤器（api-key→JWT、限流）。
- **Spring MVC**（Servlet，`spring-boot-starter-web`）—— 所有下游服务的 `@RestController`。
- 服务间调用用 `RestTemplate`（装 `OutboundTenantForwarder` + `OutboundTraceForwarder` 拦截器，铸发内部 JWT + 透传 traceId）。

### AI / LLM
- **LangChain4j 1.13.1**（`langchain4j-bom` 统一版本，`langchain4j-spring-boot-starter`）：声明式 `@AiService` / `AiServices.builder`、全局单个 `ChatModel` bean、`ChatModelListener` SPI（审计/计量/成本的挂载点）。
- **LiteLLM**（外部，非 Java 模块）：统一 OpenAI 兼容 LLM 网关，provider 路由 / failover / 模型名映射集中在 `deploy/litellm/config.yaml` —— **Java 侧无任何 provider switch**。
- 已接入的 LangChain4j provider/集成：`langchain4j-open-ai`（走 LiteLLM）、`langchain4j-ollama`、`langchain4j-anthropic`、`langchain4j-mcp`（1.13.1-beta23）、`langchain4j-document-parser-apache-tika`、`langchain4j-easy-rag`。
- **本机 Ollama**（默认本地模型后端，如 `llama3.1`、embedding `nomic-embed-text`）；容器经 `host.docker.internal:11434` 访问。

### RAG / 向量 / 全文 / 知识图谱（`knowledge-service`）
- **四路混排检索**：向量（余弦）+ 内存关键词（BM25 近似）+ **Elasticsearch 真 BM25 全文** + GraphRAG 三元组，交 `HybridFusionService` 融合。融合策略 `RRF`（`1/(k+rank)`，k=60，免疫 BM25/余弦量纲差；ES 开启时有效默认）或 `WEIGHTED_MAX`（历史加权语义）。
- **Elasticsearch 8.15.x**（自建镜像装 `analysis-smartcn` 中文分析器，:9200 + Kibana :5601）：自研 `EsGateway`（低层 `RestClient` + 手工 JSON），索引 `knowledge_segments_text`（keyword 精确字段 + `text`/smartcn 全文）；ingest 期同步 upsert、查询期 `match+filter` 召回。刻意排除 Spring 的 ES 自动配置/健康指示器（避免 ES 关闭时 readiness DOWN）。
- 向量库集成：**Qdrant**（`langchain4j-qdrant`，yml/compose 默认）、**in-memory**（零依赖回退）；另打包 **Milvus / Chroma / pgvector**（含向量+PG 全文 RRF 的 HYBRID 模式）/ **Doris** 可选后端（gRPC 版本钉到 `1.59.1` 以兼容 Milvus + Qdrant 共存）。
- Embedding：确定性 hash（yml 默认，64 维）/ OpenAI 兼容（LiteLLM）/ Ollama（compose 默认 `nomic-embed-text`，768 维，带非对称 query/document 任务前缀）。切换 provider = 换向量维度，需重灌。
- 文档抽取：**Apache Tika**（PDF/Office/HTML/纯文本）；分块：Markdown header / parent-child / semantic；中文分词 `simple`（默认零依赖）/ `hanlp`（compose 默认，更准）。
- **GraphRAG**：自研确定性三元组抽取 + 实体链接 + 邻居查询；存储 in-memory 或 JDBC/MySQL（默认 JDBC）。
- **图片多模态 RAG**：走 OpenAI 兼容多模态 embedding 端点（`jina-clip-v2`，1024 维），独立 image collection（`knowledge_images_<tenant>`），text→image 跨模态检索；不参与四路文本融合。
- **公共/共享知识库**：保留租户分区 `__public__`，查询并入、写入需 `public-ingest` scope（见安全/RBAC）。

### 工作流引擎
- **Flowable**（`flowable-spring`，BPMN 2.0）—— `workflow-service` 退款审批流，自管其数据库表（同一 MySQL 数据源）。

### 数据与存储
- **MySQL 8.4**（`mysql-connector-j`）：Flowable / async-task / knowledge 图谱 / channel 去重 / **auth 账号·角色·刷新会话** / NL2SQL demo 等。**裸 `JdbcTemplate` 直连**，表结构靠 `Jdbc*Store` 里的 `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE` 演进（无迁移工具），连接池 HikariCP（Spring Boot 默认）。JDBC 存储多为可选开启（compose 默认多切 JDBC）、application.yml 默认内存。
- **Redis 7**（`spring-boot-starter-data-redis`）：语义缓存 store、knowledge 文档 registry（默认）、事件去重、限流/token 预算/成本计数等后端。
- **Qdrant**：向量持久化（yml/compose 默认向量库）。
- **Elasticsearch 8.15.x + Kibana**（自研 `EsGateway`，非 Spring Data ES）：RAG 全文 BM25 混排索引 `knowledge_segments_text`，自建镜像装 `analysis-smartcn` 中文分析器（compose 默认开启，knowledge-service 待其健康探针就绪再启动）。
- **H2**（`test` scope）：DB 相关单测的内存库（无 Testcontainers）。

### 消息 / 事件总线
- **Apache Kafka 3.8.0**（`spring-kafka`）：`platform-eventbus` + workflow/async-task/channel。事务性 **outbox + relay** + 消费侧 eventId 幂等去重 = **effective exactly-once**。默认 Noop（`platform.eventbus.enabled=false`），零 Kafka 依赖。

### 安全 / 鉴权
- **JJWT 0.12.6**（`jjwt-api/impl/jackson`）：内部 JWT 签发/校验，**HS256（默认）或 RS256**。
- 边缘双模换发内部 JWT：`X-Api-Key`（`ApiKeyToInternalTokenFilter`）**或** 登录会话 `Authorization: Bearer`（`SessionBearerAuthFilter`，会话密钥独立于内部密钥）→ 统一 `X-Internal-Token`；`TenantContext`（ThreadLocal）跨服务传播；自研 token-bucket 限流（`platform-security/ratelimit`）。
- **auth-service（登录 + RBAC）**：`SessionTokenIssuer` 签发会话 accessToken（60min）+ 服务端只存哈希的 httpOnly 刷新令牌（7d，一次性轮转）；密码 **BCrypt**（`spring-security-crypto` 的 `BCryptPasswordEncoder`，不引入 Spring Security 过滤链）；RBAC 角色→scope 展开、`role-admin`/`public-ingest` 平台 scope、管理写端点 `If-Match` 乐观锁；存储 in-memory 或裸 `JdbcTemplate`（`USERS`/`ROLES`/`ROLE_SCOPE`/`USER_ROLE`/`AUTH_SESSION`）。
- 渠道 webhook 自研验签：飞书 `SHA-256 + AES-256-CBC + verification token`、钉钉 `HmacSHA256(timestamp+"\n"+appSecret)`。

### 配置中心
- **Spring Cloud Config Server**（`config-server`）：native（打包 classpath）默认 / git 后端可选；各服务 `spring.config.import=optional:configserver:...` 接入，不可达不阻断启动。

### 可观测 / 审计 / 计量
- **Micrometer + Prometheus**（`spring-boot-starter-actuator`，`/actuator/prometheus`）；health/liveness/readiness 探针。
- `platform-observability` traceId 生成 + 跨服务透传；`platform-audit` 审计日志 + LLM `ChatModelListener`；`platform-metering` 按租户 token budget + cost attribution（actuator `tokenbudget`/`cost` 端点）。

### Agent 工具技术
- **langchain4j-mcp**：agent 作 MCP client（`mcp_call`）。
- **Playwright 1.47.0**（Chromium 无头浏览器）：`browser_*` 动作。
- **JShell / 子进程**：`code_exec` 受限代码执行（中等隔离，非强沙箱）。
- 多模态视觉：LangChain4j 多模态 `ChatModel`（vision-service，经 LiteLLM）。

### 互操作 / 渠道协议
- **A2A**（Agent-to-Agent，JSON-RPC over HTTP，task 协议）+ `/.well-known/agent-card.json`；**MCP**（Model Context Protocol）tool surface（interop）+ MCP client（agent）。
- 渠道：**飞书**事件回调、**钉钉**机器人消息回调（+ 机器人发消息 API 回复）、通用 webhook / voice HTTP、Kafka channel events。

### 测试
- **JUnit 5 + Mockito + AssertJ**，`*Test` 同包；刻意纯 POJO 单测（不用 Spring context / `@SpringBootTest`）求速；DB 相关用内存 **H2**（无 Testcontainers）。

### 部署
- **Docker Compose**（`deploy/docker-compose.yml`，本地整网：LiteLLM + Redis + MySQL + Kafka + Qdrant + Elasticsearch/Kibana + config-server + 全部业务服务含 auth-service + 前端容器 `capability-showcase-frontend`）；变体 `docker-compose.failover.yml` / `.oracle.yml`（`.es.yml` / `.rag-full.yml` 现已并入主栈默认，仅供冒烟分层）。
- **一键启动脚本**：`start-all.sh`（全 docker 含前端 nginx :8093）、`start-dev.sh`（后端 docker + 前端 vite HMR :5173）、`start-local.sh`（仅后端，本机端口重映射 18080/18090/13307 避开 apollo）。
- **Helm / Kubernetes**（`deploy/helm` 伞状 chart，生产路径：External Secrets、Service DNS、Config Server）。
- 演示/冒烟脚本：`seed-kb.sh`（灌示例知识库）、`rag-demo.sh`（RAG 闭环演示）、`smoke-qdrant-rag.sh`、`smoke-es-hybrid-rag.sh`（ES 混排断言 source∈{es,hybrid}）、`smoke-rbac.sh`（登录→角色展开→换发内部 JWT→admin 门）、`smoke-a2a.sh`、`smoke-nl2sql.sh`、`smoke-failover.sh`。

### 各模块技术落点（速查）

| 模块 | 端口 | 关键技术 |
|---|---|---|
| edge-gateway | 8080 | Spring Cloud Gateway (WebFlux) · JJWT（会话 Bearer / api-key 双模换发内部 JWT）· 限流 · CORS |
| auth-service | 8092 | 登录/会话 JWT · BCrypt · RBAC（角色/scope）· MySQL（用户/角色/刷新会话）/内存 |
| config-server | 8888 | Spring Cloud Config Server |
| conversation | 8081 | LangChain4j · Redis（语义缓存） |
| workflow | 8082 | Flowable BPMN · Kafka · MySQL |
| analytics | 8083 | LangChain4j · MySQL（只读 NL2SQL） |
| knowledge | 8084 | LangChain4j · Qdrant/Milvus/Chroma/pgvector · **Elasticsearch(smartcn) BM25 + RRF** · Tika · hash/Ollama/OpenAI embed · HanLP · MySQL（图谱）· Redis |
| agent | 8085 | LangChain4j · langchain4j-mcp · Playwright |
| async-task | 8086 | Kafka · MySQL · SSE |
| channel | 8087 | Kafka · MySQL（去重）· 飞书/钉钉验签 |
| interop | 8088 | A2A JSON-RPC · MCP surface |
| eval | 8089 | HTTP 回归 · 可选 judge/embedding |
| vision | 8090 | LangChain4j 多模态 ChatModel |
| voice | 8091 | OpenAI 兼容 ASR(whisper)/TTS · 分句半流式 SSE · 转发 conversation `/chat` |
| capability-showcase-frontend | 8093 | Vue3 + Vite 静态 SPA · nginx · 跨域直调网关 · 前后端分离（非 Maven 模块） |
| platform-\* | — | security(JJWT) · observability · gateway-client · protocol · audit · metering · eventbus(Kafka) |

## 平台基建

### 两层网关

- **`edge-gateway`（:8080）** —— 唯一对外入口。校验 `X-Api-Key` **或** 登录会话 `Authorization: Bearer`（`SessionBearerAuthFilter` order -110 先于 api-key filter -100），按绑定关系识别租户/用户/scope，签发短时内部 JWT（`X-Internal-Token`），按路径路由，并对分离部署的展示前端做 CORS 放行。路由表覆盖 auth/conversation/analytics/workflow/knowledge/agent/async-task/channel/interop/eval/vision/voice，并对 `/.well-known/agent-card.json`、`/auth/login|register|refresh|logout|public-config`、渠道入站事件放行免鉴权。
- **LiteLLM**（外部，非 Java 模块） —— LLM 网关。所有模型调用统一走一个 OpenAI 兼容端点，provider 路由/failover/模型名映射都在 LiteLLM 配置里，Java 侧没有任何 provider `switch`。

### 内部 JWT（HS256 / RS256）

- `platform-security` 的 `InternalToken` 负责签发/校验内部 JWT，租户身份随其跨每一次网络跳传播，下游还原进 `TenantContext`。
- 算法可配（前缀 `platform.security.jwt.*`）：默认 `HS256`（对称，沿用 `INTERNAL_JWT_SECRET`，≥32 字节，保零配置 dev/test）；设 `platform.security.jwt.algorithm=RS256` 时 edge-gateway 用私钥签发、下游用公钥验签（PEM 或纯 base64，私钥 PKCS#8 / 公钥 X.509），缩小密钥轮转爆炸半径。
- 下游是否接受直连 `X-Api-Key`：`allow-api-key-fallback`（默认 true，本地调试用；生产可关只信 JWT）。

### 登录、会话与 RBAC（auth-service，:8092）

- **自建账号登录**：`POST /auth/login` 账号密码换取会话 accessToken（会话 JWT，`SESSION_JWT_SECRET` 签发，默认 60min）+ httpOnly 刷新 cookie（默认 7d，`POST /auth/refresh` 一次性轮转，`/auth/logout` 撤销）。会话令牌与 api-key 是**并存的两条入口**，边缘统一换发内部 JWT，下游零改动。跨域直调网关时刷新 cookie 需 `AUTH_COOKIE_SAME_SITE=None` + `AUTH_COOKIE_SECURE=true`。
- **RBAC**：种子角色 `viewer/editor/analyst/approver/admin`；有效 scopes = 角色展开 ∪ 直配 scopes，签发令牌时展开写入。新增平台 scope `role-admin`（RBAC 管理面门禁）、`public-ingest`（写公共知识库），只经 `admin` 角色获得、不挂 api-key。管理面 `/auth/admin/{users,roles}/**` 整类受 `AUTH_RBAC_ENABLED` 门控，写端点再受 `admin-writes-enabled`（关→503）与 `If-Match` 乐观锁（缺失 428 / 冲突 412）约束。
- **默认差异**：application.yml 默认 `AUTH_STORE=in-memory`、RBAC/写全关；compose demo 默认 `jdbc` + `AUTH_RBAC_ENABLED=true` + 写开放 + `AUTH_RBAC_BOOTSTRAP_ADMIN_USERS=alice`，自助注册 `AUTH_REGISTRATION_ENABLED` 恒默认关。demo 账号 `alice`(acme/admin)、`bob`(globex/viewer)、`analyst-a`(tenantA/analyst)，口令 `demo12345`，与内置 api-key 租户/scope 镜像对齐。详见 [RBAC 与公共知识库指南](../平台工程/rbac-and-public-kb.md)。

### 集中配置（config-server）

- 独立模块 `config-server`（:8888），Spring Cloud Config Server。
- 默认 `native` 后端：读打包进 jar 的 `classpath:/config/`（`config/application.yml` = 全服务共享默认，`config/<service>.yml` = 单服务覆盖），dev/无 git 环境即开即用。
- 可选 `git` 后端：`SPRING_PROFILES_ACTIVE=git` + `SPRING_CLOUD_CONFIG_SERVER_GIT_URI`，GitOps 库承载非密文配置；密钥仍走各服务 `${ENV:default}` / Vault / K8s Secret。
- 各业务服务用 `spring.config.import=optional:configserver:${CONFIG_SERVER_URI:...}` 接入 —— **optional 前缀保证 Config Server 不可达时不阻断启动**，`${ENV:default}` 兜底继续生效。

### 事件总线（platform-eventbus）

- 新共享库，`EventPublisher` SPI：默认 `NoopEventPublisher`（`platform.eventbus.enabled=false`，无任何 Kafka 依赖），开启后走 Kafka 变体。
- 生产侧幂等（`enable.idempotence`，默认开）+ 同步等 broker ack 再 `markDelivered`（供 outbox relay 避免误标已投）；可选事务性生产者（配非空 `platform.eventbus.producer.transactional-id-prefix` 启用事务）。
- 消费侧幂等去重存储：`processed-event-store=memory`（默认，重启失忆）| `jdbc`（跨重启）；失败重试超限进 `<topic>.DLT`。
- 端到端定位为 **effective exactly-once**（写侧事务性 outbox + 投侧 at-least-once + 收侧 eventId 幂等去重）。workflow 终态、async-task 生命周期均以「同事务写 outbox 行 → relay 到 Kafka」落地原子性，channel-service `@KafkaListener` 消费回推并做 eventId 去重。

### 审计、计量、可观测

- `platform-audit`：审计日志 + LLM audit `ChatModelListener`。
- `platform-metering`：按租户 token budget（默认开，内存）+ cost attribution（默认关）。actuator 暴露 `tokenbudget`、`cost` 端点。
- `platform-observability`：`TraceIdFilter` 生成并跨服务透传 traceId。

## 业务能力说明

### 1. 对话、语义缓存与模型级联

`conversation-service`：

- **`POST /chat`**：默认即先调 `knowledge-service` 的 `/rag/query` 做 RAG 上下文增强（`CONVERSATION_RAG_ENABLED=true` 默认开），把检索结果注入 prompt 再交给 LLM（`CONVERSATION_RAG_TOP_K`、`CONVERSATION_RAG_CATEGORY`、`CONVERSATION_RAG_MIN_SCORE` 等可调）；置 `false` 退回纯 LLM 直答。
- **L1 语义缓存**（默认开，`CONVERSATION_SEMANTIC_CACHE_ENABLED`，置 `false` 关闭）：应用侧、问题级、按租户分桶、在 RAG 之前。命中即短路 RAG+LLM 直接返回。相似度阈值 `CONVERSATION_SEMANTIC_CACHE_THRESHOLD`（默认 0.95），embedding provider `hash`（默认）/`openai`，store `in-memory`（默认）/`redis`（TTL `CONVERSATION_SEMANTIC_CACHE_REDIS_TTL`，默认 0=不过期）。
- **`DELETE /chat/cache`** 语义缓存失效：清当前租户的缓存桶（带 `question` 参数则定向失效某问题）。租户取自内部 JWT，只能清自己的桶；语义缓存关闭时 no-op。知识库更新后调它可避免 `/chat` 返回缓存旧答案（也可由 knowledge-service 自动触发，见 §2）。
- **`POST /chat/cascade`** 模型级联（默认开，`CHAT_CASCADE_ENABLED`，端点仅在开启时装配，置 `false` 关闭）：便宜模型先答，低置信才升级强模型。`CHAT_CASCADE_CHEAP_MODEL` / `CHAT_CASCADE_STRONG_MODEL` 为 LiteLLM 里的逻辑模型名（留空退化为网关默认模型），置信门 `CHAT_CASCADE_CONFIDENCE_THRESHOLD`（默认 0.6），可选 `CHAT_CASCADE_SELF_RATING`。返回体含 `served=cheap|strong`。cascade 变体 ChatModel 由 `platform-gateway-client` 产出。
- **`POST /conversation/workflow/reply`**、**`POST /conversation/workflow/ticket`**：给 workflow-service 用的回复生成 + 结构化工单抽取端点，让 workflow 经 HTTP 调用而不直连本地 ChatModel。
- **`POST /chat/auto`** 意图路由（默认开，`CONVERSATION_ROUTER_ENABLED`）：LLM-as-Router 用 temp=0 判官模型先给问题分类，再分派到对应应答路径；返回体含 `route`（路由类型）、`reason`、`classifyMs`/`answerMs`。记忆键（`<tenantId>::<chatId>`）与 `/chat` 一致，与普通对话共享多轮记忆。未启用时返回明确禁用提示。
- **`POST /chat/vision`** 看图对话（multipart，默认开，`CONVERSATION_VISION_ENABLED`）：图片 base64 后连同 `message`（作为 vision 指令）转发给 vision-service（`CONVERSATION_VISION_BASE_URL`，默认 `http://localhost:8090`），返回 `reply`/`model`/`chars`。未启用或缺图返回明确提示。
- **`POST /chat/mcp`** MCP 工具对话（默认关，`CONVERSATION_MCP_ENABLED=true` 开启）：工具来自外部 MCP server（`CONVERSATION_MCP_TRANSPORT=stdio`（默认）|`http`），由 `McpAssistant` 挂载工具后应答。未启用时返回禁用提示。
- **`POST /extract?type=ticket`** 结构化抽取（端点常开）：自由文本 → 结构化 POJO（langchain4j structured output），用抽取器注册表按 `type` 分派，未知类型返回 400。新增目标类型只需注册一个 `type → 抽取函数`。
- **多轮记忆**（默认内存滑窗，常开）：`Assistant.chat(@MemoryId ...)` 按会话隔离记忆。`CONVERSATION_MEMORY_STORE=in-memory`（默认）|`redis`（`CONVERSATION_MEMORY_REDIS_TTL`，默认 `P7D`）；滑窗 `CONVERSATION_MEMORY_WINDOW_MODE=messages`（默认，`_MAX_MESSAGES=20`）|`tokens`（`_MAX_TOKENS=2000`，tokenizer 依据 `_TOKEN_MODEL=gpt-4o-mini`）|`summary`（旧消息用 LLM 压缩成中文摘要）。默认零外部依赖。
- **长期用户画像**（默认开，`CONVERSATION_MEMORY_PROFILE_ENABLED`）：跨会话抽取并召回用户画像事实。`POST /chat/memory` 带画像对话、`GET /memory/profile` 查看、`DELETE /memory/profile` 合规清空。租户+用户取自内部 JWT，天然只操作自己的画像。`_STORE=in-memory`（默认，redis 变体待补）、`_MAX_ITEMS=50`、`_RECALL_LIMIT=12`、`_ASYNC=true`（异步抽取不阻塞应答）。未启用时端点返回禁用提示。
- **安全护栏**（提示注入 + PII，均默认开）：controller 层前置扫描输入、后置脱敏输出，纯确定性逻辑、流式/非流式一致，不依赖 langchain4j guardrail SPI。注入护栏 `CONVERSATION_GUARDRAIL_INJECTION_ENABLED` + `CONVERSATION_GUARDRAIL_INJECTION_MODE=block`（命中即拒答，默认）|`sanitize`（剥离控制 token 后继续）|`audit`（仅记日志放行）；PII 护栏 `CONVERSATION_GUARDRAIL_PII_ENABLED` 对输出里的 email/手机号/身份证就地脱敏。两者默认开；如需放行可显式置 `false`。

### 2. 知识库、RAG 与 GraphRAG

`knowledge-service`（`/rag/**`）：

- **文档 ingestion**：`POST /rag/documents`（JSON 文本或 multipart 文件）、`GET /rag/documents`、`GET /rag/documents/{docId}`、`DELETE /rag/documents/{docId}`。Apache Tika 抽取 PDF/Office/HTML/纯文本；支持 Markdown header、parent-child、semantic 等分块。
- **图片多模态 embedding（CLIP）**（默认开，`RAG_MULTIMODAL_ENABLED`；需可达的 CLIP/jina 多模态 embedding 端点，缺后端调用期报错）：图片走原生 CLIP / jina-clip 多模态 embedding，向量存入独立 image collection（`knowledge_images_<tenant>`，与文本集合隔离）。`POST /rag/image`（multipart `image`）入库、`POST /rag/image-search`（文本 query 跨模态检索图片）；通用 `POST /rag/documents` 传 `image/*` 或 `imageBase64` 也走多模态。关闭（`RAG_MULTIMODAL_ENABLED=false`）时上传图片返回 400。⚠️ 旧的「图 → 文字（caption/OCR）」路径（`RAG_IMAGE_TEXT_*`）已移除，不再接受 `caption`/`ocrText`。
- **检索（四路混排 + RRF）**：`POST /rag/query`（别名 `/knowledge/query`）并行召回四路——vector（向量）、keyword（内存 BM25 近似）、es（Elasticsearch 真 BM25 全文，`RAG_ES_ENABLED=true` 默认开）、graph（可选）——交 `HybridFusionService` 融合。融合策略 `RAG_FUSION_STRATEGY`：留空时 ES 参与查询则**有效默认 `rrf`**（`1/(k+rank)`，`RAG_FUSION_RRF_K=60`，免疫 BM25/余弦量纲差），否则 `weighted_max`（权重 `RAG_RANKING_VECTOR_WEIGHT`/`_KEYWORD_WEIGHT`/`_ES_WEIGHT`/`_GRAPH_WEIGHT`）。命中多源标 `source=hybrid`。keyword hybrid 默认开（`RAG_HYBRID_ENABLED=true`）。
- **ES 全文索引**：ingest 时 `SegmentIndexer` 把同批明文分块同步 upsert 进 `knowledge_segments_text`（`RAG_ES_INDEX_NAME`）；字段 `tenantId/docId/category/...` 为 keyword 精确过滤、`text` 用 smartcn 中文分析器全文（`RAG_ES_ANALYZER=smartcn`，不可用 standard）。ES 写失败默认 best-effort（`RAG_ES_FAIL_FAST=false`），查询失败降级返回空、由内存关键词源兜底。开启 ES 后需重灌历史文档以填充索引（`seed-kb.sh`）。
- **公共/共享知识库**（默认开，`RAG_PUBLIC_ENABLED`）：保留租户分区 `__public__`；各租户查询在隔离查自己分区基础上并入公共分区（向量/keyword/ES 三路并，graph 本期不并），命中标 `visibility=public`。写共享库 `POST /rag/documents`（`visibility=public|shared`）需 `public-ingest` scope（否则 403），列/查共享库普通登录用户即可；运行时 `GET /rag/config` 回显 `publicKbEnabled` 供前端决定是否展示共享库视图。
- **多租户强隔离**：`RAG_VECTOR_STORE_ISOLATION=collection-per-tenant`（默认）—— 每租户独立 collection/namespace，EmbeddingStore/Model 按租户路由；可退回 `shared`（单 store + metadata filter）。
- **向量存储**：`RAG_VECTOR_STORE_PROVIDER=qdrant`（yml/compose 默认，`QDRANT_HOST`/`QDRANT_PORT`）| `in-memory`（零依赖回退）| `pgvector`（`RAG_PGVECTOR_*`，含 `SEARCH_MODE=HYBRID` 向量+PG 全文 RRF）| `milvus`（`RAG_MILVUS_*`）| `chroma`（`RAG_CHROMA_*`）| `doris`（自研 JDBC + HNSW ANN，`RAG_DORIS_*`）。collection/表基名统一由 `RAG_VECTOR_STORE_BASE_COLLECTION`（默认 `knowledge_segments`）决定。
- **Embedding provider**：`RAG_EMBEDDING_PROVIDER=hash`（默认，确定性本地）| `openai`（走 LiteLLM/OpenAI 兼容）| `ollama`（`RAG_EMBEDDING_OLLAMA_BASE_URL`、`RAG_EMBEDDING_OLLAMA_MODEL`，默认 `nomic-embed-text`）。含维度守卫、超时/重试参数。
- **GraphRAG**（默认开，`RAG_GRAPH_ENABLED`）：`POST /rag/graph/query`（实体邻居查询）、`GET /rag/graph/entities`。确定性三元组抽取，受控格式 `subject|relation|object`（换行或分号分隔）；`RAG_GRAPH_INCLUDE_IN_QUERY` 决定是否融合进 `/rag/query`；关系白名单 `RAG_GRAPH_RELATION_WHITELIST`、别名 `RAG_GRAPH_ALIASES`、最大跳数 `RAG_GRAPH_MAX_HOPS`。图谱存储 `RAG_GRAPH_STORE=in-memory`（默认）| `jdbc`（MySQL）。
- **语义缓存失效联动**（默认开，`RAG_CACHE_INVALIDATION_ENABLED`）：文档 `upload`/`delete` 成功后，尽力而为地调 conversation 的 `DELETE /chat/cache` 失效同租户语义缓存（松耦合、带内部 JWT 传播租户、失败只记日志不阻断 ingest），配合 §1 的失效端点实现「文档更新 → 缓存即新鲜」。关闭时为 Noop 实现，零影响。
- **检索重排（rerank）**（默认开，`RAG_RERANK_ENABLED`）：先按 `RAG_RERANK_CANDIDATE_MULTIPLIER`（默认 3）放大召回，再二次打分重排。`RAG_RERANK_TYPE=llm`（默认，复用共享 temp=0 ChatModel 让 LLM 打 0..1 相关性分，零外部依赖）|`jina`（Jina reranker 云 API，`RAG_RERANK_JINA_MODEL`，默认 `jina-reranker-v2-base-multilingual` + `JINA_API_KEY`）。关闭时为 Noop 不重排。
- **查询扩展（query-expansion）**（默认开，`RAG_QUERY_EXPANSION_ENABLED`）：用共享 temp=0 ChatModel 为原 query 生成同义/多角度变体扩大召回，`RAG_QUERY_EXPANSION_MAX_VARIANTS`（默认 4，含原 query）。关闭时为 Noop 不扩展。
- **上下文增强（Contextual Retrieval）**（默认开，`RAG_CONTEXTUAL_ENABLED`）：入库时逐 chunk 用共享 temp=0 ChatModel 生成「该片段在全文中的位置与主题」一句上下文前缀再嵌入，提升脱离全文后的可检索性；`RAG_CONTEXTUAL_MAX_DOC_CHARS`（默认 8000）限制喂给生成器的文档截断。每 chunk 一次 LLM 调用，仅 ingest 期发生。关闭时为 Noop 不增强。
- **中文分词（HanLP）**：keyword hybrid 检索的分词器 `RAG_HYBRID_TOKENIZER=simple`（默认，零依赖）|`hanlp`（HanLP 中文分词，切词更准，随 knowledge 依赖引入，需 HanLP 词典）。
- **Obsidian vault 导入**：`POST /rag/obsidian/import`（multipart `file`=vault zip，可选 `category`）把一个 zip 打包的 Obsidian 库批量导入 RAG —— 每篇 `.md` 笔记成为一个文档、`[[双链]]` 成为 GraphRAG 三元组；缺文件返回 400。端点常开，需带具备 ingest 权限的 api-key。

### 3. Agent 编排（五种模式）

`agent-service`（`/agent/**`，`AGENT_ENABLED=true` 默认开）。所有模式共用 gateway `ChatModel`、审计+计量 listener、SSE 进度 sink。

- **深度 Agent（ReAct）**：`POST /agent/run`（同步）、`POST /agent/run/async`（返回 taskId）。内置动作 `rag_search`（调 knowledge）、`analytics_sql`（调 analytics，`AGENT_ANALYTICS_ENABLED=true`）、`current_time`、`delegate`（受控委派，`AGENT_ALLOW_DELEGATION`/`AGENT_MAX_DEPTH`）、`finish`。步数/时长/token/循环等有护栏参数。
- **DAG 多 Agent**：`POST /agent/dag/run`（显式传 DAG）、`POST /agent/dag/plan-run`（Planner 自动拆 DAG）、二者 `/async` 变体。Kahn 拓扑分层，同层并行、下游看上游结果、末尾综合。可选质量闭环 `AGENT_DAG_REPLAN_ENABLED=true`：每轮综合由 Critic 加权评分，低于 `AGENT_DAG_REPLAN_THRESHOLD`（默认 0.75）则 Replanner 修订后再跑，上限 `AGENT_DAG_REPLAN_MAX_REPLANS`。SSE 除任务状态外还有 `dag-planned`/`dag-level-*`/`dag-worker-*`/`dag-synthesis-*`/`dag-critique`/`dag-replan*` 阶段事件。
- **链式 Agent（chaining）**：`POST /agent/chain`。顺序步骤 + 步间确定性 gate（`gate-min-length`/`gate-must-contain`/`gate-must-match`），不过即短路。`app.agent.chaining.steps` 默认空 —— 需先在配置里定义链，否则端点返回 400。
- **投票 Agent（voting）**：`POST /agent/vote`。同题并行 N 次（`AGENT_VOTING_N`，默认 3）取共识。`AGENT_VOTING_STRATEGY=majority`（默认，确定性多数表决，适合离散/分类题）| `synthesis`（聚合器 LLM 收口，适合自由文本）；`AGENT_VOTING_MIN_AGREEMENT` 阈值。
- **反思 Agent（reflexion）**：`POST /agent/reflexive`、`POST /agent/reflexive/stream`（SSE）。单答案 answer→critique→improve 自省环，评分复用 DAG 的 Critic，加权聚合达 `AGENT_REFLEXION_THRESHOLD`（默认 0.75）或到 `AGENT_REFLEXION_MAX_ATTEMPTS` 即停。

**Agent 任务态**：`GET /agent/tasks`、`GET /agent/tasks/{taskId}`、`GET /agent/tasks/{taskId}/stream`（SSE）、`DELETE /agent/tasks/{taskId}`。`GET /agent/capabilities` 返回能力清单。

**可选 Agent 工具（全部默认关）**：

- `code_exec`（`AGENT_CODE_EXEC_ENABLED=true`）：受限 Java 片段执行。默认 `AGENT_CODE_EXEC_SANDBOX=subprocess`（独立子进程 + `-Xmx`/空 cwd/进程 kill，中等隔离）；仍非强隔离容器，不可信输入慎开。含超时/输出截断/denylist 参数。
- `mcp_call`（`AGENT_MCP_ENABLED=true`）：调外部 MCP server，`AGENT_MCP_TRANSPORT=stdio`（默认）| `http`。
- browser 动作（`AGENT_BROWSER_ENABLED=true`）：`browser_open`/`browser_click`/`browser_click_xy`/`browser_type`/`browser_screenshot`，依赖 Playwright Chromium。
- `browser_see` 视觉动作：**双门控**，需 `AGENT_BROWSER_ENABLED=true` 且 `AGENT_VISION_ENABLED=true`（`VISION_BASE_URL` 指向 vision-service），agent POST 截图字节给 vision-service 看图。

**任务中心迁移**：agent 可选把本地任务生命周期同步到 async-task-service，mirror 模式（`AGENT_ASYNC_EXTERNAL_ENABLED=true`）或 authoritative 模式（再加 `AGENT_ASYNC_EXTERNAL_AUTHORITATIVE=true`，含 worker lease 认领）。终态 webhook 可本地投递或交中心 outbox（`AGENT_ASYNC_EXTERNAL_MIRROR_WEBHOOK`）。

### 4. 异步任务中心

`async-task-service`（`/async/tasks/**`）跨服务通用任务状态中心：

- `POST /async/tasks` 创建、`GET /async/tasks` 列表、`GET /async/tasks/{taskId}` 详情、`PATCH /async/tasks/{taskId}/status` 更新、`DELETE /async/tasks/{taskId}` 取消/删除。
- `GET /async/tasks/{taskId}/stream` SSE，支持 `Last-Event-ID` header 或 `lastEventId` query 断点续订。
- `POST /async/tasks/{taskId}/lease` worker 认领（`PENDING`→`RUNNING`），未过期 lease 只允许 owner worker 更新。
- 终态（`SUCCEEDED`/`FAILED`/`CANCELLED`）webhook 投递（头带 `X-Async-Task-Id`/`X-Async-Task-Status`/`X-Tenant-Id`）。
- 存储 `ASYNC_TASK_STORE=in-memory`（默认）| `jdbc`（MySQL，自动建 `ASYNC_TASK` + `ASYNC_TASK_WEBHOOK_OUTBOX`）。投递耗尽进 `DEAD`，`GET /async/webhook-outbox/dead` 按租户查询。已投递 outbox 默认保留 7 天（`ASYNC_TASK_WEBHOOK_DELIVERED_RETENTION`）。
- kafka 档下终态在 `store.update` 同事务写生命周期 outbox 行，由 relay 投 Kafka（与 workflow 对称，避免两段式丢失）。

### 5. 工作流审批

`workflow-service`（`/workflow/**`）Flowable 退款审批流：

- `POST /workflow/refund/start` 发起、`GET /workflow/tasks` 待办、`POST /workflow/tasks/{taskId}/claim` / `/unclaim` / `/complete`、`GET /workflow/instances/{instanceId}`、`DELETE /workflow/data` 按 chatId 清理。
- 回复生成/工单抽取默认经 HTTP 调 conversation-service（`/conversation/workflow/reply`、`/ticket`），断开本地 ChatModel。
- 终态通知：默认 `WORKFLOW_TERMINAL_NOTIFICATION_MODE` 可切 `async-task`（交中心 outbox，失败回退本地 `WF_OUTBOX`）。kafka 档下 BPMN `end` 监听器在引擎事务内写 `WF_TERMINAL_EVENT_OUTBOX`，由 relay 投 Kafka，保证「终态提交 ⇔ 事件行已写」原子。

### 6. 数据分析（NL2SQL / ChatBI）

`analytics-service`：`POST /chat/sql`（别名 `/analytics/sql`）自然语言转 SQL。内置 SQL guard 和只读连接边界，避免生成写操作直接执行。agent→analytics 已用 protocol DTO（`AnalyticsSqlRequest`/`AnalyticsSqlReply`）typed 契约。

### 7. 渠道接入

`channel-service`（`/channel/**`）：

- `GET /channel/capabilities` 能力、`POST /channel/messages` 出站投递、`POST /channel/inbound` 入站事件。
- callback 转渠道消息：`POST /channel/callbacks`、`POST /channel/callbacks/async-task`、`POST /channel/callbacks/workflow`。
- 出站适配：webhook、飞书 webhook 机器人文本、钉钉机器人、voice HTTP provider。默认 `CHANNEL_OUTBOUND_ENABLED=false`。
- **入站事件桥（飞书 / 钉钉）**（默认关，`FEISHU_*` / `DINGTALK_*` 开）：`POST /channel/feishu/events`、`POST /channel/dingtalk/events` 接收群内 @机器人 消息 → 验签（飞书 SHA-256+AES-256-CBC / 钉钉 HmacSHA256）+ 按 msgId 去重 → 设租户 → 调 conversation `/chat`（可带 RAG）→ 机器人回消息（飞书 im API / 钉钉发消息 API）。**钉钉侧含「知识库无命中 → 转人工 @人工客服」兜底闸门**（作答前先查 `/rag/query` 判命中，命中不足则转人工、不调 LLM）。edge-gateway 白名单放行这两个回调路径（免 api-key，靠渠道签名验真）。详见 `docs/互操作渠道/dingtalk-guide.md`。
- 签名：出站/入站 HMAC 签名校验默认关（`CHANNEL_OUTBOUND_SIGNATURE_ENABLED` / `CHANNEL_INBOUND_SIGNATURE_ENABLED`）。
- 可选 Kafka channel event 发布（`CHANNEL_EVENTS_ENABLED=false`，topic `platform.channel.events`）；作为 platform-eventbus 生命周期事件消费方，去重存储 `CHANNEL_DEDUP_STORE=memory`（默认）| `jdbc`。

### 8. 互操作（真 A2A / MCP）

`interop-service`（`/interop/**`）对外互操作面，内部仍走 typed-HTTP 代理到下游：

- **A2A JSON-RPC**：`POST /interop/a2a` 单端点，真 task 协议，方法 `message/send`、`message/stream`（真 SSE：chat 代理 conversation `/chat/stream` token 流、research 代理 agent 任务流）、`tasks/get`、`tasks/cancel`、`tasks/pushNotificationConfig/set|get`；push 通知按 A2A Task 信封回推客户端（interop 侧中继，HMAC + `X-A2A-Notification-Token`）。`GET /.well-known/agent-card.json` A2A 生态 agent-card（edge-gateway 白名单放行）。
- **agent-card / MCP surface**：`GET /interop/agent-card`、`GET /interop/a2a/agent-card`、`GET /interop/mcp/tools`、`GET /interop/mcp/tools/{toolName}`、`POST /interop/mcp/call`。
- **live discovery**（默认开，`INTEROP_DISCOVERY_ENABLED`）：agent-card skills / MCP tools 从向下游动态发现（懒加载 + TTL `INTEROP_CAPABILITY_TTL`），下游不可达确定性回退静态默认。

### 9. 回归评测

`eval-service`（`/eval/**`）外部回归测试客户端：

- `GET /eval/capabilities`、`POST /eval/run`（单 case/内联 suite）、`POST /eval/suites/{suiteName}/run`（baseline suite）。
- **双跑门禁**：`POST /eval/dual-run`（同一 suite 分别打 oracle 冻结单体与 candidate 网关）、`POST /eval/gate`（纯函数 Gate，回归返回 HTTP 422 供 CI fail）。
- 断言：HTTP status、response contains、oracle contains、JSON path 等值（如 `$.answer`、`$.items[0].name`）、轻量 semantic-tolerance（`semanticExpected` + `semanticMinScore`），输出 JSON report。
- 可选比较模式：LLM-judge（`EVAL_JUDGE_ENABLED=true`）、embedding 相似度（`EVAL_EMBEDDING_ENABLED=true`），默认均开。

### 10. 多模态视觉

`vision-service`（:8090，`/vision/**`）独立多模态服务，**默认开启但须配模型**（`VISION_ENABLED=true`；`VISION_MODEL` 留空则启动 fail-fast，须配一个 LiteLLM `model_list` 里的多模态模型逻辑名，compose 默认 `vision-default`→本机 Ollama qwen2.5vl）：

- `POST /vision/caption`（别名 `/vision/describe`）：JSON（`imageBase64`）或 multipart 图片，返回 caption/描述。多模态 ChatModel 与文本共用同一 LiteLLM base-url，仅逻辑模型名不同（`VISION_MODEL`，如 gpt-4o-mini / qwen2.5-vl）。
- 图片字节上限 `VISION_MAX_IMAGE_BYTES`（默认 10MB）、允许 MIME `VISION_ALLOWED_MIME`、caption 结果按内容 SHA-256 缓存（`VISION_CAPTION_CACHE_SIZE`）。vision token 计入 metering。
- 供 agent-service（`browser_see`）复用。

### 11. 语音闭环（ASR → 对话 → TTS）

`voice-service`（:8091，`/voice/**`）独立语音客服服务，**默认整服务不装配**（`VOICE_ENABLED=false`）。走与 `/chat` 同一套鉴权链（edge-gateway 签发内部 JWT + 多租户），语音转出的文本经 HTTP 调 conversation-service（`VOICE_CONVERSATION_BASE_URL`，默认 `http://localhost:8081`）应答：

- **`POST /voice/chat`**（multipart `audio`[，`chatId`]）：完整轮次 音频 → ASR → `/chat` → TTS，返回 `transcript` + 回复文本 + base64 语音；未传 `chatId` 自动生成 `voice-<uuid>`。
- **`POST /voice/chat/stream`**（multipart，SSE）：半流式 —— 先发 `transcript` 事件，再按句切分逐句 TTS 发 `audio-chunk`（`{text, audioContentType, audioBase64}`），最后 `done`。句最小字数 `VOICE_STREAM_MIN_CHARS`（默认 8）防碎句、省调用。
- **`POST /voice/transcribe`**（multipart `audio`）：仅做 ASR（调试 / 纯转写），返回 `transcript`。
- provider `VOICE_PROVIDER=openai`（OpenAI 兼容协议，`VOICE_BASE_URL` 可指云 OpenAI / Azure / 本地 whisper+tts 网关，`VOICE_API_KEY`）；ASR 模型 `VOICE_ASR_MODEL`（默认 `whisper-1`）、TTS 模型 `VOICE_TTS_MODEL`（默认 `tts-1`）、音色 `VOICE_TTS_VOICE`（默认 `alloy`）、输出格式 `VOICE_TTS_FORMAT`（默认 `mp3`，决定回复 content-type）、ASR 语言提示 `VOICE_LANGUAGE`（留空自动检测）。
- 上传音频上限 `VOICE_MAX_AUDIO_BYTES`（默认 25MB，超限返回 400）+ multipart 上限 `VOICE_MAX_UPLOAD`（默认 25MB）；超时 `VOICE_TIMEOUT_SECONDS`（默认 30）。

### 12. 认证、登录与 RBAC

`auth-service`（:8092，`/auth/**`）自建账号体系，与 api-key 并存的第二鉴权路径：

- **登录/会话**：`POST /auth/login`（账号密码 → `LoginResponse{accessToken, expiresInSeconds, user}` + Set-Cookie 刷新令牌）、`POST /auth/refresh`（用刷新 cookie 一次性轮转）、`POST /auth/logout`（撤销会话，204）、`GET /auth/me`（当前登录用户，需 Bearer）、`GET /auth/public-config`（注册开关 + 口令长度约束，供前端显隐注册入口）、`POST /auth/register`（自助注册，默认关，须同时开 `AUTH_RBAC_ENABLED` 与 `AUTH_REGISTRATION_ENABLED`）。前 5 个 + register 在边缘免鉴权放行，`/auth/me` 需会话 Bearer。
- **会话与内部 JWT 两级 TTL**：会话 accessToken 默认 60min（`SESSION_ACCESS_TTL`）、刷新令牌默认 7d（`SESSION_REFRESH_TTL`，服务端只存 SHA-256 哈希）；边缘换发的内部 JWT 仍是 5min。降权/禁用/删号即时撤销刷新会话，已签发 access 最长延迟一个 access TTL 生效。
- **RBAC 管理面** `/auth/admin/**`（整类 `@ConditionalOnProperty(app.auth.rbac.enabled)`，`role-admin` scope 门禁）：用户 CRUD（`GET/POST /auth/admin/users`、`GET/PATCH/DELETE /auth/admin/users/{username}`、`PUT /auth/admin/users/{username}/roles`）、角色 CRUD（`GET/POST /auth/admin/roles`、`GET/PUT/DELETE /auth/admin/roles/{name}`）。写端点二级门 `admin-writes-enabled`（关→503）+ `If-Match` 乐观锁（缺失 428 / 版本冲突 412）；护栏禁止移除最后一个 role-admin、删被引用角色（409）。列表响应 `X-Total-Count`，单查回 `ETag` 作 If-Match 基线。
- **角色/scope 模型**：种子角色 `viewer[chat]`/`editor[chat,ingest]`/`analyst[chat,analytics]`/`approver[chat,approve]`/`admin[全量 + role-admin + public-ingest]`；有效 scopes = 角色展开 ∪ 直配，签发令牌时展开。`role-admin`/`public-ingest` 只经 admin 角色获得。
- **默认与存储**：application.yml 默认 `AUTH_STORE=in-memory` + RBAC/写全关；compose demo 默认 `jdbc`（表 `USERS`/`USER_ROLE`/`ROLES`/`ROLE_SCOPE`/`AUTH_SESSION`，`CREATE TABLE IF NOT EXISTS` 自建）+ `AUTH_RBAC_ENABLED=true` + 写开放 + `AUTH_RBAC_BOOTSTRAP_ADMIN_USERS=alice`。密码 BCrypt，登录按 username+IP、注册按 IP 节流。生产应分阶段灰度并显式配置 bootstrap-admin 名单。

## 当前限制

- GraphRAG 抽取是确定性三元组格式，不是开放信息抽取。
- A2A `message/stream` 已是真 SSE（chat 代理 conversation token 流、research 代理 agent 任务流）；push 通知按 A2A Task 信封由 interop 中继回推（默认经 agent webhook 触发，零外部依赖；push 配置存储默认内存、多副本需换 Redis/JDBC）。
- 契约测试当前覆盖 knowledge/agent 两个 P0 provider（analytics/async-task 待补），网关 failover 为独立 smoke 脚本、不进默认 CI。
- `code_exec` 子进程沙箱是中等隔离，非强隔离容器；生产对不可信输入应进一步收紧。
- RS256、Config Server git 后端、vision 模型（`VISION_MODEL` 需配）、voice ASR/TTS（需真实 OpenAI 兼容语音端点）、RAG rerank 的 jina 云 reranker、HanLP 分词、Kafka EOS、agent code-exec/browser/MCP 等生产能力默认关闭或需填真实外部配置。
