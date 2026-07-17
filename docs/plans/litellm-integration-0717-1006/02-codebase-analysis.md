# 02 — 代码库分析（codebase-explorer）

## 1. 仓库现状摘要

这是 Java 21 / Spring Boot 3.3.5 / LangChain4j 1.13.1 的多模块 Maven 平台。LLM chat 调用已经集中到 `platform-gateway-client`，以 OpenAI 兼容协议指向 LiteLLM。部署栈已有 LiteLLM、Redis、MySQL 与独立 failover mock 栈，但 LiteLLM 主实例目前没有数据库、响应缓存、管理 UI 持久化配置或启用中的正式 fallback。

本次分析实际读取了下列源代码、配置和测试；结论不依赖推测的类或表。

## 2. 当前 ChatModel 调用链

```text
业务 Controller / @AiService / AiServices.builder
  -> Spring 全局唯一 ChatModel bean
     PlatformGatewayClientAutoConfiguration.chatModel(...)
       -> GatewayChatModelFactory.build()
          -> OpenAiChatModel.builder()
             baseUrl = platform.gateway.base-url
             apiKey  = platform.gateway.api-key
             model   = platform.gateway.model-name
             listeners = Spring 中所有 ChatModelListener
          -> POST <LiteLLM>/v1/chat/completions
             -> deploy/litellm/config.yaml 的 model_list
                -> 当前 chat-default = deepseek/deepseek-chat

流式：
  PlatformGatewayClientAutoConfiguration.streamingChatModel(...)
    -> GatewayChatModelFactory.buildStreaming()
      -> OpenAiStreamingChatModel

程序化变体：
  buildDeterministic() / build(modelName, temperature)
    -> eval judge、routing judge、grounding、memory profile、agent voting 等

级联：
  conversation CascadeConfig
    -> CascadeChatModelFactory.build/buildRater
      -> GatewayChatModelFactory 为 cheap/strong/rater 分别构造底层 ChatModel
```

### 2.1 关键实现事实

- `GatewayClientProperties` 的默认值是 `http://localhost:4000/v1`、`sk-litellm-master`、`chat-default`，尚无租户归因属性。
- `GatewayChatModelFactory.build(String, Double)` 和 `buildStreaming(String, Double)` 是所有网关模型构造的收口点，适合统一包装；不能只改全局 bean，否则程序化 judge/cascade 会漏归因。
- `PlatformGatewayClientAutoConfiguration` 用 `@ConditionalOnMissingBean(ChatModel.class)` 保证全局只有一个同步模型；流式同理。新增 wrapper 不能注册第二个 `ChatModel` bean。
- `GatewayChatModelFactory` 已把 `List<ChatModelListener>` 注入每一个底层 OpenAI 模型。wrapper 若走接口默认实现后再调用delegate，会造成 listener 双调用；实现必须覆盖并直接委托 delegate 的 `chat(request, options)`。在此前提下，`listeners()` 可代理delegate以保持自省语义。
- LangChain4j 1.13.1 的实际字节码/API 已核对：
  - `ChatRequest.toBuilder()` 存在；
  - `OpenAiChatRequestParameters.Builder.user(String)` 和 `overrideWith(ChatRequestParameters)` 存在；
  - `OpenAiChatModel.Builder.customHeaders(Supplier<Map<String,String>>)` 与流式 builder 同时存在；
  - 默认 OpenAI client 合并 headers 时 custom headers 后写，能够覆盖静态 `Authorization`；
  - `ChatModel.chat(ChatRequest, ChatRequestOptions)` 和流式同名方法会自己执行 listeners，wrapper 应覆盖该重载并调用 delegate，避免重复回调。

## 3. 身份来源与传播现状

### 3.1 可复用类型

- `platform-security/src/main/java/com/lrj/platform/security/TenantContext.java`
  - ThreadLocal 当前身份；无值返回 `ANONYMOUS`。
  - `Tenant` 是 `(tenantId, userId, scopes, department)` record。
  - 现有计量与限流都使用 `tenantId`。
- `InternalTokenAuthFilter` 从内部 JWT 重建 `TenantContext`，请求结束清理。
- `agent-service`、`interop-service`、`async-task-service` 的自定义 executor 已显式复制 `TenantContext`；这证明异步传播不是框架自动保证，新增归因必须测试上下文缺失。

