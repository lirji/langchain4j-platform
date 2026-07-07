# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`AGENTS.md`（中文）和 `README.md`（中文，含每个接口可直接运行的 curl 示例）是本文件的权威补充。`docs/` 目录下有架构、运维、接口速查、开发者指南和迁移路线图。代码里大部分注释/Javadoc 为中文，系统提示词也要求模型用中文回答。

## 常用命令

Maven 多模块，Java 21，Spring Boot 3.3.5。无 Maven wrapper —— 使用系统 `mvn`。未配置任何 linter/格式化/静态分析工具（风格靠约定）。

- 构建全部、跳过测试：`mvn -DskipTests package`
- 运行全部测试：`mvn test`
- 单个模块测试：`mvn -pl knowledge-service test`
- 模块 + 上游依赖（`platform-*` 共享库必须先构建，所以需要 `-am`）：`mvn -pl agent-service -am test`
- 单个测试类 / 方法：`mvn -pl platform-security -Dtest=InternalTokenTest test` / `-Dtest=InternalTokenTest#methodName test`（务必用 `-pl` 限定模块；不带 `-pl` 在 reactor 根跑 `-Dtest` 会对未匹配模块报 "No tests"）
- 不用 Docker 单跑某个服务：`mvn -pl conversation-service spring-boot:run`（:8081）、`mvn -pl edge-gateway spring-boot:run`（:8080）
- 起整套本地栈（LiteLLM + Redis + MySQL + Kafka + 各服务）：`docker compose -f deploy/docker-compose.yml up --build`
- Qdrant RAG 冒烟测试：`bash deploy/smoke-qdrant-rag.sh`

本地运行前置：JDK 21、Maven、Docker、本机 Ollama（`ollama pull llama3.1`），以及一个可达的 LiteLLM（或把 `platform.gateway.base-url` 指向任意 OpenAI 兼容端点）。

## 架构

按 DDD 拆分的全微服务 AI 平台 —— 是原单体（`LangChain4j_project`，冻结在磁盘别处作为行为基准；迁移进度见 `docs/migration-roadmap.md`）的重写目标。根 `pom.xml` 是 `packaging=pom` 聚合器，统一管理所有依赖版本。基础包为 `com.lrj.platform`，每个模块位于 `com.lrj.platform.<module>` 下。

### 两层网关 —— 核心设计思想

1. **`edge-gateway`**（Spring Cloud Gateway / WebFlux，:8080）是唯一对外入口。校验 `X-Api-Key`，签发短时内部 JWT（`X-Internal-Token`），按路径路由到各服务。其 `application.yml` 存放路由表和 api-key→租户 目录。
2. **LiteLLM**（外部，`deploy/litellm/config.yaml`，不是 Java 模块）是 LLM 网关。所有模型调用统一走一个 OpenAI 兼容端点；provider 路由/failover/模型名映射都在 LiteLLM 配置里 —— 这就是 **Java 代码里没有任何 provider `switch` 的原因**。

下游服务（都是 servlet-MVC 的 `@RestController`，各自一个 `@SpringBootApplication`）只信任内部 JWT。租户身份随该 JWT 跨每一次网络跳传播，并在下游还原进 `platform-security` 的 `TenantContext`（一个 ThreadLocal —— 测试里必须在 `@AfterEach` 中 `TenantContext.clear()`）。跨服务调用用装了 `OutboundTenantForwarder` + `OutboundTraceForwarder` 拦截器的 `RestTemplate`（见 `agent-service/.../AgentConfig.java`）。

### 模块

共享库（`platform-*`，无主类；通过 `META-INF/spring/...AutoConfiguration.imports` 自注册）：
- `platform-security` —— `TenantContext`、内部 JWT 签发/校验、入站鉴权 filter、出站传播、限流（`ratelimit/`）
- `platform-gateway-client` —— 唯一的 `ChatModel` bean（`GatewayChatModelFactory`）
- `platform-protocol` —— 跨服务 DTO 契约（不可变 record）；**所有跨服务 DTO 都放这里，不要在各服务里重复定义**
- `platform-observability` —— `TraceIdFilter` + traceId 透传
- `platform-audit`、`platform-metering` —— 审计/token 预算/成本，通过 `ChatModelListener` 挂载

