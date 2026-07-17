# LiteLLM 能力具象化最终实施方案

> 状态：架构规划，可直接交给开发 Agent 执行。本文不代表代码已修改。所有版本敏感项在实施时先验证再落配置。

## 1. 背景

平台已经通过 `platform-gateway-client` 把六个服务的 ChatModel 统一指向 LiteLLM，但当前 LiteLLM 仍接近无状态转发器：正式配置只有 DeepSeek 文本模型与 Ollama 视觉模型，没有 PostgreSQL spend 持久化、响应缓存、管理 UI 所需数据库、启用中的正式 fallback 或 OTel callback。所有 Java 服务共用一个 master key，LiteLLM 看不到可信 tenant 归因。

应用侧已有两个相关但不同的能力：

- `platform-metering` 用 `ChatModelListener` 在成功响应后按 `TenantContext.tenantId` 累加 token/估算 USD；Redis tracker 原子，但没有生产 pre-check guard。
- `EdgeRateLimitFilter` 在业务入口按 `(tenant, endpoint family)` 做 QPM 硬限流。

本方案把 LiteLLM 升级成 provider 边界的持久化执行/硬保护面，同时保留应用侧业务观测，不建立虚假的跨系统强一致账。

## 2. 目标与非目标

### 2.1 目标

1. LiteLLM 连接独立 PostgreSQL，spend、virtual key 与 UI 管理状态可重启持久化。
2. 新增 `platform.gateway.tenant-attribution=none|user|virtual-key`，默认 none 与现状一致。
3. 同步、流式、deterministic、指定模型、cascade cheap/strong/rater 全路径租户归因。
4. LiteLLM 的美元预算、TPM/RPM、并发限制作为 provider 边界硬保底；应用 metering/edge 限流继续承担业务口径。
5. LiteLLM 响应缓存复用栈内 Redis，具备 TTL、namespace、健康/故障验证。
6. 正式 `config.yaml` 增加 `chat-default-fallback` 并启用有序 fallback。
7. LiteLLM OTel callback 输出到与 Java 相同的 Jaeger/OTLP HTTP 链路；Java出站传播 W3C上下文。
8. 形成可重复、无云 key依赖的测试与回滚流程。

### 2.2 非目标

- 不替换 edge API key、内部 JWT、Casdoor/SpiceDB。
- 不让 Java 访问 LiteLLM 内部 PostgreSQL 表，不复制第三方 migration。
- 不新增业务账单/发票 API，不把 Redis估算成本回填 LiteLLM。
- 不实现 `TokenBudgetGuardFilter`、预扣/退款或跨系统分布式事务。
- 不扩展到 embedding/ASR/TTS，不为视觉模型虚构备用 provider。
- 不公网暴露 `/ui`，不引入 LiteLLM 企业 SSO。
- 不在本轮新增 key provisioning 控制面、Python custom auth plugin 或业务数据库表。

## 3. 已确认业务规则

### 3.1 三档归因

| 模式 | 请求体 `user` | Authorization | 缺身份/key行为 |
| --- | --- | --- | --- |
| `none` | 不改 | `platform.gateway.api-key` | 完全沿用当前行为 |
| `user` | 强制覆盖为可信 `tenantId` | 共享 key | 无 security 时归 `anonymous` 并启动告警；不得信任调用方 user |
| `virtual-key` | 强制为 `tenantId` | 当前 tenant 的 virtual key | key 缺失/空白/resolver失败时调用 provider 前失败，禁止退 master key |

- 归因键是 `tenantId`，与现有限流、token/成本一致；`userId` 不作为 LiteLLM tenant 账键。
- `anonymous` 是合法 user 归因，但 virtual-key 必须显式有 anonymous key。
- key secret 来自 `platform.gateway.tenant-virtual-keys.<tenant>` 对应的 Secret/Environment，绝不提交和记录。
- 模式只影响 OpenAI chat 请求；默认 none 不要求 security runtime，因此 `eval-service` 不会因传递依赖意外注册安全 filter。

### 3.2 双轨职责

| 层 | 职责 | 是否硬拒绝 | 是否财务权威 |
| --- | --- | --- | --- |
| LiteLLM + PostgreSQL | provider路由后 spend；key/user/team；美元预算；TPM/RPM/max parallel | 是 | provider spend的运营权威；仍需与供应商账单对账 |
| platform-metering + Redis | tenant成功响应 token；应用价目估算 USD；Actuator | 本任务否 | 否，业务观测账 |
| edge + Redis rate limiter | API入口 endpoint-family QPM | 是 | 否，流量保护 |

硬保底阈值不在开发阶段臆造：生产 `global max budget`、key/user budget、TPM/RPM、max parallel 必须由业务/运维给出。上线门禁要求这些值已配置；test-only 栈使用极低值验证拒绝。LiteLLM硬阈值建议高于正常业务告警线，避免双层计数边界抖动。

