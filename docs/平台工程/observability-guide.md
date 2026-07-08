# 可观测性指南（Observability）

本指南面向要在平台上做**日志排障、分布式追踪、指标监控与成本/配额观测**的开发者与运维。
可观测性是横切能力，由共享库 `platform-observability`（跨服务 traceId + OpenTelemetry GenAI 追踪 + 健康探测）
与 `platform-metering`（token 预算 / 成本 Actuator 端点）提供，随各 LLM 服务自动装配，**无需改任何服务代码**。

平台把「一次请求发生了什么」拆成三条**正交**的观测线，各看一个侧面、互不依赖、可分别开关：

| 观测线 | 由谁提供 | 看什么 | 默认状态 |
| --- | --- | --- | --- |
| **跨服务 traceId** | `platform-observability`：`TraceIdFilter` + `OutboundTraceForwarder` | 一条调用链在多服务日志里用同一个 id 串起来 | **默认开**（servlet 服务自动挂 filter） |
| **OpenTelemetry GenAI span** | `platform-observability`：`otel/OtelChatModelListener` + `OtelTracingAutoConfiguration`（配合 `platform-gateway-client` 的 `TracingDefaultsEnvironmentPostProcessor`） | 每次 LLM 调用的 span 树、耗时分解、token、finish_reason、租户归属 | **默认关**（`management.tracing.enabled=false` 兜底） |
| **指标 + Actuator** | Spring Boot Actuator + Micrometer（`platform-metering` 的 `tokenbudget`/`cost` 端点、`knowledge` 的 `ChunkMetrics`、`workflow` 的 `WorkflowMetrics`、`cost` 的 `gen_ai.client.cost.usd`） | 聚合趋势：请求量、成本、切分质量、工作流；租户 token/成本快照；健康 | **部分默认开**（health/info 全开；prometheus/tokenbudget/cost 按服务声明） |

> 阅读约定：
> - **业务接口**统一经边缘网关 `http://localhost:8080` + `-H 'X-Api-Key: dev-key-acme'`（网关校验 key → 签发内部 JWT → 路由下游）。
> - **Actuator（health/prometheus/tokenbudget/cost）属于运维面，不经边缘网关**——网关只路由业务路径（`/chat`、`/agent`、`/rag` 等），`/actuator/**` 不在路由表里。用**服务自身端口直连**访问（内网/本地）：conversation `:8081`、workflow `:8082`、analytics `:8083`、knowledge `:8084`、agent `:8085`、async-task `:8086`、channel `:8087`、interop `:8088`、eval `:8089`、vision `:8090`、voice `:8091`、edge-gateway `:8080`、config-server `:8888`。
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

每个服务独立暴露自己的 Actuator（单体是一个进程一套端点，微服务是**每服务一套**、用各自端口）。当前各服务
`management.endpoints.web.exposure.include` 实际声明如下（以各 `application.yml` 为准）：

| 暴露集合 | 服务 |
| --- | --- |
| `health,info,prometheus,tokenbudget,cost` | conversation `:8081`、agent `:8085`、vision `:8090` |
| `health,info,prometheus` | async-task `:8086`、channel `:8087`、interop `:8088`、eval `:8089`、voice `:8091` |
| `health,info`（不含 prometheus） | analytics `:8083`、knowledge `:8084`、workflow `:8082`、config-server `:8888` |
| `health,info,gateway` | edge-gateway `:8080`（额外 `gateway` 端点） |

> 注意：`knowledge` 虽然在采集 `rag.chunk.*` 指标，但其 exposure **默认不含 `prometheus`**——要抓它的切分质量指标，需把 `prometheus` 加进 knowledge 的 `management.endpoints.web.exposure.include`。

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

> **相对单体的重要差异（诚实提示）**：单体有自研 `MetricsChatModelListener` 发 `gen_ai_client_requests_total` / `_operation_duration_seconds` / `_token_usage_total` / `_errors_total` 四个聚合指标。新平台**没有**这套 Micrometer 聚合计数器——这些「每调用」信号改由**第 2 节的 OTel span** 承载（耗时/token/finish_reason/error 都在 span 上），token 用量另由 `/actuator/tokenbudget` 快照（3.4 节）、成本由 `gen_ai.client.cost.usd` counter 承载。

### 3.3 抓取（Prometheus scrape）

> **坑（务必先看）**：exposure 里写了 `prometheus` 只是**声明意图**。`/actuator/prometheus` 抓取端点由 `micrometer-registry-prometheus` 注册表激活，而当前依赖树里**没有**这个 registry（只有 `micrometer-core`/`micrometer-tracing-*`）。也就是说，直接 `curl /actuator/prometheus` 目前会 404。要真正抓取，需给相关服务的 pom **加上 `io.micrometer:micrometer-registry-prometheus`**（进程内所有 Micrometer 指标随即在该端点以文本格式暴露）。加了 registry 后：

```bash
curl -s http://localhost:8085/actuator/prometheus   # agent，加了 registry 后返回指标文本
```

最小 Prometheus 配置（每服务一个 target，因为端点是每服务独立的）：