### 3.2 依赖边界风险

`platform-gateway-client/pom.xml` 当前**没有**依赖 `platform-security`。大多数消费服务同时直接依赖两者，但 `eval-service` 依赖 gateway-client、没有直接依赖 security。若把 security 作为普通传递依赖加入 gateway-client，`eval-service` 会意外获得 `PlatformSecurityAutoConfiguration`，在 servlet 环境注册 `InternalTokenAuthFilter` 和 Redis rate-limit beans，属于明显回归。

可复用模式已在 `platform-observability/pom.xml` 出现：对可选框架类型使用 `<optional>true</optional>` + `@ConditionalOnClass`。最终实现应沿用此模式，并通过 gateway-client 自己的 `TenantIdentityProvider` SPI 解耦：security 在 classpath 时提供 `TenantContext` adapter；缺失时 `none` 可正常工作，非 `none` 要显式告警或 fail-fast，而不是悄悄假装有身份。

## 4. 计量、预算与限流现状

### 4.1 `platform-metering`

| 类 | 当前行为 | 可复用点 |
| --- | --- | --- |
| `TokenBudgetChatModelListener.onResponse` | 读取 response token usage，以 `tenantId` 调 `tracker.consume`；错误不扣 | 保留应用业务口径；无需移入 LiteLLM |
| `TokenBudgetTracker` | 有 `currentUsed`、`wouldExceed`、`consume`、`secondsUntilReset`、`snapshotAll` | 只存在预检接口，没有实际 guard 调用点 |
| `RedisTokenBudgetTracker` | Lua `INCRBY + PEXPIREAT`，多 pod 原子累加；Redis 故障 fail-open | 继续作为应用观测账 |
| `CostChatModelListener.onResponse` | 根据应用价目表估算 USD，写 `CostTracker` 与 Micrometer | 继续用于租户运营趋势，不替代 provider spend |
| `RedisCostTracker` | Lua `INCRBYFLOAT + PEXPIREAT`，失败仅告警 | 可作为对账的一侧 |
| `TokenBudgetEndpoint` / `CostEndpoint` | Actuator 快照 | 灰度观察与差异监控入口 |

仓库搜索确认没有 `TokenBudgetGuardFilter.java`，也没有生产代码调用 `wouldExceed()`；`docs/平台工程/cost-attribution.md` 也明确写明“尚未接入 pre-check guard filter”。因此当前 token budget 是计量，不是硬拦截。

### 4.2 边缘限流

`edge-gateway/src/main/java/com/lrj/platform/edge/EdgeRateLimitFilter.java`：

- 从内部 JWT 取 tenant；
- 以 `(tenantId, familyOf(path))` 调 `RateLimiterRegistry.tryConsume`；
- 拒绝返回 429、`Retry-After`、`X-RateLimit-*`；
- 控制业务入口 QPM，不知道实际 LLM token、provider 重试或 cascade 的多次调用。

`platform-security` 的 Redis rate limiter 已支持多 pod 原子共享。这和 LiteLLM 的 key TPM/RPM 是不同层次，应并存。

## 5. OTel 现状

### 5.1 Java 侧已有组件

- `platform-observability/src/main/java/com/lrj/platform/observability/otel/OtelChatModelListener.java`
  - `onRequest` 创建 `chat <model>` CLIENT span；
  - 写 `gen_ai.*`、`tenant.id`、`enduser.id`；
  - `onResponse/onError` 结束 span；
  - span 句柄放在 listener attributes。
- `OtelTracingAutoConfiguration` 在 Tracer + LangChain4j 同时存在时注册 listener。
- `TracingDefaultsEnvironmentPostProcessor` 以最低优先级写入 `management.tracing.enabled=false`，所以默认不建 Tracer。
- `platform-gateway-client` 已带 `micrometer-tracing-bridge-otel` 与 OTLP HTTP exporter。
- `docs/平台工程/observability-guide.md` 已给出 Jaeger all-in-one（4318/16686）本地方案，但 Compose 里目前没有 Jaeger/collector。

### 5.2 现有缺口