### 3.3 数据、缓存、failover 与 trace

- PostgreSQL 独立于现有 MySQL；首次上线不迁历史 spend。
- 固定 LiteLLM tag/digest后才允许建库/迁移；当前 `main-stable` 必须替换，具体版本待兼容矩阵验证。
- Redis response cache设置有限 TTL和 namespace；优先逻辑 DB 1，固定镜像若不支持该键则保留 namespace并记录。
- 正式文本备用为仓库现有配置注释已经提到的 `ollama/llama3.1`，逻辑名 `chat-default-fallback`；运行前必须 pull。它与 DeepSeek 在工具调用、JSON schema、上下文窗和流式细节上不应被假定等价，灰度前必须跑代表性能力测试。视觉不加 fallback。
- virtual key 若配置 model allowlist，必须确认 LiteLLM 的 fallback 授权语义；固定版本若要求显式授权 fallback target，则 key 同时允许 `chat-default` 与 `chat-default-fallback`，否则主故障时会被授权层阻断。
- Java tracking默认仍为 false；显式开启时通过 Micrometer Propagator注入 trace headers。LiteLLM callback常规输出到 Jaeger。
- 本轮验收“Java 与 LiteLLM 同 trace ID”；不承诺 LiteLLM span 精确成为当前 `OtelChatModelListener` chat span 的直接子节点。

## 4. 当前代码与调用链分析

### 4.1 真实构造链

`PlatformGatewayClientAutoConfiguration.chatModel()` → `GatewayChatModelFactory.build()` → `OpenAiChatModel.builder()`；流式走 `streamingChatModel()` → `buildStreaming()`。`buildDeterministic()` 和 `build(model, temperature)` 被 eval、routing、grounding、memory、agent voting 使用。`CascadeChatModelFactory` 也通过同一个 gateway factory构造 cheap、strong、rater。

因此 wrapper 必须放在 `GatewayChatModelFactory` 的同步/流式构造出口，不能只包全局 Spring bean。

### 4.2 LangChain4j 1.13.1 已核实 API

- `ChatRequest.toBuilder()`；
- `OpenAiChatRequestParameters.builder().overrideWith(...).user(...)`；
- 同步/流式 OpenAI builder 都有 `customHeaders(Supplier<Map<String,String>>)`；
- custom headers 后合并，能覆盖静态 Authorization；
- 同步 `chat(ChatRequest, ChatRequestOptions)` 和流式三参 `chat` 是 listener 调用边界。

wrapper 要覆盖上述 options重载并直接调用 delegate相同重载；不要调用 delegate `doChat`。`listeners()` 可代理 delegate以保持接口自省语义，因为实际执行边界已经直接进入 delegate；关键是不能再让 wrapper 的默认 `chat(...)` 路径执行同一组 listeners。

### 4.3 身份与依赖

`TenantContext.current()` 无上下文时返回 `ANONYMOUS`；绝大多数 gateway消费者也直接依赖 security，但 `eval-service` 没有。`platform-security` 自动配置会在 servlet应用注册 `InternalTokenAuthFilter` 和 Redis rate limiter，故 gateway-client只能 optional编译依赖，并用自己的 `TenantIdentityProvider` SPI隔离。

### 4.4 计量与追踪事实

- `TokenBudgetChatModelListener.onResponse` 后置 `consume`；没有生产 `wouldExceed()` 调用点。
- `CostChatModelListener` 是价目表估算；默认关闭。
- `RedisTokenBudgetTracker`/`RedisCostTracker` 失败 fail-open，仅告警。
- `OtelChatModelListener` 已创建 GenAI span，但 LangChain4j HTTP不走 `OutboundTraceForwarder`，当前无明确 traceparent注入。
- Compose无 PostgreSQL/Jaeger，LiteLLM未连现有 Redis。

## 5. 候选方案与统一评分

评分 1–5，5 最优；复杂度、测试难度、回滚成本按低/易得高分。

| 方案 | 正确性 25% | 风险 20% | 低复杂度 15% | 可维护 15% | 扩展 10% | 易测试 10% | 易回滚 5% | 总分 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A 请求包装+动态header | 4.5 | 4.3 | 4.2 | 4.1 | 3.8 | 4.0 | 4.5 | **4.24** |
| B 每租户delegate池 | 4.4 | 3.4 | 3.0 | 3.0 | 3.7 | 3.1 | 3.4 | 3.60 |
| C LiteLLM自定义鉴权 | 4.2 | 2.1 | 1.8 | 2.6 | 4.5 | 2.0 | 2.0 | 2.70 |
| D 控制平面闭环 | 4.7 | 1.8 | 1.2 | 3.0 | 5.0 | 1.4 | 1.3 | 2.72 |

