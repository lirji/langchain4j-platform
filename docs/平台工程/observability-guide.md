# 可观测性指南（Observability）

本指南面向要在平台上做**日志排障、分布式追踪、指标监控与成本/配额观测**的开发者与运维。
可观测性是横切能力，由共享库 `platform-observability`（跨服务 traceId + OpenTelemetry GenAI 追踪 + 健康探测）
与 `platform-metering`（token 预算 / 成本 Actuator 端点）提供，随各 LLM 服务自动装配，**无需改任何服务代码**。

平台把「一次请求发生了什么」拆成三条**正交**的观测线，各看一个侧面、互不依赖、可分别开关：

| 观测线 | 由谁提供 | 看什么 | 默认状态 |
| --- | --- | --- | --- |
| **跨服务 traceId** | `platform-observability`：`TraceIdFilter` + `OutboundTraceForwarder` | 一条调用链在多服务日志里用同一个 id 串起来 | **默认开**（servlet 服务自动挂 filter） |
| **OpenTelemetry GenAI span** | `platform-observability`：`otel/OtelChatModelListener` + `OtelTracingAutoConfiguration`（配合 `platform-gateway-client` 的 `TracingDefaultsEnvironmentPostProcessor`） | 每次 LLM 调用的 span 树、耗时分解、token、finish_reason、租户归属 | **默认关**（`management.tracing.enabled=false` 兜底） |
| **指标 + Actuator** | Spring Boot Actuator + Micrometer（`platform-metering` 的 `tokenbudget`/`cost` 端点、`knowledge` 的 `ChunkMetrics`、`workflow` 的 `WorkflowMetrics`、`cost` 的 `gen_ai.client.cost.usd`） | 聚合趋势：请求量、成本、切分质量、工作流；租户 token/成本快照；健康 | **默认开**（health/info/prometheus 全服务；tokenbudget/cost 按服务声明） |

> 阅读约定：
> - **业务接口**统一经边缘网关 `http://localhost:8080` + `-H 'X-Api-Key: dev-key-acme'`（网关校验 key → 签发内部 JWT → 路由下游）。
> - **Actuator 属于运维面，既不经边缘网关、也不在业务端口上**——每个 Java 服务把 actuator 搬到了独立的 **management 端口 = 业务端口 + 1000**（`MANAGEMENT_PORT`）。运维面直连该端口（内网/本地）：edge-gateway `:9080`、conversation `:9081`、workflow `:9082`、analytics `:9083`、knowledge `:9084`、agent `:9085`、async-task `:9086`、channel `:9087`、interop `:9088`、eval `:9089`、vision `:9090`、voice `:9091`、auth `:9092`、order `:9093`、tax `:9094`、config-server `:9888`。**AgentScope 编排运行时是例外**：它是单端口应用，`/metrics`、`/health`、`/readiness` 都在业务端口 `:8085`。
> - **为什么要拆端口**：业务端口上挂着内部 JWT 校验 filter，而内部 JWT 只活 5 分钟——Prometheus 和 K8s probe 拿不到能长期使用的静态凭据，抓取和探活就必然失败。Spring Boot 的 management 端口跑在独立子上下文里，天然不经过父上下文的租户 filter，因此运维面免鉴权、业务面鉴权不变（第 3.3 节有验证）。该端口**只在内网/集群内暴露，不要发布到公网**。
> - 三条线**默认都无需任何外部基础设施**：traceId 纯内存 MDC；OTel 关着且开着也只走 OTLP HTTP，不引 gRPC；指标在进程内 Micrometer registry。

---

## 1. 跨服务 traceId（日志关联）

微服务下一次外部请求会穿过多个服务（如 channel → conversation → knowledge），要在各服务的日志里把它串起来，
就需要一个贯穿全链路的 id。`platform-observability` 用「一个入站 filter + 一个出站拦截器」实现了这套**最小分布式追踪**——
不依赖任何 collector，只要看日志就够用。

### 1.1 工作原理

- **入站**：`TraceIdFilter`（`OncePerRequestFilter`，注册在 `HIGHEST_PRECEDENCE + 10`、拦 `/*`）读请求头 `X-Trace-Id`：
  - 有值 → **复用**（说明是上游服务转发进来的，同一条链路共享同一 id）；
  - 无值 → 生成一个 8 位短 UUID。
  - 随后把它放进 SLF4J `MDC`（key = `traceId`），并**回写到响应头 `X-Trace-Id`**；请求结束 `finally` 里清掉 MDC。