```yaml
scrape_configs:
  - job_name: langchain4j-platform
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    static_configs:
      - targets:   # K8s 换成 <svc>.<ns>.svc.cluster.local:<port>
          - "conversation-service:8081"
          - "agent-service:8085"
          - "vision-service:8090"
          - "async-task-service:8086"
          # …按需补齐暴露了 prometheus 的服务
```

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

直连服务端口访问（不经网关）：

```bash
curl -s http://localhost:8081/actuator/tokenbudget   # conversation：各租户当日 token 用量
curl -s http://localhost:8085/actuator/cost          # agent：各租户当日成本 USD
```

响应示例（map：tenantId → 快照）：

```json
{ "acme": { "used": 12840, "budget": 200000, "day": "2026-07-09" } }
```

---

## 4. 健康检查（Health）

所有服务暴露 `GET /actuator/health`（`health.probes.enabled=true`，含 liveness/readiness group）。
`platform-observability` 额外提供一个 **LLM 网关 TCP 探测**：

- **`gateway` HealthIndicator**（`GatewayHealthIndicator`）：对 `platform.gateway.base-url`（LiteLLM）做 TCP 连通性探测，出现在 `/actuator/health` 的 `"gateway"` 节点。v2 里 provider 路由都在网关，**这一个探测即覆盖所有下游 LLM 调用的网络就绪**。仅当下游服务已引 actuator 时装配；未配 base-url → `UNKNOWN`。
- **TCP 探测的取舍**（沿用单体洞察）：只查网络可达，不发 LLM 请求 → 不烧 token、不需 api-key 有效、1s 内出结果，适合 K8s readiness/liveness probe；但不反映模型实际可推理能力（那要靠 OTel 的 `error.type` span 或成本/用量趋势监控）。

```bash
curl -s http://localhost:8081/actuator/health   # conversation，含 gateway 节点
curl -s http://localhost:8080/actuator/health   # edge-gateway
```

K8s probe（直连服务端口，与网关无关）：

```yaml
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8081 }
  initialDelaySeconds: 5
  periodSeconds: 10
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8081 }
  initialDelaySeconds: 30
  periodSeconds: 30
```

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
| Actuator | 单进程一套端点（`:8080`） | **每服务一套**，用各自端口；exposure 按服务声明（见 3.1） |
| traceId | 单进程 `TraceIdFilter` 打 MDC | 同款 filter + **`OutboundTraceForwarder` 跨服务透传**；由第一个 servlet 下游服务铸造、沿 `X-Trace-Id` 传播；网关（WebFlux）不打 traceId |
| LLM 聚合指标 | 自研 `MetricsChatModelListener` 发 `gen_ai_client_*` 四指标 | **不再有**这套 counter；每调用信号移到 OTel span，token→`/actuator/tokenbudget`，成本→`gen_ai.client.cost.usd` |
| OTel 追踪配置 | 自研 `app.observability.otel.*`（enabled/endpoint/service-name/sampler…） | **Spring Boot 原生** `management.tracing.*` + `management.otlp.tracing.*`；默认关由 `TracingDefaultsEnvironmentPostProcessor` 兜底 |
| span API | 原生 OpenTelemetry SDK | `io.micrometer.tracing`（micrometer-tracing-bridge-otel），属性语义不变 |
| 健康探测 | `llm`/`embedding` 双 TCP 探测各自 base-url | 单个 `gateway` TCP 探测 LiteLLM（provider 路由都在网关，一探即覆盖） |
| Prometheus registry | 已在 classpath | **当前未引入**，需按需加 `micrometer-registry-prometheus`（见 3.3 坑） |

---

## 6. Grafana Dashboard

单体在 `LangChain4j_project/docs/grafana-dashboard.json` 提供了一份预制 dashboard（7 个 panel：Request Rate / Latency
p50·p95·p99 / Token Usage / Error Rate / 24h Token Spend / Health / Request Count），依赖一个 `prometheus` 数据源，
变量 `$provider`/`$model` 从指标 label 派生。

**新平台尚未内置该 JSON**，且它引用的 `gen_ai_client_requests_total` / `_operation_duration_seconds` / `_token_usage_total` /
`_errors_total` 在 v2 里**不再产出**（见第 5 节）。如需沿用，请：

1. 先按 3.3 给服务加上 `micrometer-registry-prometheus` 并配好 scrape；
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
| `management.endpoints.web.exposure.include` | 见 3.1 | 每服务暴露的 Actuator 端点集合 |

> traceId（`X-Trace-Id`/MDC `traceId`）**无开关、默认常开**，servlet 服务随 `platform-observability` 自动装配；OTel span listener 也常驻但**没开 tracing 时全程 no-op**。

### 端点速查（均为服务自身端口直连，不经边缘网关）

| 端点 | 方法 | 暴露服务 | 说明 |
| --- | --- | --- | --- |
| `/actuator/health` | GET | 全部 | 健康（含 `gateway` TCP 探测、liveness/readiness group） |
| `/actuator/info` | GET | 全部 | 构建信息 |
| `/actuator/prometheus` | GET | conversation/agent/vision/async-task/channel/interop/eval/voice（**需加 `micrometer-registry-prometheus`**） | Micrometer 指标抓取 |
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