完整比较见 `comparison.md`。A不是完美方案：它下发长期key、依赖custom header覆盖、共享Redis故障域较大；D长期扩展性最好。但按本次范围与当前工厂结构，A是首轮最合理主体。

## 6. 最终方案及选择原因

采用方案 A，并吸收 B/C/D 的部分原则：

1. `TenantAwareChatModel` 与流式 sibling 每请求改可信 `user`，无状态、线程安全。
2. 一个共享 OpenAI delegate；`GatewayRequestHeadersSupplier.get()` 每发送动态合并 tenant virtual-key Authorization 与 Micrometer trace headers。
3. `TenantIdentityProvider`/`TenantVirtualKeyResolver` 两个 SPI隔离身份和Secret来源；未来可换 Vault或短时签名，不改wrapper。
4. key缺失 fail-closed；保留 B的固定delegate作为custom header契约失败时的技术回退，不预先实现其registry。
5. 只制定 key alias/version、rotation和对账规则，不实现 D的控制面。
6. LiteLLM PostgreSQL/Redis/fallback/OTel全部由网关配置完成，platform-metering不改生产代码。

### 6.1 已知弱点

- 每个 LLM应用实例需要拿到所服务租户的长期 virtual keys；Secret分发和轮换是主要运营成本。
- Java current trace注入能让 LiteLLM进入同一 trace，但现有 listener span scope不足以保证精确父子关系。
- 现有异步执行器只明确复制了 `TenantContext`/MDC，没有统一复制 Micrometer trace context；后台agent/workflow调用的跨进程trace连续性要单测验证，缺上下文时LiteLLM会产生新root trace。本轮不借机重写所有executor。
- Redis与关键限流/计数共享物理实例，namespace/逻辑DB不能隔离资源。
- LiteLLM第三方schema和错误码随版本变化；必须pin镜像并实测，不能依赖 `main-stable`。
- `user` 模式只适合统一end-user阈值/归因，差异化tenant policy应使用virtual-key。
- LiteLLM spend与应用metering不会逐请求相等；这是真实架构取舍，不是待修复“事务bug”。

## 7. 精确修改清单

### 7.1 `platform-gateway-client`

#### 修改 `GatewayClientProperties`

文件：`platform-gateway-client/src/main/java/com/lrj/platform/gateway/GatewayClientProperties.java`

- 增加字段 `TenantAttributionMode tenantAttribution = TenantAttributionMode.NONE`。
- getter/setter `getTenantAttribution()` / `setTenantAttribution(...)`。
- 不在该 bean保存/输出 virtual key Map，避免 `/actuator/configprops` 或日志扩大暴露面。

#### 新增 enum 与 SPI

包：`com.lrj.platform.gateway.tenant`

- `TenantAttributionMode { NONE, USER, VIRTUAL_KEY }`。
- `TenantIdentityProvider.currentTenantId(): String`。
- `TenantVirtualKeyResolver.resolve(String tenantId): Optional<String>`。
- `EnvironmentTenantVirtualKeyResolver.resolve(...)` 从 Spring `Environment` 读取 `platform.gateway.tenant-virtual-keys.<tenantId>`；trim后空白算缺失。
- `TenantContextIdentityAutoConfiguration`：仅 `com.lrj.platform.security.TenantContext` 存在时创建 `TenantIdentityProvider`，方法体返回 `TenantContext.current().tenantId()`；允许用户 bean覆盖。

没有 security adapter时，factory使用匿名 provider。USER模式记录一次启动 warning；VIRTUAL_KEY依赖 `anonymous` key或在请求前失败。

#### 新增同步 wrapper

文件：`platform-gateway-client/src/main/java/com/lrj/platform/gateway/tenant/TenantAwareChatModel.java`

- 构造参数：`ChatModel delegate`、`TenantAttributionMode mode`、`TenantIdentityProvider identities`。
- 覆盖 `chat(ChatRequest request, ChatRequestOptions options)`：
  - NONE直接 `delegate.chat(request, options)`；
  - USER/VIRTUAL_KEY调用 package-private `attributedRequest(request, tenantId)`，再委托。
- `attributedRequest` 用 `OpenAiChatRequestParameters.builder().overrideWith(request.parameters()).user(tenantId).build()`，再 `request.toBuilder().parameters(...).build()`。
- 覆盖 `defaultRequestParameters()`、`provider()`、`supportedCapabilities()`、`listeners()` 代理 delegate；由于两参数 `chat` 已直接委托，不会先执行 wrapper 默认 listener 边界，因此仍只回调一次。
- 不记录 messages/user secret。

#### 新增流式 wrapper

