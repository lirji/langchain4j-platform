# Project Capability Report

- workspace: `/Users/liruijun/personal/LLM/langchain4j-platform`
- generated_at: 2026-09-16
- protocol: project-capability-report/v1
- source_of_truth: 源码 / yml / SQL / CI（README 仅线索）
- 配套深度分析: `PROJECT_DEEP_ANALYSIS.md`、`ARCHITECTURE.md`、`BUSINESS_FLOWS.md`、`EVIDENCE_INDEX.md`
- 能力状态：已实现 / 可选开关 / 规划中 / 推测

本文件是 2026-09-16 能力探索对话的落盘件。分析会话未跑 `mvn test`（`UNVERIFIED`）。

---

## 1. Project Executive Summary

**langchain4j-platform** 是企业多租户 AI 能力平台：对话、RAG、Agent、NL2SQL、退款审批、渠道、A2A/MCP 拆成 Spring Boot 微服务，统一经 `edge-gateway` 入站，统一经 LiteLLM 调模型。

| 维度 | 判断 |
|---|---|
| 规模 | 中大型 Brownfield：25 个 Maven 模块、约 44 个 Controller、约 281 个 `*Test.java` |
| 难度 | 多租户 + 多数据面 + LLM 不确定性 + 跨服务最终一致 |
| Agent 默认 | compose 打仓外 AgentScope；本仓 Java `agent-service` 为回滚 |

---

## 2. Project Purpose

给企业内部与演示控制台提供可鉴权、可观测、可限流的 AI 能力。原单体冻结为行为基准。

**Users**：前端操作员、内部服务、可选 IM、外部 A2A/MCP。  
**Core objects**：Tenant/InternalToken、Document+version、IngestionJob、AgentRun、AsyncTask、Flowable 实例、Order、发票批次。

---

## 3. Repository & Module Map

Java 21 / Spring Boot 3.3.5 / Spring Cloud 2023.0.3 / LangChain4j 1.13.1。无 Maven wrapper。

共享库：`platform-security`、`observability`、`gateway-client`、`protocol`、`audit`、`metering`、`eventbus`、`database-migrations`。

服务端口（yml）：edge 8080、conversation 8081、workflow 8082、analytics 8083、knowledge 8084、agent 8085、async-task 8086、channel 8087、interop 8088、eval 8089、vision 8090、voice 8091、auth 8092、order 8093、tax 8094、config-server 8888。

运行：`mvn -pl <svc> spring-boot:run`；`docker compose -f deploy/docker-compose.yml up --build`；Helm `deploy/helm/platform/`。

---

## 4. Business Capability Map

- **对话**：`/chat` `/chat/stream` `/chat/auto` `/chat/vision` `/chat/cascade` `/chat/memory` `/extract`；MCP 对话默认关。
- **知识**：混排检索、同步/异步入库、GraphRAG、分享（enforce）、Obsidian；CLIP 默认关。
- **Agent**：生产 AgentScope；Java ReAct/DAG/chain/vote/reflexion/process/analyst 回滚；code_exec/mcp/browser 默认关。
- **审批/任务**：Flowable 退款；async-task 状态机+SSE。
- **分析/财税/订单**：NL2SQL+SqlGuard；税票规则权威；订单只读。
- **渠道/互操作/评测**：出站与 IM 默认关；A2A 真 SSE；eval HTTP 回归。
- **登录 RBAC**：管理面默认关。

---

## 5. Platform Capability Map

边缘 JWT 换发、TenantContext、出站传播、统一 ChatModel+Listener、审计、token 事后计量、trace、edge 限流、可选事件总线、任务中心、可选 ReBAC、自研 HTTP 熔断与 deadline、可选 config-server、前端能力目录。

主导写法：接口 + `@ConditionalOnProperty` 多实现（内存/Noop 默认）。

---

## 6. Technical Capability Map

| 能力 | 真实性 |
|---|---|
| 内部 JWT / 多租户 / LiteLLM ChatModel / 混排 RAG / 入库 lease / 版本可见性 / WF 幂等+HTTP Outbox | CRITICAL |
| Redis registry/缓存/限流/预算；自研熔断；SqlGuard；ReAct 停止条件 | USED 或 CRITICAL |
| Kafka 终态、ReBAC、code_exec | IMPLEMENTED，默认关或可选 |
| Resilience4j | 未使用 |
| TokenBudget 预检 Filter | DECLARED（Javadoc），无源文件 |

---

## 7. Enterprise Capability Matrix