- **出站**：`OutboundTraceForwarder`（`ClientHttpRequestInterceptor`）从 MDC 取出当前 `traceId`，塞进对下游发起的 `X-Trace-Id` 请求头。
  它作为 bean 由 `PlatformObservabilityAutoConfiguration` 常驻，但**要生效需挂到服务间的 `RestTemplate`/`RestClient` 上**——各服务的 `*Config`（如 `agent-service` 的 `AgentConfig`、`conversation` 的 `ConversationRagConfig`、`channel` 的 `HttpConversationClient` 等）已把它和 `OutboundTenantForwarder` 一起装进拦截器链。
- **日志里显示**：在各服务 `logback`/`application.yml` 的日志 pattern 里引用 `%X{traceId}` 即可让每行日志带上它。

只要每一跳都装了这两件，`X-Trace-Id` 就沿调用链自然传播，一条链路全程一个 id。

### 1.2 与边缘网关的关系（重要区别于单体）

`TraceIdFilter` 是 **servlet-only**（`@ConditionalOnWebApplication(SERVLET)`）。`edge-gateway` 是 Spring Cloud Gateway / WebFlux，
**不跑这个 filter，也不主动打 traceId**——它只负责 api-key→内部 JWT 与路由。因此：

> traceId 由**第一个收到请求的下游 servlet 服务**（网关路由到的那个）在 `TraceIdFilter` 里铸造，之后经 `OutboundTraceForwarder` 一路透传给更下游。
> 若你希望链路从更外层就带上同一 id，客户端可在请求里自带 `X-Trace-Id`，`TraceIdFilter` 会复用它。

### 1.3 curl 验证

业务请求经网关打到 conversation，观察响应头里的 `X-Trace-Id`（`-i` 看响应头）：

```bash
curl -i -X POST 'http://localhost:8080/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"用三句话介绍平台"}'
# 响应头会出现：X-Trace-Id: 1a2b3c4d
```

自带 traceId（便于把你侧的日志和平台日志对齐），会被原样复用并回写：

```bash
curl -i -X POST 'http://localhost:8080/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' -H 'X-Trace-Id: my-req-0001' \
  -H 'Content-Type: application/json' -d '{"message":"hi"}'
# X-Trace-Id: my-req-0001（跨服务日志都用它）
```

---

## 2. OpenTelemetry GenAI 分布式追踪（span 树）

把一次 chat 请求记成一棵 **CLIENT span**（遵循 OpenTelemetry **GenAI 语义约定**），与第 3 节的聚合指标**正交**：
一个出聚合趋势看整体，一个出 span 看**单次 LLM 调用的调用链与耗时/token 分解**。多 Agent DAG、reflexion、voting 等 fan-out
场景下，每次 LLM 子调用各出一条 span，配合第 1 节的 `traceId`（MDC）可把日志与 span 串起来看。

**默认关**，零 gRPC 依赖冲突（走 OTLP HTTP）。

### 2.1 组成（`platform-observability/otel` + `platform-gateway-client`）

| 类 | 作用 |
| --- | --- |
| `OtelChatModelListener` | 实现 langchain4j `ChatModelListener`：`onRequest` 起 CLIENT span、`onResponse`/`onError` 收 span 并写 `gen_ai.*` 属性。作为 bean 由 `GatewayChatModelFactory` 收进 `List<ChatModelListener>`，与 audit / metering 一同挂载，**无需改网关工厂**。 |
| `OtelTracingAutoConfiguration` | 仅当 classpath 同时有 `io.micrometer.tracing.Tracer` 与 langchain4j `ChatModelListener` 时装配该 listener bean（即依赖 `platform-gateway-client` 的 6 个服务：**conversation、agent、analytics、eval、vision、workflow**；`knowledge`/`interop`/`voice`（不引 gateway-client）与 `edge-gateway`/`config-server`（无这些类）经 `@ConditionalOnClass` 整体跳过、零负担）。 |
| `TracingDefaultsEnvironmentPostProcessor`（在 `platform-gateway-client`） | 以**最低优先级**注入 `management.tracing.enabled=false` 兜底——因为 gateway-client 带来了 `micrometer-tracing-bridge-otel` + OTLP exporter，Spring Boot 默认会自动装配 tracing，这里把它默认关掉、零回归；任何 yml/env/命令行显式设置都能覆盖它。 |

> **关键实现**：listener 通过 `Supplier<Tracer>`（`ObjectProvider::getIfAvailable`）**惰性解析** Tracer。未开 tracing 时无 `Tracer` bean、`supplier` 返回 `null`、listener 全程 no-op、零开销、启动不失败；开启后拿到 Boot 装配的 OTel-backed Tracer，span 经 OTLP 导出。