文件：`platform-gateway-client/src/main/java/com/lrj/platform/gateway/tenant/TenantAwareStreamingChatModel.java`

- 覆盖 `chat(ChatRequest, ChatRequestOptions, StreamingChatResponseHandler)`，与同步相同归因后调用 delegate。
- 代理 default params/provider/capabilities/listeners；三参数 `chat` 直接委托，避免重复 listeners。

#### 新增 header supplier

文件：`platform-gateway-client/src/main/java/com/lrj/platform/gateway/GatewayRequestHeadersSupplier.java`

- 实现 `Supplier<Map<String,String>>.get()`。
- VIRTUAL_KEY时：取当前 tenant→resolver；缺失抛 `IllegalStateException` 或专用 `TenantVirtualKeyMissingException`（异常不得含key）；返回 `Authorization: Bearer ...`。
- Tracer/Propagator/current context存在时：`propagator.inject(context, headers, Map::put)`。
- 每次返回新 `LinkedHashMap`；两类headers合并，不能覆盖彼此。

#### 修改 `GatewayChatModelFactory`

文件：`platform-gateway-client/src/main/java/com/lrj/platform/gateway/GatewayChatModelFactory.java`

- 构造器增加 identity provider、virtual-key resolver和可选 tracing header依赖。
- 提取私有 `buildBase(modelName, temperature)` 与 `buildStreamingBase(...)`，在 OpenAI builder同时配置 `customHeaders(headersSupplier)`。
- `build(String, Double)` 始终返回 `new TenantAwareChatModel(base, mode, identities)`。
- `buildStreaming(String, Double)` 返回流式wrapper。
- `build()`、`buildDeterministic()`继续调用上述出口，保证 judge/cascade覆盖。

#### 修改 auto configuration 与依赖

- `PlatformGatewayClientAutoConfiguration.gatewayChatModelFactory(...)` 注入 `ObjectProvider<TenantIdentityProvider>`、`TenantVirtualKeyResolver`、Tracer/Propagator provider，identity缺失时匿名；Tracing provider使用 `getIfUnique()` 或等价确定性选择，避免多个bean时启动期歧义。
- 提供 `@ConditionalOnMissingBean TenantVirtualKeyResolver` 的环境实现。
- 继续只注册一个 ChatModel bean；不把 wrapper另注册成第二个。
- `platform-gateway-client/pom.xml` 增加 optional `platform-security`，不能传递到eval。
- AutoConfiguration imports增加可选 TenantContext adapter配置。

#### 新增测试

- `tenant/TenantAwareChatModelTest.java`
- `tenant/TenantAwareStreamingChatModelTest.java`
- `GatewayRequestHeadersSupplierTest.java`
- `PlatformGatewayClientAutoConfigurationTest.java`

### 7.2 部署

#### 修改 `deploy/docker-compose.yml`

1. LiteLLM镜像改为经测试固定 tag/digest（值待实施验证）。
2. 新增 `litellm-postgres`：固定 PostgreSQL主版本、独立 user/db、healthcheck、`litellm-postgres-data` 卷；密码从环境注入。
3. LiteLLM环境增加：
   - `DATABASE_URL`
   - `REDIS_HOST=redis`、`REDIS_PORT=6379`，优先 `REDIS_DB=1`
   - `UI_USERNAME`、`UI_PASSWORD`
   - `OTEL_EXPORTER=otlp_http`、`OTEL_ENDPOINT=http://jaeger:4318`、`OTEL_SERVICE_NAME=litellm-proxy`
   - 主/备 provider所需key；备用Ollama无key但需host访问。
4. LiteLLM `depends_on` 等 PostgreSQL healthy、Redis started；增加自身healthcheck。
5. 新增 `jaegertracing/all-in-one` 固定版本，开放 4318与本地 UI 16686；版本可沿用仓库文档已验证的 1.57或在实施时更新后锁定。
6. 直接 LLM消费服务加入：
   - `PLATFORM_GATEWAY_TENANT_ATTRIBUTION=${...:-none}`；
   - `MANAGEMENT_TRACING_ENABLED=${...:-false}`；
   - `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://jaeger:4318/v1/traces`；
   - sampling由环境覆盖，默认沿用Boot 0.1。
7. virtual key不写入Compose文件；通过 Compose override、`env_file`（不提交）或Secret注入标准属性。所有发起LLM调用的服务需一致获得映射。
8. 新增 `litellm-postgres-data` 卷；保留现有所有卷。

#### 修改 `deploy/litellm/config.yaml`

- 添加：

```yaml
- model_name: chat-default-fallback
  litellm_params:
    model: ollama/llama3.1
    api_base: http://host.docker.internal:11434
```