超时/部分重试/自研熔断/edge 限流/bulkhead 有。一致性以本地事务+Outbox 为主，无 2PC。并发靠 DB unique+lease。幂等覆盖审批启动与部分作业。扩展靠 SPI。观测有 log/trace/audit/health。安全：AuthN 强、文档授权默认弱。预算不 fail-closed。

---

## 8. Top Core Business Flows

| 链路 | 入口 | DB | Redis | MQ | 难度 |
|---|---|---|---|---|---|
| 鉴权换发 | 任意 API | 否 | 限流 | 否 | A |
| RAG 对话 | POST /chat | 否 | 语义缓存 | 否 | S |
| 混排检索 | POST /rag/query | graph | registry | 否 | S |
| 异步入库 | POST /rag/ingestions | job 表 | registry | 否 | S |
| 退款审批 | POST /workflow/refund/start | Flowable+WF_* | 否 | 可选 | S |
| Agent | /agent/** | 视动作 | 否 | 否 | S |
| NL2SQL | POST /chat/sql | nl2sql_demo | 否 | 否 | A |
| 异步任务 | /async/tasks | ASYNC_TASK* | 否 | 可选 | A |
| A2A 流式 | /interop/a2a | 可选 | 可选 | 否 | A |
| 发票审查 | POST /tax/invoices/review | 无 | 否 | 否 | A |

时序细节见 `BUSINESS_FLOWS.md`。

---

## 9. Architecture

微服务 + 共享内核 + 双网关。数据按上下文拆库。同步 HTTP 为主。图见 `ARCHITECTURE.md`。

默认 `/agent/**` → AgentScope。Java 注释与 yml 默认值经常相反。

---

## 10. Key Technical Implementations

1. 双网关；ChatModel 禁止 virtual-key 回退 master key  
2. 内部令牌 iss/aud/kid/token_use/TTL  
3. RRF 融合 + shared AND  
4. REGISTRY.commitVersion 可见性门闩  
5. Outbox DB claim 多实例  
6. SqlGuard + 只读账号  
7. 税票规则权威  
8. 前端 catalog，不写死业务表  

---

## 11. Project Challenges

1. 多源 RAG 分数与三套可见性（S）  
2. 多 sink 入库部分成功（S）  
3. 审批通知 at-least-once（S）  
4. 租户跨 JVM + 虚拟线程传递（A/S）  
5. LLM 进入审批/Agent/SQL（S）  
6. 开关矩阵与文档漂移（A）  

---

## 12. Design Highlights

单一 ChatModel+Listener；条件实现；内部 JWT；知识 runtime role 拆分；规则权威 AI 叙事；高风险动作默认关；Flyway 与进程分离；自研 origin 熔断。

---

## 13. Risks

- **P0** HS256 默认 secret 可伪造租户；ReBAC 默认关 + public KB 默认开  
- **P1** 预算不拦截；同步 RAG+LLM 扇出；shadow fail-open；Casdoor Java 默认 vs yml  
- **P2** 语义缓存 HVALS；内存关键词不持久；渠道签名默认关（入站亦默认关）  
- **P3** 注释与默认值漂移  

10 倍：LiteLLM → RestTemplate → MySQL 热点行 → Redis 大 Hash → ES/Qdrant。

---

## 14. Technical Debt

| 类 | 项 |
|---|---|
| Architectural | Agent 双栈；服务多、同步扇出 |
| Code | Javadoc「默认关」；TokenBudgetGuard 有名无类 |
| Test | 少 Spring 上下文；CI skipITs |
| Data | ingest GC 默认关；graph 缺 docId |
| Operational | dev secret 进仓；开关爆炸 |

---

## 15. Top Modules Worth Deep Diving

1. knowledge-service  
2. platform-security + edge-gateway  
3. workflow-service  
4. conversation-service  
5. platform-gateway-client + metering  

---

## 16. Final Project Capability Map

```text
langchain4j-platform
├── 业务能力：对话 / RAG / Agent / 退款 / NL2SQL / 订单 / 税票 / 渠道 / A2A
├── 平台能力：换发 JWT / 租户 / 审计计量 / 限流 / 任务中心 / 可选 ReBAC
├── 技术能力：Redis 非账本 / 可选 Kafka / Outbox lease / 自研熔断 / RRF / 开关
└── 工程能力：POJO 单测 / Flyway 分离 / Compose+Helm / SBOM
```

### 技术画像

架构复杂度高；业务复杂度中高；分布式程度高（同步为主）；一致性要求审批/入库高、对话可 fail-open；工程成熟度中高；测试成熟度中。

### 可选附录

简历/面试口径见 `PROJECT_DEEP_ANALYSIS.md` 第 20–21 节。