### 2.2 span 属性（GenAI 语义约定）

CLIENT span，名 `chat {model}`，含：

| 属性 | 说明 |
| --- | --- |
| `gen_ai.operation.name` | 固定 `chat` |
| `gen_ai.system` | provider 规范化小写：`OPEN_AI`→`openai`、`ANTHROPIC`→`anthropic`、`GOOGLE_*_GEMINI`→`gemini`、`MISTRAL_AI`→`mistral_ai`、`AMAZON_BEDROCK`→`aws.bedrock`、`OLLAMA`→`ollama`，其余小写 |
| `gen_ai.request.model` / `gen_ai.response.model` | 请求/响应模型名 |
| `gen_ai.request.messages` | 请求消息条数 |
| `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens` | token 用量 |
| `gen_ai.response.finish_reasons` | 结束原因 |
| `gen_ai.client.duration_ms` | 调用时长（span 起止时间也隐含时长，这里额外写进属性便于直接看） |
| `tenant.id` / `enduser.id` | 复用 `platform-security` 的 `TenantContext`（`onRequest` 跑在业务线程、ThreadLocal 还在），租户归属 |
| 出错 | `error.type`（异常类名）+ `span.error(...)`，span status = ERROR |

### 2.3 怎么开（真实配置键 —— 已迁到 Spring Boot 原生 tracing）

**这是相对单体最大的变化**：单体用自研的 `app.observability.otel.*`（enabled/endpoint/service-name/sampler…）。
新平台**删掉了那套自定义配置**，直接复用 **Spring Boot 原生分布式追踪**（`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`）。
开启只需设两个标准 Boot 属性（env 用 relaxed-binding 大写下划线形式）：

| 配置属性 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `management.tracing.enabled` | `MANAGEMENT_TRACING_ENABLED` | `false`（`TracingDefaultsEnvironmentPostProcessor` 兜底） | **总开关**。设 `true` 才会有 `Tracer` bean、listener 才真正出 span |
| `management.otlp.tracing.endpoint` | `MANAGEMENT_OTLP_TRACING_ENDPOINT` | 不设时 Boot 默认走本机 `http://localhost:4318/v1/traces` | OTLP **HTTP**（HTTP/protobuf）collector 端点，指向你的 collector |
| `management.tracing.sampling.probability` | `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | `0.1`（Spring Boot 默认） | 采样比例 0.0–1.0；本地调试常设 `1.0` 全采 |

> 以上是**源码/框架真实键**：`TracingDefaultsEnvironmentPostProcessor` 与 `OtelChatModelListener` 的 Javadoc 显式点名 `management.tracing.enabled` 与 `management.otlp.tracing.endpoint`；`sampling.probability` 是 Spring Boot micrometer-tracing 的标准键。**平台没有自定义任何 OTLP 环境变量**——不要照抄单体的 `app.observability.otel.*`。

### 2.4 跑一遍（Jaeger 自带 OTLP HTTP 4318 + UI 16686）

```bash
# 1) 起一个 collector（Jaeger all-in-one）
docker run -d -p 4318:4318 -p 16686:16686 jaegertracing/all-in-one:1.57

# 2) 开开关起某个 LLM 服务（以 conversation 为例），指向 collector、本地全采
MANAGEMENT_TRACING_ENABLED=true \
MANAGEMENT_OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces \
MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0 \
  mvn -pl conversation-service spring-boot:run

# 3) 发一次请求（经网关）
curl -X POST 'http://localhost:8080/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"用三句话介绍平台"}'
```

打开 Jaeger UI（`http://localhost:16686`），能看到 `chat <model>` 的 CLIENT span 及其 GenAI 属性。
`docker compose` 整栈起时，给需要追踪的服务在 compose 的 `environment` 里加上这几个变量即可。

### 2.5 设计取舍（沿袭单体的洞察）

- **为什么 no-op 兜底**：listener 要能无条件加入 `List<ChatModelListener>`，就不能自己判开关。惰性 `Supplier<Tracer>` 让「关着 = 无 Tracer bean = span 空操作」，启动不失败、零开销。
- **只观测、不注册第二个 ChatModel**：listener 只挂监听，遵守仓库「全局只有一个 `ChatModel` bean」的约束。
- **OTLP HTTP 而非 gRPC**：走 OkHttp/JDK、不引 gRPC，避开 gRPC 版本冲突（与单体同样理由）。
- **与指标正交**：span 出调用链细节；聚合趋势看第 3 节的 Micrometer 指标——两者可同时开。
- **迁移变化**：单体自研 `OtelTracingConfig`/`OtelTracingProperties`（本地 OTel SDK + 自定义 sampler 字符串）→ 新平台改用 micrometer-tracing 抽象（span API 从原生 OTel 换成 `io.micrometer.tracing.Span`），采样/导出交给 Boot 自动装配，配置面收敛到标准 `management.*` 键。
- **测试**：`OtelChatModelListenerTest` 用 micrometer-tracing 的 `SimpleTracer` 做确定性断言（不连模型/collector/网络）：一次 response 导出一条带齐全 GenAI tag 的 CLIENT span；error 路径导出 ERROR span 带 `error.type`；no-op tracer 路径不导出任何 span。