- 在固定镜像上验证后，使用单一已支持位置配置：
  - `fallbacks: [{chat-default: [chat-default-fallback]}]`
  - 有界 `num_retries`、`request_timeout`、cooldown；删除自环注释。
- `general_settings.database_url: os.environ/DATABASE_URL`。
- `litellm_settings.cache: true`、Redis `cache_params`、TTL/namespace、`enable_redis_auth_cache: true`。
- `litellm_settings.callbacks: ["otel"]`。
- 全局预算/默认key上限只引用经业务确认的env；不在代码评审中擅自填生产数值。若固定镜像不接受空值，通过部署overlay加入该块。
- virtual key provisioning必须测试model allowlist对fallback target的授权；需要时同时授权两个逻辑模型，但对应用仍只公开主逻辑名。

#### 测试栈与脚本

- `deploy/docker-compose.failover.yml` 增加test-only PostgreSQL、Redis、Jaeger；所有名字/卷仍属于 `llm-failover-smoke` project。
- `deploy/litellm/config.failover.yaml` 加相同 DB/cache/OTel 能力，保留mock fallback；测试阈值可极低且仅test-only。
- 扩展 `deploy/smoke-failover.sh`：启动→health/UI/cache ping→生成key→主模型→cache hit→停主→fallback→硬限额→spend持久→Jaeger trace→停主备有界失败；trap只删除test project卷。

### 7.3 platform-metering

生产代码**不修改**。实施文档明确：

- `TokenBudgetChatModelListener.onResponse` 与 `CostChatModelListener.onResponse` 保留。
- 不新增DB/消息/接口。
- 用 `/actuator/tokenbudget`、`/actuator/cost` 与LiteLLM spend/API做趋势对账。

## 8. 数据库、接口、配置与消息结构变更

### 8.1 数据库

| 项 | 变更 |
| --- | --- |
| 新数据库 | `litellm-postgres` PostgreSQL实例/数据库 |
| schema owner | 固定版本LiteLLM镜像 |
| 应用migration/entity | 无 |
| 初始数据 | UI/API创建的user/team/virtual key；不迁Redis历史 |
| 备份 | 升级前volume snapshot/`pg_dump`，恢复演练后上线 |
| 内部表名 | 不依赖；随版本待验证 |

### 8.2 接口

- 平台业务HTTP API无变化。
- 使用LiteLLM现有管理面：`/ui`、key/user/team管理与spend查询、`/cache/ping`；具体请求/响应以固定镜像OpenAPI冻结。
- Chat失败新增可见来源：virtual key缺失是应用配置错误；LiteLLM预算/限流是OpenAI兼容非2xx。业务层现有异常映射需回归，不在本任务臆造新业务error DTO。

### 8.3 出站消息结构

OpenAI chat JSON在 USER/VIRTUAL_KEY增加或覆盖顶层：

```json
{"user":"<tenantId>"}
```

VIRTUAL_KEY动态覆盖HTTP `Authorization: Bearer <tenant-virtual-key>`；tracing开启时增加标准 `traceparent`/可选 `tracestate`。Kafka/eventbus消息无变化。

### 8.4 配置表

| 配置 | 默认 | 敏感 | 说明 |
| --- | --- | --- | --- |
| `platform.gateway.tenant-attribution` | `none` | 否 | 三档总开关 |
| `platform.gateway.tenant-virtual-keys.<tenant>` | 无 | **是** | virtual-key映射；缺失fail-closed |
| `DATABASE_URL` | 无 | **是** | LiteLLM PostgreSQL URL |
| `LITELLM_MASTER_KEY` | 本地开发值 | **是** | 管理key；生产必须Secret |
| `UI_USERNAME/UI_PASSWORD` | 本地开发值/无 | **是** | UI登录 |
| `REDIS_HOST/PORT/DB` | redis/6379/建议1 | 视部署 | LiteLLM cache/auth cache |
| cache TTL/namespace | 待压测；必须有限 | 否 | 主配置固定或env |
| global/key budget、RPM/TPM/parallel | 无业务默认 | 否 | 上线前必须由业务/运维确认 |
| `OTEL_ENDPOINT` | `http://jaeger:4318/v1/traces`（Compose） | 否 | LiteLLM OTLP HTTP；**必须带 /v1/traces 路径，裸端口会 404**（实施时真机验证） |
| `MANAGEMENT_TRACING_ENABLED` | false | 否 | Java追踪保持默认关 |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | Compose指向Jaeger | 否 | Java OTLP HTTP `/v1/traces` |

## 9. 分阶段实施步骤与依赖关系

依赖链：阶段1定义状态/契约 → 阶段2实现纯Java核心 → 阶段3接Spring与LiteLLM/Compose → 阶段4验证 → 阶段5文档和发布检查。不得先让生产流量使用数据库/virtual key再补迁移和回滚验证。