- LiteLLM `config.yaml` 没有 `callbacks: ["otel"]`。
- Compose 没有为 LiteLLM 配 OTLP endpoint/service name。
- LangChain4j OpenAI client 不是现有 `OutboundTraceForwarder` 覆盖的 RestTemplate；gateway factory 没有注入 `traceparent`。
- `OtelChatModelListener` 启动的 span 没有通过 `Tracer.withSpan` 在网络调用期间保持 current scope。直接只开 LiteLLM callback 会得到两棵不关联的 trace。

可复用方案是 gateway-client 已有 Micrometer tracing classpath，加一个动态 header supplier，通过 `Propagator.inject(currentTraceContext.context(), headers, Map::put)` 注入当前请求上下文。它至少让 LiteLLM server span 接到应用请求 trace；要让它精确成为 `chat <model>` span 子节点，需要重构 listener/HTTP instrumentation，超出最小范围并有流式 scope 泄漏风险。

## 6. 部署与 LiteLLM 配置现状

### 6.1 `deploy/docker-compose.yml`

- `litellm` 使用浮动 `ghcr.io/berriai/litellm:main-stable`，仅有 master key、DeepSeek key、配置挂载、4000 端口。
- 已有 `redis:7-alpine`，AOF + `redis-data` 卷，应用预算、成本、限流、语义缓存等已复用；LiteLLM 尚未连接。
- 只有 MySQL，没有 PostgreSQL。
- 没有 Jaeger/OTel Collector。
- conversation/workflow/analytics/knowledge/agent/vision 指向 LiteLLM；其中直接使用 `platform-gateway-client` 的源码依赖消费方是 conversation、workflow、analytics、agent、eval、vision（knowledge 的 LLM chat 路径不经该模块）。
- Compose 底部卷目前只有 `mysql-data`、`qdrant-data`、`redis-data`、`es-data`。

### 6.2 `deploy/litellm/config.yaml`

- `chat-default -> deepseek/deepseek-chat`。
- `vision-default -> openai/qwen2.5vl`（宿主 Ollama `/v1`）。
- 注释里已有 `ollama/llama3.1` 和 `openai/gpt-4o-mini` 示例，可证明备用候选名称来自仓库；当前没有真正的备用 model entry。
- `litellm_settings` 有 `num_retries: 2`、`request_timeout: 60`。
- fallback 仍被注释，且注释示例是 `chat-default -> chat-default` 自环，不能直接启用。
- `general_settings` 只有 master key。

### 6.3 已有 failover 测试资产

- `deploy/litellm/config.failover.yaml` 已有 `chat-default` mock-a、`chat-default-fallback` mock-b 和启用的 fallback。
- `deploy/docker-compose.failover.yml` 启动两个 Python mock + 独立 LiteLLM，端口 4010。
- `deploy/smoke-failover.sh`：先断言 mock-a，再停 mock-a，断言 mock-b，trap 中 `down -v` 清理测试栈。
- `deploy/litellm/mock_upstream.py` 提供确定性 OpenAI 兼容 mock。

这些资产应扩展而不是删除：主配置承担真实部署，test-only 配置承担不依赖云 key 的 failover/cache/spend/OTel 确定性验证。

## 7. 所有受影响文件

### 7.1 必改现有文件