---

## 3. 指标与 Prometheus

### 3.1 各服务暴露了什么（Actuator exposure）

每个服务独立暴露自己的 Actuator（单体是一个进程一套端点，微服务是**每服务一套**、各自一个 management 端口）。
**`health,info,prometheus` 是全服务基线**——16 个常驻 Java 服务无一例外，否则同一套抓取配置会漏掉一部分进程。
在此之上按能力追加端点：

| 追加端点 | 服务 | 说明 |
| --- | --- | --- |
| `tokenbudget,cost` | conversation `:9081`、agent `:9085`、vision `:9090` | 引了 `platform-metering` 的 LLM 服务，见 3.4 |
| `gateway` | edge-gateway `:9080` | Spring Cloud Gateway 自带的路由端点 |
| （仅基线） | workflow `:9082`、analytics `:9083`、knowledge `:9084`、async-task `:9086`、channel `:9087`、interop `:9088`、eval `:9089`、voice `:9091`、auth `:9092`、order `:9093`、tax `:9094`、config-server `:9888` | — |

> 这条基线由 `deploy/test-observability-config.sh` 静态守住（CI 的 supply-chain workflow 里跑）：任一服务少了
> `prometheus`、少了 management 端口、management 端口不等于业务端口 +1000，或没进抓取配置，门禁直接失败。
> 加新服务时照抄任一现有 `application.yml` 的 `management` 块即可。

### 3.2 平台实际发出的 Micrometer 指标

除 Spring Boot 默认的 JVM/HTTP/系统指标外，平台自定义的业务指标（Prometheus 抓取时 `.`→`_`、counter 尾缀 `_total`）：

| Micrometer 名 | 类型 | tags | 出处 / 含义 |
| --- | --- | --- | --- |
| `gen_ai.client.cost.usd` | counter | `model`,`provider` | `platform-metering` 的 `CostChatModelListener`：每次 LLM 调用累加成本 USD（成本归因开启时，见 [cost-attribution.md](cost-attribution.md)） |
| `llm.cascade` | counter | `served`（`cheap`/`strong`） | conversation 模型级联：量化「省下多少次强模型调用」（`app.chat.cascade.enabled=true` 时） |
| `rag.chunk.size` | DistributionSummary | `strategy` | knowledge `ChunkMetrics`：每个 chunk 的字符长度（自动出 `_count`/`_sum`/`_max`，均值=sum/count） |
| `rag.chunk.total` / `rag.chunk.tiny` / `rag.chunk.oversize` | counter | `strategy` | 入库 chunk 总数 / 碎块数 / 超大块数（换切分策略后切分形态可观测，详见 [rag-guide.md](../对话与检索/rag-guide.md)） |
| `rag.ingest.documents` | counter | `strategy` | 入库文档数 |
| `workflow.tasks.pending` | gauge | — | workflow 待审批任务数 |
| `workflow.started` / `workflow.completed` / `workflow.approval.timeout` | counter | `priority`/`outcome` 等 | 退款审批流启动/完成/超时数 |
| `workflow.approval.duration` | timer | — | 审批耗时分布 |
| `async_task_backlog` / `async_task_inflight` | gauge | — | async-task 中心队列的 PENDING / RUNNING 任务数（读的是持久化存储，各副本看同一份） |
| `async_task_event_append_total` | counter | `event`,`duplicate` | 任务事件追加数（`duplicate` 区分幂等重放） |
| `async_task_orphan_failed_total` | counter | `kind` | 租约过期被孤儿回收判失败的任务数 |
| `knowledge.ingestion.jobs` | counter | — | 入库任务数 |
| `knowledge.ingestion.oldest.pending.seconds` | gauge | — | 最老待处理入库任务的等待秒数（队列老化，比 backlog 数量更能反映卡住） |
| `knowledge.ingestion.stage.duration` / `knowledge.ingestion.commit.latency` | timer | 阶段 | 入库各阶段耗时 / 提交延迟 |
| `knowledge.ingestion.failures` | counter | — | 入库失败数 |
| `knowledge.authz.decisions` / `knowledge.authz.candidates` / `knowledge.authz.allowed_docs` / `knowledge.authz.underfill` | counter / summary | 决策维度 | 文档级 ReBAC 判权结果、候选集与过滤后不足量（`RAG_AUTHZ_MODE` 非 disabled 时有数） |
| `knowledge.authz.check_bulk.latency` | timer | — | 外部 auth-platform `checkBulk` 延迟 |
| `conversation.shadow.requests` / `conversation.shadow.latency` | counter / timer | — | 影子流量对比（灰度切换期） |