### 阶段一：数据结构与领域模型

步骤：

1. 选定并记录固定 LiteLLM tag/digest；建立版本兼容矩阵。
2. 在test-only空PostgreSQL验证该镜像自动schema、管理API、UI和升级日志；不手写内部表。
3. 定义 `TenantAttributionMode`、`TenantIdentityProvider`、`TenantVirtualKeyResolver` 的接口和语义。
4. 给 `GatewayClientProperties` 加默认 NONE；定义Secret属性命名和特殊tenant ID规则。
5. 确认生产预算/RPM/TPM/并发阈值的责任人和数值；未确认则阻止生产启用hard guard，但不阻止代码测试。

完成标准：

- 镜像固定且空库启动/重启通过；schema对象由镜像创建。
- enum/SPI编译、配置绑定测试通过，默认 none。
- 没有业务表/migration，未提交Secret。
- 预算阈值有签字配置或明确“仅不可生产上线”的阻塞记录。

### 阶段二：核心业务逻辑

步骤：

1. 实现 `TenantAwareChatModel.attributedRequest` 与 options重载委托。
2. 实现流式wrapper。
3. 实现 `GatewayRequestHeadersSupplier`：virtual key fail-closed + Propagator header合并。
4. 修改 `GatewayChatModelFactory` 所有构造出口，保证deterministic/cascade也归因。
5. 保持底层listeners唯一执行，不改platform-metering。

完成标准：

- 三档同步/流式单测全过；调用方user无法伪造。
- 1000次并发不同tenant不串key/user。
- listener每次只回调一次。
- build/buildDeterministic/指定模型/cascade矩阵均覆盖。

### 阶段三：接口与适配层

步骤：

1. 加optional security依赖、TenantContext adapter和Environment virtual key resolver；验证eval无传递security。
2. 调整Spring auto config，保持唯一ChatModel bean。
3. Compose新增PostgreSQL、Jaeger、卷、health/depends_on和env。
4. 主LiteLLM config启用DB/cache/auth cache/OTel/fallback与备用模型。
5. 同步更新test-only compose/config；不给主仓库写virtual key。
6. 配置Java服务可选tracing与tenant attribution env，默认仍none/false。

完成标准：

- `docker compose config`与LiteLLM config加载成功。
- `/ui`、readiness、`/cache/ping`正常。
- 默认整栈现有服务启动，eval没有意外security filter/Redis依赖。
- 正式config无fallback自环，backup entry可解析。

### 阶段四：测试

步骤：

1. 跑gateway-client单元/装配测试。
2. 跑六个消费服务目标回归和全仓测试。
3. 扩展mock冒烟，覆盖PostgreSQL持久、cache、user/key、hard guard、fallback、OTel。
4. 注入PG/Redis/Jaeger/主备provider故障。
5. 做LiteLLM版本升级/PG恢复和配置回滚演练。
6. 记录应用metering与LiteLLM spend在cache/retry/fallback/stream cancel下的差异基线。

完成标准：

- `test-plan.md`最终清单全部通过，或每个版本限制有批准的排除项。
- 无真实云key也能完成核心CI冒烟。
- hard guard与跨租户隔离成立；双失败有界。
- Java和LiteLLM同trace，trace无prompt/key。
- 备份恢复实际演练，不只是文档步骤。

### 阶段五：文档与最终检查

步骤：

1. 更新deploy README、observability、cost attribution、operations。
2. 写UI访问限制、key创建/轮换、Ollama pull、备份/恢复、Redis容量、回滚runbook。
3. 安全扫描git diff/日志/Compose，确认无secret。
4. 审核默认值：tenant none、Java tracing false；确认缓存/fallback是明确启用的需求变化。
5. 记录固定镜像、测试命令/结果、配置样例和已知弱点。

完成标准：

- 新Agent只读文档即可部署、灰度、回滚。
- PR列出受影响模块、测试、Compose影响、配置与Secret要求。
- 无浮动LiteLLM镜像、无未知“待验证”项直接进入生产。

## 10. 测试方案摘要

完整用例见 `test-plan.md`，强制门禁：

- 单元：none/user/virtual-key、同步/流式、参数保留、防伪、key缺失、trace/header合并、并发隔离、listener一次。
- 装配：单ChatModel bean、custom provider退让、security缺失、eval依赖树。
- 集成：PG首建/重启/升级，UI，spend API，Redis cache hit/TTL/跨tenant，virtual-key预算/RPM/TPM。
- failover：主成功、主停备成功、双停有界失败、恢复。
- OTel：LiteLLM callback、Java同trace、敏感内容不采集。
- 回归命令：