| 路径 | 计划修改 |
| --- | --- |
| `deploy/docker-compose.yml` | 增加 LiteLLM PostgreSQL、Jaeger、卷、health/depends_on、DB/Redis/UI/OTel env；向 LLM 服务提供可选 tracing 与 tenant attribution env |
| `deploy/litellm/config.yaml` | 增加 fallback model；启用 fallbacks、Redis cache/auth cache、database_url、OTel callback 与有界 retry/timeout |
| `deploy/docker-compose.failover.yml` | 测试栈增加 PostgreSQL/Redis/Jaeger并注入相同管理能力，保持与主栈语义一致 |
| `deploy/litellm/config.failover.yaml` | 在 mock 模型之外加入 DB/cache/OTel 设置和可测试的低阈值配置 |
| `deploy/smoke-failover.sh` | 扩展为验证 failover、cache、spend persistence、virtual-key hard limit、OTel；仍只删除 test-only 卷 |
| `platform-gateway-client/pom.xml` | 以 optional 方式编译接入 `platform-security` adapter；不向 eval 传递 security 自动配置 |
| `platform-gateway-client/src/main/java/com/lrj/platform/gateway/GatewayClientProperties.java` | 新增三档 enum 属性与校验相关配置，默认 `NONE` |
| `platform-gateway-client/src/main/java/com/lrj/platform/gateway/GatewayChatModelFactory.java` | 统一包装同步/流式/指定模型/确定性模型；合并动态 Authorization 与 trace headers |
| `platform-gateway-client/src/main/java/com/lrj/platform/gateway/PlatformGatewayClientAutoConfiguration.java` | 装配 identity provider、virtual-key resolver、header supplier，保持单 ChatModel bean |
| `platform-gateway-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 注册可选 TenantContext adapter auto-configuration（若拆为独立配置类） |

### 7.2 计划新增文件（类名是实现契约，不代表仓库当前已有）

| 路径 | 责任 |
| --- | --- |
| `platform-gateway-client/src/main/java/com/lrj/platform/gateway/tenant/TenantAttributionMode.java` | `NONE/USER/VIRTUAL_KEY` enum |
| `platform-gateway-client/src/main/java/com/lrj/platform/gateway/tenant/TenantIdentityProvider.java` | 提供当前 `tenantId` 的小型 SPI |
| `platform-gateway-client/src/main/java/com/lrj/platform/gateway/tenant/TenantContextIdentityAutoConfiguration.java` | security 存在时以 `TenantContext.current().tenantId()` 实现 SPI |
| `platform-gateway-client/src/main/java/com/lrj/platform/gateway/tenant/TenantVirtualKeyResolver.java` | 按 tenant 解析 virtual key 的 SPI；返回缺失而非 master key |
| `platform-gateway-client/src/main/java/com/lrj/platform/gateway/tenant/EnvironmentTenantVirtualKeyResolver.java` | 从 `platform.gateway.tenant-virtual-keys.<tenant>` 读取 Secret-backed 配置 |
| `platform-gateway-client/src/main/java/com/lrj/platform/gateway/tenant/TenantAwareChatModel.java` | 覆盖同步 `chat(ChatRequest, ChatRequestOptions)`，写入/覆盖 OpenAI `user` 并委托 |
| `platform-gateway-client/src/main/java/com/lrj/platform/gateway/tenant/TenantAwareStreamingChatModel.java` | 流式等价 wrapper |
| `platform-gateway-client/src/main/java/com/lrj/platform/gateway/GatewayRequestHeadersSupplier.java` | 每请求合并 virtual-key Authorization 和 Micrometer Propagator headers |
| `platform-gateway-client/src/test/java/com/lrj/platform/gateway/tenant/TenantAwareChatModelTest.java` | 三档、参数保留、伪造覆盖、listener 不重复、并发 |
| `platform-gateway-client/src/test/java/com/lrj/platform/gateway/tenant/TenantAwareStreamingChatModelTest.java` | 流式归因与回调 |
| `platform-gateway-client/src/test/java/com/lrj/platform/gateway/GatewayRequestHeadersSupplierTest.java` | key fail-closed、secret 不泄漏、trace header 合并 |
| `platform-gateway-client/src/test/java/com/lrj/platform/gateway/PlatformGatewayClientAutoConfigurationTest.java` | 默认 none、security 缺失、单 bean 与配置绑定 |

### 7.3 文档实施阶段建议更新（不属于本次规划写入动作）

- `deploy/README.md`：启动前置、UI、PostgreSQL/Redis/Jaeger、备用 Ollama 模型。
- `docs/平台工程/observability-guide.md`：LiteLLM callback、共享 trace 的真实父子关系限制。
- `docs/平台工程/cost-attribution.md`：LiteLLM hard guard 与应用观测账分工、对账偏差。
- `docs/参考/operations.md`：监控、备份、密钥轮换、回滚 runbook。

## 8. 明确不应修改的代码

- `platform-metering` 生产类：本任务只定义双轨职责，不需改 tracker/listener。
- `edge-gateway` 限流代码：继续承担入口 QPM。
- 各业务 Controller/Service：归因必须由 gateway-client 透明完成。
- LiteLLM 内部 PostgreSQL 表或 Prisma migration：由固定镜像拥有，项目不复制。

## 9. 当前工作树注意事项

分析开始时仓库已有与本任务无关的用户修改：`docs/Agent编排/agent-guide.md`、`docs/README.md` 以及两个未跟踪文档。本次规划不会触碰、覆盖或纳入这些文件。