**AgentScope 编排运行时（Python，OTel → 手写 Prometheus 渲染）**。渲染器给单调 counter 补 `_total`、不追加 unit 后缀，
所以下面的名字加 `_total` 就是抓取后的真实序列名：

| OTel 名 | 类型 | attributes | 含义 |
| --- | --- | --- | --- |
| `agent_async_task_submissions` / `agent_async_task_completions` | counter | `kind`（+`status`） | Agent 异步任务受理数 / 终态完成数 |
| `agent_async_task_heartbeat_failures` | counter | — | 租约心跳失败数（心跳断了租约会过期，任务可能被重复领取） |
| `agent_async_task_running` / `agent_async_task_inflight` / `agent_async_task_backlog` | up-down counter | `kind` | **本进程内**执行中 / 已受理 / 等执行槽的任务数（与 Java 侧 `async_task_backlog` 不同：那是中心队列，这是单进程视角） |
| `agent_tool_policy_denials` | counter | `tool`,`reason` | 受治理工具在执行前被策略拒绝数 |
| `agent_tool_provider_failures` | counter | `tool`,`provider` | 受治理工具的下游 provider 调用失败数 |

> **相对单体的重要差异（诚实提示）**：单体有自研 `MetricsChatModelListener` 发 `gen_ai_client_requests_total` / `_operation_duration_seconds` / `_token_usage_total` / `_errors_total` 四个聚合指标。新平台**没有**这套 Micrometer 聚合计数器——这些「每调用」信号改由**第 2 节的 OTel span** 承载（耗时/token/finish_reason/error 都在 span 上），token 用量另由 `/actuator/tokenbudget` 快照（3.4 节）、成本由 `gen_ai.client.cost.usd` counter 承载。

### 3.3 抓取（Prometheus scrape）

`/actuator/prometheus` 这个端点只有在 `micrometer-registry-prometheus` 在 classpath 上时才真的存在——光在 exposure 里
写 `prometheus` 会得到 404。这个 registry 现在由 **`platform-observability` 统一带入**（`runtime` 且**不加 `optional`**，
否则依赖不传递、接入方仍然拿不到端点）；`edge-gateway` 与 `config-server` 不依赖该共享库，因此在自己的 pom 里直接声明。

抓取与告警配置在仓库里，**不用自己拼**：

| 文件 | 内容 |
| --- | --- |
| `deploy/prometheus/prometheus.yml` | 14 个默认拓扑 Java 服务（management 端口 + `/actuator/prometheus`）+ AgentScope（`:8085/metrics`）。每个 target 带 `service` 标签，告警文案直接引用它。`agent-service`（`legacy-agent` profile）与 `eval-service`（`evaluation` profile）默认不启动，故意不在列表里——列进去只会制造长期 `up==0` 噪声 |
| `deploy/prometheus/alerts.yml` | 8 条规则，见 3.5 |

起 Prometheus（`observability` profile，不影响默认栈）：

```bash
docker compose -f deploy/docker-compose.yml --profile observability up -d prometheus
# UI: http://localhost:19090（PROMETHEUS_HOST_PORT 可改）→ Status / Targets 应全绿；Alerts 里 8 条规则均为 inactive
```

直接看某个服务的原始指标（走 management 端口、**不需要任何凭据**）：

```bash
curl -s http://localhost:9084/actuator/prometheus | grep '^rag_chunk'   # knowledge 的切分质量
curl -s http://localhost:8085/metrics | grep '^agent_tool'             # AgentScope（单端口，业务端口即运维端口）
```