```bash
mvn -pl platform-gateway-client test
mvn -pl conversation-service,agent-service,analytics-service,workflow-service,eval-service,vision-service -am test
mvn test
bash deploy/smoke-failover.sh
```

## 11. 风险、监控、灰度与回滚

### 11.1 主要风险与控制

| 风险 | 控制 |
| --- | --- |
| PG第三方schema升级失败 | 固定镜像；升级前pg_dump/snapshot；预发演练；回滚恢复同版本快照 |
| key泄漏/错租户 | Secret注入；不日志；custom header并发测试；缺key fail-closed；UI内网 |
| cache挤压关键Redis | TTL/namespace/逻辑DB；监控used_memory/evictions/latency；必要时拆物理实例 |
| cache跨tenant错误 | user覆盖；比较cache key/命中；不满足则tenant namespace或禁用相关请求缓存 |
| 双层重试放大 | 收敛Java/LiteLLM retry；总时限与双失败测试 |
| 预算并发超额 | 配max_parallel/TPM/RPM；hard ceiling留安全余量；记录最大超额 |
| OTel阻塞主链路 | Jaeger故障测试；callback异步/超时行为按固定版本冻结；内容采集关闭 |
| app/LiteLLM账不一致 | 定义预期差异；按tenant/day/model趋势告警，不做伪事务 |
| backup Ollama未安装 | 部署前 `ollama pull llama3.1`；readiness/synthetic check；生产可换批准provider |
| rollback到none绕过tenant key限制 | 保持LiteLLM全局hard ceiling；回滚窗口加强edge限流和告警 |

### 11.2 监控

- LiteLLM：readiness、UI/spend管理API、结构化错误、cache hit header、fallback/429/预算拒绝日志。
- PostgreSQL：连接数、慢查询、容量、备份年龄、migration日志。
- Redis：used memory、evictions、命中率、连接数、延迟、`/cache/ping`；同时观察应用rate-limit/token key是否异常消失。
- Java：`/actuator/tokenbudget`、`/actuator/cost`、现有health gateway节点、OTel error spans。
- Jaeger：`service.name=litellm-proxy` span量、错误、延迟；与Java trace ID关联率。
- 对账：按tenant/day/model比较LiteLLM spend趋势与应用estimated cost/token；cache/retry/fallback单独分层。

仓库当前没有Prometheus registry，不能把不存在的 `/actuator/prometheus` 指标写成已可抓取；若要Prometheus另行加依赖。

### 11.3 灰度顺序

1. 预发：固定镜像+空PG+Redis cache+Jaeger+mock全套。
2. 生产阶段0：仅换固定镜像、连PG，应用保持none；校验共享key spend/UI。
3. 阶段1：启用OTel callback，Java tracing仍低采样/按服务开启。
4. 阶段2：启用Redis cache，先低风险模型/流量，观察命中和内存。
5. 阶段3：启用正式fallback，做受控主provider故障。
6. 阶段4：单服务/测试tenant切user。
7. 阶段5：预创建virtual key，1%租户→10%→50%→100%，每档至少观察一个预算窗口或批准的缩短窗口。

### 11.4 回滚

- 应用归因：先改回user或none并滚动发布；virtual-key硬限额丢失时由全局budget和edge限流兜底。不要删除key。
- cache：配置关闭cache并重启LiteLLM；保留Redis数据等TTL/按namespace安全清理，禁止对共享Redis做全库FLUSH。
- fallback：删除/关闭fallback配置并重启，恢复单主模型；不改Java。
- OTel：移除callback或endpoint，Java tracking改false；不影响业务。
- DB：若只是业务回滚，可让旧无DB配置运行并保留PG卷；若版本回滚，恢复旧版本对应快照再启旧镜像，不假设schema向后兼容。
- Compose不得 `down -v` 主栈；只有 `llm-failover-smoke` test project trap可删自己的卷。

## 12. 最终验收清单

### 代码与兼容

- [ ] LiteLLM镜像固定tag/digest，不再使用浮动main-stable。
- [ ] `tenant-attribution` 默认none；非法值启动失败。
- [ ] 同步/流式/deterministic/指定模型/cascade全覆盖。
- [ ] 只有一个全局ChatModel bean，listeners不重复。
- [ ] eval未传递引入platform-security自动配置。

### 身份与安全

- [ ] user不可伪造，统一用tenantId。
- [ ] virtual-key两租户header、spend、预算互相隔离。
- [ ] key缺失/撤销fail-closed且不触达provider。
- [ ] git/log/trace/Actuator无key、DB/UI密码和prompt内容。
- [ ] `/ui`仅受控网络访问，生产凭据无开发默认。

### 网关能力