服务（`<name>-service`，端口 8081–8089）：`conversation`（`/chat` + 可选 RAG 增强）、`workflow`（Flowable 退款审批 BPMN 引擎 + outbox）、`analytics`（`/chat/sql` NL2SQL）、`knowledge`（`/rag/**` 混合 RAG + GraphRAG）、`agent`（`/agent/run` ReAct + `/agent/dag/**` 多 Agent DAG）、`async-task`（`/async/tasks/**` 通用任务中心、SSE、webhook outbox）、`channel`（`/channel/**` 出站投递 + callback + Kafka 事件）、`interop`（`/interop/**` A2A + MCP surface）、`eval`（`/eval/**` 回归测试客户端）。

### langchain4j 使用模式

- **全局一个 `ChatModel` bean。** `GatewayChatModelFactory` 构建指向 LiteLLM base-url 的 `OpenAiChatModel`，并注入所有发现的 `ChatModelListener`；`buildDeterministic()` 是给判官/critic 用的 temp=0 变体。
- **声明式 AiServices 是主流写法。** `@AiService` 接口（如 `conversation/.../Assistant.java`）或 `AiServices.builder(...)`（如 `agent/.../AgentBrain.java`、`analytics/.../SqlAssistant.java` 通过 `.tools(...)` 加工具）。为保证可单测，**LLM 始终藏在可 mock 的接口后 —— 想单测的 controller/service 里绝不直接调 `ChatModel`。**
- **横切关注点挂 `ChatModelListener` SPI**（审计、按租户 token 预算、成本）。

### 主导写法：接口 + `@ConditionalOnProperty` 多实现

存储/传输/provider 几乎都是「一个接口 + 内存/Noop 默认实现 + 由环境变量属性开启的 Http/Kafka/Redis/Jdbc 变体」。例如：`AgentAction`（`agent/actions/` 里可插拔的 agent 工具注册表）、`ChannelMessageDispatcher`、`ChannelEventPublisher`、`ImageTextProvider`、`EmbeddingStore`/`EmbeddingModel`（`knowledge/.../KnowledgeEmbeddingConfig.java`）、`GraphStore`、`AsyncTaskStore`、`RateLimiterRegistry`。**默认都是内存/确定性实现，因此本地运行和单测无需任何外部基础设施**（knowledge-service 用确定性的 `HashEmbeddingModel`，不真调用 embedding）。

### 持久化陷阱

没有 JPA/Hibernate/MyBatis，也没有 Flyway/Liquibase。持久化用裸 `JdbcTemplate` 直连 MySQL，而且 **表结构演进靠 `Jdbc*Store` 类里的 `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE ADD COLUMN` 字符串字面量**（如 `async-task-service/.../JdbcAsyncTaskStore.java`）—— 改表要编辑这些代码块，而不是加迁移文件。JDBC 存储是可选开启（`ASYNC_TASK_STORE=jdbc`、`RAG_GRAPH_STORE=jdbc`），默认内存。Flowable 在同一数据源上自行管理其表。

## 测试

JUnit 5 + Mockito + AssertJ，`*Test` 后缀，与被测代码同包。测试几乎全是纯 POJO 单测：直接 new controller/service 并注入 mock —— 为了速度刻意不用 Spring context 和 `@SpringBootTest`。依赖 DB 的测试用内存 **H2**（无 Testcontainers）。新增功能要为租户/安全上下文传播、异步任务状态流转、RAG 排序、工作流状态流转补聚焦测试。配置项：`INTERNAL_JWT_SECRET` 必须 ≥32 字节。

## 非显而易见的坑

- 行为高度 feature-flag 化且默认关闭（RAG 增强、向量库、GraphRAG、JDBC 持久化、agent 动作、DAG 重规划）。README 里为每个环境变量都给了示例 curl。
- `code_exec` agent 动作是同 JVM 的 JShell —— 只做 denylist + 超时 + 输出截断，**不是真正的沙箱**；对不可信输入务必保持关闭。
- Playwright 浏览器动作首次需在联网环境装二进制：`mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"`。
- 提交遵循带模块 scope 的 Conventional Commits，如 `feat(knowledge): ...`。