业务端口上则**取不到**它——请求会被内部 JWT filter 拦在路由之前，返回 401 而不是 404
（`AsyncTaskManagementPortTest` 对这条边界做了断言：management 端口免鉴权可取指标与探针，业务端口 401，业务接口仍要凭据）：

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8084/actuator/prometheus   # 401
```

K8s 侧由 Helm 渲染：`platform-lib` 给每个 Spring 服务额外开一个名为 `mgmt` 的容器端口与 Service 端口，探针也打在 `mgmt` 上；
`agentscope-orchestrator` 在 `values.yaml` 里显式 `managementPort: 0`（单端口应用），此时不渲染 `mgmt` 端口、探针回落到 `http`。

示例 PromQL（切分质量，沿用单体洞察）：

```promql
# chunk 平均长度（按策略）
rate(rag_chunk_size_sum[1h]) / rate(rag_chunk_size_count[1h])

# 碎块比例 —— 偏高说明切太碎、单句成块污染检索
sum(rate(rag_chunk_tiny_total[1h])) by (strategy)
  / sum(rate(rag_chunk_total_total[1h])) by (strategy)

# 24h 累计成本（成本核算）
increase(gen_ai_client_cost_usd_total[24h])

# 强模型被节省的比例（cascade）
sum(rate(llm_cascade_total{served="cheap"}[1h]))
  / sum(rate(llm_cascade_total[1h]))
```

### 3.4 token 预算 / 成本 Actuator（`platform-metering`）

`platform-metering` 通过 `ChatModelListener` 记账，并暴露两个自定义 Actuator 端点（`GET`），返回**按租户**的当日快照。
conversation/agent/vision 三个服务已在 exposure 里带上它们：

| 端点 | 端点 id | 返回（每 tenant） | 相关开关 |
| --- | --- | --- | --- |
| `GET /actuator/tokenbudget` | `tokenbudget` | `{used, budget, day}`（当日 token 用量/配额） | token 预算默认**开**；计数默认落 `redis`（`TOKEN_BUDGET_STORE`，无 redis 设 `in-memory`） |
| `GET /actuator/cost` | `cost` | `{usd, currency, day}`（当日累计成本） | 成本归因默认**关**；开启后 `COST_STORE` 默认 `redis`。详见 [cost-attribution.md](cost-attribution.md) |

直连 management 端口访问（不经网关、不需凭据）：

```bash
curl -s http://localhost:9081/actuator/tokenbudget   # conversation：各租户当日 token 用量
curl -s http://localhost:9085/actuator/cost          # agent：各租户当日成本 USD
```

> 这两个端点按租户返回快照，**不要暴露到公网**——management 端口整体只在内网/集群内开放。

响应示例（map：tenantId → 快照）：

```json
{ "acme": { "used": 12840, "budget": 200000, "day": "2026-07-09" } }
```

---

## 3.5 关键告警（`deploy/prometheus/alerts.yml`）

规则只引用代码里确实注册过的序列（3.2 节两张表），**不引用臆想的指标**。阈值是异常信号的起点，
**不是经过验证的 SLO**——本仓没有正式性能验收目标，所以刻意不设延迟/TP99 门限，上线前应按真实容量与值班能力重定：

| 告警 | 触发 | 级别 | 首查 |
| --- | --- | --- | --- |
| `PlatformServiceDown` | `up == 0` 持续 2m | critical | 容器状态 + `/actuator/health/readiness` |
| `PlatformServerErrorRatioHigh` | 5xx 比例 > 5% 持续 10m（分母 > 0 保护，空闲期不除零误报） | warning | 是单接口回归还是下游依赖故障 |
| `AsyncTaskBacklogNotDraining` | `async_task_backlog` 15m 内一次都没归零 | warning | `GET /async/drain-inventory` 看存量按 kind + 状态 + 租约持有者的归属 |
| `AsyncTaskOrphansReaped` | `async_task_orphan_failed_total` 有增长 | warning | worker 是否重启 / 租约续约失败 |
| `AgentAsyncTaskHeartbeatFailing` | `agent_async_task_heartbeat_failures_total` 有增长 | warning | 心跳断→租约过期→任务可能被重复领取，先查 async-task-service 连通性 |
| `AgentToolProviderFailing` | `agent_tool_provider_failures_total` 持续增长 5m | warning | 下游 provider 可达性与凭据（Agent 会降级但答案质量下降） |
| `WorkflowApprovalsTimingOut` | `workflow_approval_timeout_total` 有增长 | warning | 待办分派与值班 |
| `KnowledgeIngestionBacklogAging` | `knowledge_ingestion_oldest_pending_seconds > 900` 持续 10m | warning | ingestion worker 是否在跑、embedding / 向量库可达性 |

改完规则本地先过语法：

```bash
# 必须挂到 /etc/prometheus：prometheus.yml 里的 rule_files 是容器内绝对路径，挂别处会报 rule 文件不存在
docker run --rm -v "$PWD/deploy/prometheus:/etc/prometheus:ro" --entrypoint promtool \
  prom/prometheus:v2.55.1 check config /etc/prometheus/prometheus.yml