- [ ] PG空库首建、重启持久、备份恢复、版本升级演练通过。
- [ ] spend/UI/key管理可用。
- [ ] budget/RPM/TPM/max parallel硬拒绝有效，阈值由业务确认。
- [ ] Redis cache hit、TTL、隔离、故障行为通过。
- [ ] 主成功、主故障备成功、双故障有界失败通过。
- [ ] 备用模型通过普通对话、工具调用、JSON schema、流式与上下文边界的代表性兼容测试；不把ping成功当能力等价。
- [ ] virtual-key model allowlist不会阻断fallback目标。
- [ ] 备用Ollama前置或生产替代provider已落实。

### 可观测与双轨

- [ ] LiteLLM OTel span到Jaeger；Java开启时同trace ID。
- [ ] 请求线程与已知异步LLM路径分别记录trace关联结果；异步上下文缺失被明确监控/列入后续，不伪报全链路连续。
- [ ] OTel故障不长期阻塞chat。
- [ ] platform-metering未被误改为财务账/硬预算。
- [ ] cache/retry/fallback/stream取消的双轨差异有基线和告警。

### 交付

- [ ] gateway-client目标测试、六服务回归、全仓测试、冒烟均记录结果。
- [ ] deploy/observability/cost/operations文档和runbook更新。
- [ ] 灰度与每一步回滚均演练，主栈卷不会被测试脚本删除。

## 13. 实施前必须关闭的“待验证”

1. 固定LiteLLM版本/digest及其DB migration、UI、budget错误码。
2. 该版本fallback最终使用 `litellm_settings` 还是 `router_settings`；只能保留实测一种。
3. Redis `db`、namespace、TTL和auth cache实际配置回显。
4. cache key是否包含user，跨tenant测试不通过时的namespace修正。
5. retry/fallback总时限与Java maxRetries的最终组合。
6. LiteLLM OTel callback镜像依赖、endpoint格式和Jaeger trace关联。
7. 生产global/key budget、TPM/RPM/max parallel具体值。
8. DeepSeek主模型与Ollama备用在工具/结构化输出/流式能力上的可接受降级边界，以及virtual-key fallback授权规则。
9. agent/workflow等异步执行器上的Micrometer trace context是否实际传播；未传播时接受新root还是另立后续改造。

任何一项未验证都可以合并代码但不得直接宣称生产验收完成。

## 14. 跨模型复核记录（Claude，2026-07-17）

对照真实仓库与 langchain4j 1.13.1 字节码逐条核验，结论：**无事实性错误**。已核实项：

- `ChatModel.chat(ChatRequest, ChatRequestOptions)` 存在，且单参 `chat(ChatRequest)` 汇入两参版本（`ChatRequestOptions.EMPTY`），listener 边界（onRequest→doChat→onResponse）确在两参 default 方法内——wrapper 只覆盖两参/三参重载即可全覆盖且 listener 不重复；
- 参数合并顺序为 `defaultRequestParameters().overrideWith(request.parameters())`，请求级 `user` 胜出，per-request 归因成立；
- `ChatRequest.toBuilder()`、`OpenAiChatRequestParameters.Builder.overrideWith(...)/user(...)`、同步/流式 builder 的 `customHeaders(Supplier)` 均存在；
- eval-service 仅依赖 gateway-client 不依赖 platform-security（optional 依赖设计必要）；`EdgeRateLimitFilter`、`OtelChatModelListener`、`CascadeChatModelFactory` 经 gateway factory、Jaeger 1.57 见 `docs/平台工程/observability-guide.md:122`、failover 冒烟基建均属实。

补充显式待验证项（并入 §13）：

- §13.3 中 `enable_redis_auth_cache` 该**键名本身**未经固定镜像验证，可能实际为其它配置名（如 `user_api_key_cache_ttl` 一族）；以固定镜像文档/回显为准，键名不符时按实际调整。
- "custom headers Supplier 能覆盖静态 Authorization" 由 Codex 标注已核实，但未经本仓库独立字节码/运行验证——阶段二单测必须首先验证该契约，失败则按 §6 第 4 条切方案 B 的固定 delegate 池回退路径。

## 15. 参考

- 仓库分析：`02-codebase-analysis.md`
- 候选方案：`03-solution-a.md` 至 `06-solution-d.md`
- 风险与评分：`comparison.md`
- 详细测试：`test-plan.md`
- LiteLLM官方文档：[Virtual Keys](https://docs.litellm.ai/docs/proxy/virtual_keys)、[Budgets](https://docs.litellm.ai/docs/proxy/users)、[Caching](https://docs.litellm.ai/docs/proxy/caching)、[Fallbacks](https://docs.litellm.ai/docs/proxy/reliability)、[Admin UI](https://docs.litellm.ai/docs/proxy/ui)、[OpenTelemetry](https://docs.litellm.ai/docs/observability/opentelemetry_integration)
