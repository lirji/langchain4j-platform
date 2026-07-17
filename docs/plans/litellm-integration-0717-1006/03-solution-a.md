# 03 — 候选方案 A：请求级透明包装 + 动态 Header（最小闭环）

## 1. 核心思想

在 `GatewayChatModelFactory` 的唯一构造收口点套 `TenantAwareChatModel`/`TenantAwareStreamingChatModel`。wrapper 只负责把可信 `tenantId` 写进请求；底层 OpenAI client 的 `customHeaders(Supplier<...>)` 在每次发送前动态覆盖 virtual-key Authorization，并用 Micrometer `Propagator` 注入当前 trace。LiteLLM 自己连接 PostgreSQL/Redis/Jaeger并完成 hard guard、spend、cache、fallback。

这是“每请求取 ThreadLocal 身份、共享底层 HTTP client”的方案，不为每租户创建 ChatModel。

## 2. 架构与职责

```text
TenantContext (optional)
  -> TenantIdentityProvider
     -> TenantAware(Chat|Streaming)Model
        -> request.parameters.user = tenantId       [USER/VIRTUAL_KEY]
        -> OpenAi(Chat|Streaming)Model
           -> GatewayRequestHeadersSupplier
              -> Authorization: Bearer tenant-key   [VIRTUAL_KEY]
              -> traceparent/tracestate              [tracing enabled]
           -> LiteLLM
              -> PostgreSQL: key/user/team/spend
              -> Redis: response cache + auth cache
              -> router: primary -> fallback
              -> OTel callback -> same Jaeger
```

模块职责：

- gateway-client：可信身份读取、请求字段覆写、virtual-key resolver SPI、trace header 注入；不管理预算数值。
- platform-security：仍只拥有 `TenantContext`；通过 optional adapter 被读取，不反向依赖 gateway-client。
- LiteLLM：provider 路由、缓存、spend、虚拟 key、硬预算、TPM/RPM、fallback、OTel。
- platform-metering：继续 listener 后置计量和 Actuator；不与 LiteLLM DB 双写。
- edge-gateway：继续 API 入口 QPM。

## 3. 核心流程

### 3.1 `none`

1. wrapper 收到 `ChatRequest`。
2. 原请求原样交给 delegate。
3. header supplier 不覆盖 Authorization；静态 `platform.gateway.api-key` 生效。
4. 若 Java tracing 显式开启，只额外传播标准 trace headers；若要求 `none` 字节级完全一致，可让 trace header 逻辑独立受 tracing 开关控制。

### 3.2 `user`

1. `TenantIdentityProvider.currentTenantId()` 返回现有 `TenantContext` 的 tenant。
2. 以 `OpenAiChatRequestParameters.builder().overrideWith(request.parameters()).user(tenantId).build()` 保留原参数并覆盖任何外部 `user`。
3. 仍用共享 gateway key；LiteLLM 以 end-user 口径记账/统一 hard budget。

### 3.3 `virtual-key`

1. 与 `user` 相同写 tenant。
2. HTTP 发送前，header supplier 用相同 tenant 调 `TenantVirtualKeyResolver.resolve`。
3. 命中则 custom header 覆盖静态 Authorization；缺失直接抛出不含 key 的配置异常。
4. LiteLLM 对该 key 的模型白名单、预算、TPM/RPM做硬检查。

## 4. 数据与配置

- PostgreSQL 是新外部状态，应用无新实体/表。
- `platform.gateway.tenant-attribution=none|user|virtual-key`，默认 none。
- `platform.gateway.tenant-virtual-keys.<tenant>` 从 Secret/Environment 解析，不纳入 `GatewayClientProperties.toString` 或日志。
- LiteLLM config 增加 `database_url`、cache、auth cache、callbacks、fallback。
- Redis 用 namespace 和 TTL；建议逻辑 DB 1，是否支持需锁定镜像实测。
- 正式备用模型用仓库已有注释中的 `ollama/llama3.1`，逻辑名 `chat-default-fallback`。

## 5. 改动范围

集中在用户指定的四类范围：

- `deploy/docker-compose.yml`、LiteLLM 主/测试 config、test-only compose；
- `platform-gateway-client` 属性、工厂、auto config、optional dependency 和新 wrapper/SPI；
- `deploy/smoke-failover.sh` 与测试；
- 后续更新运维/可观测/成本文档。

不改业务服务、platform-metering、edge 限流。

## 6. 并发、事务和幂等

- wrapper 自身无状态；每请求从 ThreadLocal 取 tenant，不把 tenant 缓存在共享字段。
- wrapper直接覆盖带 `ChatRequestOptions` 的调用并委托；`listeners()` 可以代理delegate用于自省，但不能让wrapper默认listener边界再跑一次。
- header supplier 返回新 Map，不复用可变 Map，避免并发串 key。
- virtual key 查配置是读操作；同一请求没有 key 创建/更新事务。
- LiteLLM spend 与应用 listener 是两个独立 commit，明确最终可观测而非原子一致。
- failover 内多次 provider 尝试由 LiteLLM 处理；Java 只收到最终结果/错误，不额外实现重放。
- key provisioning 在 UI/API 独立进行；重复创建与 alias 规则由运维 runbook 控制，应用不自动创建 key。

## 7. 扩展性

- `TenantIdentityProvider` 可替换为 Reactor/ScopedValue/消息上下文实现。
- `TenantVirtualKeyResolver` 可替换为 Vault/KMS/Config Server，不改 wrapper。
- header supplier 可继续加入 baggage 或请求 correlation header。
- 未来可给 embedding/其他模型接口做同样包装。

## 8. 成本与弱点

实施成本：中等，新增约 7 个小类和 4 类测试，部署变更中等。

弱点：

- virtual keys 需要分发到每个发起 LLM 调用的应用实例，Secret 生命周期是运维负担。
- 动态 Authorization 依赖 LangChain4j 1.13.1 custom header 后写覆盖行为，升级时需回归。
- trace 接到当前应用请求 span，不保证精确接在 `OtelChatModelListener` 的 chat span 下。
- `user` 模式只能表达同一共享 key 下的 tenant 字段，细粒度差异预算能力弱于 virtual key。
- 复用 Redis 会扩大资源争用故障域。

## 9. 适用结论

最贴合当前仓库“唯一 ChatModel bean + 工厂收口 + listener 横切”的结构，默认 none 回归面最小；适合本次范围，但需把 key 缺失 fail-closed、流式覆盖和 trace header 合并列为强制测试。