# SUCCESS: 1 rule files found / SUCCESS: 8 rules found
```

---

## 4. 健康检查（Health）

所有服务暴露 `GET /actuator/health`（`health.probes.enabled=true`，含 liveness/readiness group）。
`platform-observability` 额外提供一个 **LLM 网关 TCP 探测**：

- **`gateway` HealthIndicator**（`GatewayHealthIndicator`）：对 `platform.gateway.base-url`（LiteLLM）做 TCP 连通性探测，出现在 `/actuator/health` 的 `"gateway"` 节点。v2 里 provider 路由都在网关，**这一个探测即覆盖所有下游 LLM 调用的网络就绪**。仅当下游服务已引 actuator 时装配；未配 base-url → `UNKNOWN`。
- **TCP 探测的取舍**（沿用单体洞察）：只查网络可达，不发 LLM 请求 → 不烧 token、不需 api-key 有效、1s 内出结果，适合 K8s readiness/liveness probe；但不反映模型实际可推理能力（那要靠 OTel 的 `error.type` span 或成本/用量趋势监控）。

```bash
curl -s http://localhost:9081/actuator/health   # conversation，含 gateway 节点（management 端口）
curl -s http://localhost:9080/actuator/health   # edge-gateway
curl -s http://localhost:8085/health            # AgentScope：单端口应用
```

K8s probe 打 **`mgmt` 端口**（Helm 的 `platform-lib` 已按此渲染，无需手写）：

```yaml
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: mgmt }
  initialDelaySeconds: 5
  periodSeconds: 10
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: mgmt }
  initialDelaySeconds: 30
  periodSeconds: 30
```

> 探针必须打 management 端口：业务端口上 `/actuator/**` 会被内部 JWT filter 拦成 401，kubelet 不带凭据 → Pod 永远 not ready。
> 本地脚本同理，`deploy/smoke-*.sh` 与 `deploy/dev-infra/smoke.py` 的健康等待都已改用 management 端口。

要把 `gateway` 探测纳入 readiness group：

```yaml
management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState, gateway
```

---

## 5. 单体 → 微服务：可观测性差异一览

| 维度 | 单体（`LangChain4j_project`） | 新微服务平台 |
| --- | --- | --- |
| Actuator | 单进程一套端点（与业务同端口 `:8080`） | **每服务一套**，且搬到独立 management 端口（业务端口 +1000）；`health,info,prometheus` 为全服务基线（见 3.1） |
| traceId | 单进程 `TraceIdFilter` 打 MDC | 同款 filter + **`OutboundTraceForwarder` 跨服务透传**；由第一个 servlet 下游服务铸造、沿 `X-Trace-Id` 传播；网关（WebFlux）不打 traceId |
| LLM 聚合指标 | 自研 `MetricsChatModelListener` 发 `gen_ai_client_*` 四指标 | **不再有**这套 counter；每调用信号移到 OTel span，token→`/actuator/tokenbudget`，成本→`gen_ai.client.cost.usd` |
| OTel 追踪配置 | 自研 `app.observability.otel.*`（enabled/endpoint/service-name/sampler…） | **Spring Boot 原生** `management.tracing.*` + `management.otlp.tracing.*`；默认关由 `TracingDefaultsEnvironmentPostProcessor` 兜底 |
| span API | 原生 OpenTelemetry SDK | `io.micrometer.tracing`（micrometer-tracing-bridge-otel），属性语义不变 |
| 健康探测 | `llm`/`embedding` 双 TCP 探测各自 base-url | 单个 `gateway` TCP 探测 LiteLLM（provider 路由都在网关，一探即覆盖） |
| Prometheus registry | 已在 classpath | 由 `platform-observability` 统一带入（`runtime`、非 `optional`），edge-gateway / config-server 自行声明；全服务 `/actuator/prometheus` 可抓 |
| 抓取凭据 | 单进程、无内部鉴权 | actuator 在**免鉴权的 management 端口**上；业务端口仍要内部 JWT。内部 JWT 只活 5 分钟，做不了静态抓取凭据 |
| 抓取/告警配置 | 无 | 随仓库交付：`deploy/prometheus/prometheus.yml` + `alerts.yml`（8 条），`deploy/test-observability-config.sh` 静态守一致性 |

---

## 6. Grafana Dashboard

单体在 `LangChain4j_project/docs/grafana-dashboard.json` 提供了一份预制 dashboard（7 个 panel：Request Rate / Latency
p50·p95·p99 / Token Usage / Error Rate / 24h Token Spend / Health / Request Count），依赖一个 `prometheus` 数据源，
变量 `$provider`/`$model` 从指标 label 派生。

**新平台尚未内置该 JSON**，且它引用的 `gen_ai_client_requests_total` / `_operation_duration_seconds` / `_token_usage_total` /
`_errors_total` 在 v2 里**不再产出**（见第 5 节）。如需沿用，请：

1. 起 Prometheus（3.3 节的 `observability` profile）并在 Grafana 里把它加成数据源；抓取配置与 registry 已就绪，不用再改 pom；
2. 把 panel 的查询改成 v2 实际有的指标：成本用 `gen_ai_client_cost_usd_total`、耗时/错误改从 **OTel span**（Jaeger/Tempo）看、token 用量看 `/actuator/tokenbudget`、切分质量用 `rag_chunk_*`、工作流用 `workflow_*`；
3. 导入方式不变：Grafana → Dashboards → New → Import → Upload JSON file。

---

## 7. 开关与端点速查

### 环境变量（env → 默认）

| env / 属性 | 默认 | 作用 |
| --- | --- | --- |
| `MANAGEMENT_TRACING_ENABLED` (`management.tracing.enabled`) | `false` | OTel GenAI 追踪总开关（`TracingDefaultsEnvironmentPostProcessor` 兜底关） |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` (`management.otlp.tracing.endpoint`) | 不设 → 本机 `http://localhost:4318/v1/traces` | OTLP HTTP collector 端点 |
| `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` (`management.tracing.sampling.probability`) | `0.1` | 采样比例 0.0–1.0（本地调试设 `1.0`） |
| `TOKEN_BUDGET_STORE` | `redis` | token 用量计数存储（无 redis 设 `in-memory`）；`/actuator/tokenbudget` |
| `COST_STORE` | `redis` | 成本快照存储；成本归因默认关，开启后生效；`/actuator/cost` |
| `platform.gateway.base-url` | 各服务 yml | `gateway` 健康探测的 TCP 目标（LiteLLM） |
| `management.endpoints.web.exposure.include` | 见 3.1 | 每服务暴露的 Actuator 端点集合（基线 `health,info,prometheus`） |
| `MANAGEMENT_PORT` (`management.server.port`) | 业务端口 + 1000（各服务 yml） | actuator 独立监听端口。抓取与探针都打这里；**只在内网/集群内暴露** |
| `SERVER_PORT` (`server.port`) | 见 3.1 | 业务端口，仍受内部 JWT filter 保护 |
| `PROMETHEUS_HOST_PORT` | `19090` | compose `observability` profile 里 Prometheus UI 的宿主端口 |

> traceId（`X-Trace-Id`/MDC `traceId`）**无开关、默认常开**，servlet 服务随 `platform-observability` 自动装配；OTel span listener 也常驻但**没开 tracing 时全程 no-op**。

### 端点速查（均为服务自身 management 端口直连，不经边缘网关）

| 端点 | 方法 | 暴露服务 | 说明 |
| --- | --- | --- | --- |
| `/actuator/health` | GET | 全部 | 健康（含 `gateway` TCP 探测、liveness/readiness group） |
| `/actuator/info` | GET | 全部 | 构建信息 |
| `/actuator/prometheus` | GET | **全部 16 个 Java 服务** | Micrometer 指标抓取（Prometheus 的抓取目标） |
| `/metrics` | GET | agentscope-orchestrator（业务端口 `:8085`） | AgentScope 侧指标，同为免鉴权运维端点 |
| `/actuator/tokenbudget` | GET | conversation/agent/vision | 各租户当日 token 用量/配额快照 |
| `/actuator/cost` | GET | conversation/agent/vision | 各租户当日成本 USD 快照（成本归因开启时有数） |
| `/actuator/gateway` | GET | edge-gateway | 网关路由信息（Spring Cloud Gateway 自带） |
| 响应头 `X-Trace-Id` | — | 全部 servlet 服务 | 跨服务日志关联 id（第 1 节） |

### 相关文档

- 运行/部署配置：[operations.md](../参考/operations.md)、[deployment-guide.md](deployment-guide.md)
- 成本归因细节：[cost-attribution.md](cost-attribution.md)
- 切分质量指标背景：[rag-guide.md](../对话与检索/rag-guide.md)
- 模型级联（`llm.cascade` 指标来源）：[agent-guide.md](../Agent编排/agent-guide.md)
- 架构总览：[架构文档.md](../参考/架构文档.md)
