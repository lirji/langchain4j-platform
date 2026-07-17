# 01 — 需求分析（requirements-analyst）

## 1. 目标陈述

本次改造要把当前“仅作为 OpenAI 兼容转发层”的 LiteLLM，升级为可运营的 AI 网关，同时不改变默认应用行为：

1. LiteLLM 连接独立 PostgreSQL，启用持久化 spend 账、虚拟 key 管理和 `/ui` 管理界面。
2. `platform-gateway-client` 增加租户归因，配置 `platform.gateway.tenant-attribution` 支持 `none`、`user`、`virtual-key` 三档，默认 `none`。
3. LiteLLM 的预算与限流承担最终硬保护；现有 `platform-metering` 继续承担应用语义侧的 token/USD 观测，形成双轨而非双写强一致账。
4. LiteLLM 响应缓存落到现有 Compose 的 Redis。
5. 正式配置 `deploy/litellm/config.yaml` 增加备用逻辑模型并真正启用 `fallbacks`。
6. LiteLLM OTel callback 输出到平台已有的 OTLP/Jaeger 链路，并在 Java→LiteLLM 请求上保留 W3C trace 上下文。
7. 用可重复的单元、集成和冒烟测试覆盖同步、流式、归因、缓存、spend、预算/限流、failover 与 OTel。

## 2. 已确认业务规则

### 2.1 租户归因三档

| 档位 | 出站身份 | LiteLLM 能力 | 兼容性规则 |
| --- | --- | --- | --- |
| `none` | 沿用 `platform.gateway.api-key`；不注入租户 `user` | 只能依赖全局/共享 key 口径 | 默认值；请求体、鉴权头和现有调用结果必须与当前一致 |
| `user` | 沿用共享 key；OpenAI chat 请求顶层 `user` 强制写为 `tenantId` | end-user spend/统一 end-user hard budget（具体能力受 LiteLLM 版本约束） | 调用方自带 `user` 必须被覆盖，不能允许租户伪造归因 |
| `virtual-key` | `user=tenantId`，且 `Authorization` 使用该租户的 LiteLLM virtual key | 每 key/用户/团队 spend、预算、TPM/RPM、模型白名单 | 找不到租户 key 时请求前失败；严禁静默退回 master key |

补充规则：

- 租户维度采用现有 `TenantContext.Tenant.tenantId()`，不是 `userId()`；仓库现有限流、token 预算和成本也均按 tenant 归集。
- 未建立上下文时现有安全模块返回租户 `anonymous`；`user` 档可归到 `anonymous`，`virtual-key` 档必须显式配置 anonymous key，否则拒绝。
- 同步、流式、`buildDeterministic()`、指定模型构造和 cascade 内部 cheap/strong/rater 都必须使用同一归因规则。
- `virtual-key` secret 不写入仓库、不打印、不进入异常消息；从运行时配置/Secret 注入，并允许通过 `TenantVirtualKeyResolver` SPI 替换来源。
- 默认 `none` 时不要求 `platform-security` 出现在运行时，避免破坏目前未直接依赖 security 的 `eval-service`。

### 2.2 双轨计量与硬保底

| 轨道 | 权威范围 | 拒绝行为 | 现有事实/目标 |
| --- | --- | --- | --- |
| LiteLLM + PostgreSQL | 实际路由后的 provider spend、key/user/team 归因、网关 TPM/RPM、美元预算 | 达到配置阈值后在 LLM 边界硬拒绝 | 目标新增；作为资金风险最终保险丝 |
| `platform-metering` + Redis | 应用识别的 tenant、成功响应 token、配置价目估算 USD、Actuator 快照 | 本任务不新增硬拒绝 | 现有 `TokenBudgetChatModelListener`/`CostChatModelListener` 继续工作；不能称为财务账 |
| `EdgeRateLimitFilter` + Redis | 业务入口 `(tenant, endpoint family)` QPM | 429 | 已有；控制 API 突发，不等价于 LiteLLM TPM/RPM |

规则：

- 三套计数不做分布式事务，也不互相双写；通过可观测对账发现偏差。
- LiteLLM hard limit 是高于正常业务阈值的“保险丝”，应用层承担展示、运营告警和业务口径；避免两个独立计数器在边界附近随机先后拒绝。
- 当前 `platform-metering` 的 token budget **只在成功响应后 `consume`**，仓库没有 `TokenBudgetGuardFilter` 实现；本任务不把它误述为硬预算，也不顺带实现该 filter。
- 缓存命中、重试、fallback 和失败尝试会让两轨数值产生合理差异；上线前要记录基线，而非要求逐请求相等。

### 2.3 PostgreSQL 与管理 UI

- PostgreSQL 仅供 LiteLLM 管理面/spend 使用，与现有 MySQL 业务库隔离。
- Schema 由锁定版本的 LiteLLM 镜像管理；本项目不手写、复制或依赖其内部表结构。
- 首次启用从切换时刻开始记账，不把 Redis 日计数伪造为历史 LiteLLM spend。
- `/ui` 必须需要 master key 与数据库；UI 用户名/密码、master key、数据库密码均来自环境变量/Secret。
- Compose 可给出仅用于本地开发的默认值，但文档必须明确生产禁止沿用。
- LiteLLM 镜像必须从浮动 `main-stable` 收敛到经验证的固定版本或 digest；具体版本号在实施时标记“待验证”，不能在规划阶段猜测。

### 2.4 Redis 缓存

- 复用 `deploy/docker-compose.yml` 中现有 `redis:7-alpine`，不新增第二个 Redis 容器。
- 使用独立 namespace，优先使用独立逻辑 DB（当前应用默认 DB 0，LiteLLM 建议 DB 1；该配置键需随锁定镜像验证）。
- 设置有限 TTL，防止缓存无限增长；缓存 Redis 故障不得被误认为 PostgreSQL spend 丢失。
- 必须验证相同请求二次命中、`/cache/ping`、跨租户隔离/归因以及缓存命中时两轨计量口径。
- 共享实例带来内存争抢和故障域扩大；生产若无法给缓存设资源预算，应允许后来拆分物理实例，但“本次 Compose 复用现有 Redis”不变。

### 2.5 Failover

- 正式 `config.yaml` 为 `chat-default` 增加不同逻辑名 `chat-default-fallback`，并配置有序 fallback；不得使用 `chat-default -> chat-default` 的自环。
- 基于仓库已有注释和宿主机连通配置，候选备用为 `ollama/llama3.1`；运行前需 `ollama pull llama3.1`。这是一项部署前置，不得隐瞒。
- `vision-default` 的视觉 fallback 不在本次目标内，因为仓库没有第二个已确认可用的视觉 provider/model。
- 保留 `config.failover.yaml` + mock-a/mock-b 的无云凭证确定性测试；正式配置的结构也要有静态校验。
- 重试、请求超时、fallback 顺序要有总延迟上界；Java `maxRetries=3` 与 LiteLLM `num_retries` 叠加会放大延迟，必须通过测试和配置收敛。

### 2.6 OTel

- LiteLLM 配置 `callbacks: ["otel"]`，输出到与 Java 服务相同的 OTLP HTTP 接收端；本地 Compose 复用仓库文档已采用的 Jaeger all-in-one 方案。
- Java 侧追踪仍默认关闭；显式启用时，gateway-client 通过 Micrometer `Propagator` 把当前 W3C 上下文注入 LangChain4j 自定义 HTTP headers。
- `none`/`user`/`virtual-key` 的动态 header 生成必须合并，不能因写 `Authorization` 丢掉 `traceparent`/`tracestate`。
- 当前 `OtelChatModelListener` 创建的 chat client span 没有把 scope 保持到 HTTP 调用；最终方案保证 LiteLLM 至少连接到当前应用请求 trace。是否能精确成为该 chat span 的直接子节点是已知弱点和后续项，不能虚假验收。
- prompt/response 内容默认不采集，避免敏感信息进入 trace；只验模型、耗时、token、租户/虚拟 key 元数据等低敏属性。

## 3. 边界条件

- `TenantContext` 在异步线程缺失：`user` 归 anonymous；`virtual-key` 因缺 key 拒绝，借此暴露上下文传播遗漏。
- 同一租户高并发跨多个服务：virtual key 必须共用 LiteLLM 侧预算/TPM/RPM；应用 Redis 计数仍按原子 Lua 累加。
- virtual key 轮换：先在 LiteLLM 允许新旧 key 的过渡窗口，再滚动更新应用 Secret，最后撤旧；任何时点不得回退 master key。
- PostgreSQL 暂不可用：启动/管理功能行为随锁定 LiteLLM 版本“待验证”；生产期望 readiness 失败而不是无账运行，需用故障测试确认。
- Redis 暂不可用：缓存失效是否降级为直连 provider、virtual-key auth cache 是否回源 PostgreSQL，需按镜像版本验证；不能影响数据库账的定义。
- fallback 两端都失败：最终返回标准 OpenAI 兼容错误；禁止无限循环或吞错返回伪成功。
- 缓存 + fallback：由备用模型生成的结果再次命中时，应能从响应头/OTel/spend 解释，且不再次请求主/备 provider。
- 流式中途断开：LiteLLM spend、应用 listener token usage 可能不同；记录为对账差异场景。
- 一个请求包含调用方 `user`：归因 wrapper 必须覆盖，不合并也不信任。

## 4. 非目标

- 不替换现有 edge API key、内部 JWT 或 Casdoor/SpiceDB 身份体系。
- 不把 LiteLLM PostgreSQL 变成平台业务数据库，也不让 Java 服务访问 LiteLLM 内部表。
- 不在本任务同步 LiteLLM spend 回 MySQL/Redis，不建立财务结算、发票或账单 API。
- 不实现 `platform-metering` 的 token pre-check guard、预扣/退款事务。
- 不改 embedding、ASR、TTS 的归因链；`platform-gateway-client` 当前覆盖的是 ChatModel/StreamingChatModel。
- 不实现 LiteLLM 管理 UI 的公网暴露、企业 SSO/RBAC；本地 UI 仅管理面验证，生产应置于受控网络。
- 不为 `vision-default` 臆造备用视觉模型。
- 不承诺缓存适合所有提示；敏感/强实时接口的 per-request no-cache 策略属于后续业务评估。

## 5. 歧义与待验证项

| 项目 | 当前结论 | 实施前动作 |
| --- | --- | --- |
| LiteLLM 镜像版本 | 当前为浮动 `main-stable`，无法据此锁定 schema/API/状态码 | 选择固定 tag/digest，在预发跑迁移、UI、cache、budget、OTel 全套测试 |
| 数据库迁移机制 | 仓库无 LiteLLM schema/migration 文件 | 以固定镜像启动日志和空库实测为准；备份后再升级 |
| fallback 配置归属 | 仓库测试配置使用 `litellm_settings.fallbacks`；当前官方文档也展示 `router_settings.fallbacks` | 对固定镜像执行配置加载测试，选择唯一已验证写法，不双配 |
| Redis `db` 参数 | Redis client 通常支持，仓库未实测 LiteLLM config 绑定 | 用 `/cache/ping` 检查实际 DB/namespace；不支持则保留 namespace 并标注共享 DB |
| user/cache-key 关系 | 官方示例含 `user`，但未在仓库验证是否必然进入 cache key | 冒烟比较不同 tenant 的 cache key/命中行为；不满足隔离时注入 tenant namespace 或默认关闭跨租户缓存 |
| OTel v1/v2 | 官方已有 opt-in v2；仓库现有 Java span 口径更接近 v1 | 本次固定 v1 `otel` callback；v2 单独评估，不在上线时顺带切换 |
| spend 对失败/重试的记账 | 版本和 provider 行为相关 | mock 主失败/备成功、双失败、流式取消逐项比对 spend logs |
| hard limit 状态码 | 官方示例在不同限制下出现 400/401/429 | 以结构化 error type/code 断言为主，固定镜像后再冻结 HTTP 状态码契约 |
| 备用模型可用性 | `ollama/llama3.1` 来自现有主配置注释，但宿主未必已拉取 | 部署检查和 runbook 明确 `ollama pull llama3.1`，生产可换成已批准 provider |

## 6. 验收标准

### 功能验收

- PostgreSQL 空库启动后 LiteLLM readiness 正常，重启后 key/spend 仍存在；`/ui` 可登录并查看管理数据。
- `none` 默认下现有主 key、请求体和所有 ChatModel 构造路径无行为回归。
- `user` 下 mock 上游收到 `user=<tenantId>`；调用方伪造值被覆盖；同步/流式均成立。
- `virtual-key` 下两个租户发送不同 Bearer key；缺映射、anonymous 未配置、resolver 异常均在调用 provider 前失败且不泄露 secret。
- 同一请求第二次由 Redis cache 命中；TTL 后重新请求；跨租户测试证明无错误归因/越权复用。
- virtual key 的预算、TPM/RPM 任一达到阈值后 LiteLLM 硬拒绝，而应用进程仍健康。
- mock-a 停止后 `chat-default` 自动走 mock-b；两个上游都停止时在有界时间内失败。
- LiteLLM OTel span 可在 Jaeger 查询到；Java tracing 开启时双方 trace ID 相同，关闭时 Java 保持当前默认零回归。

### 质量验收

- `mvn -pl platform-gateway-client test` 通过，覆盖三档、同步/流式、参数保留、header 合并、并发 ThreadLocal 隔离、缺 key fail-closed。
- `docker compose config` 与 LiteLLM 配置加载通过；冒烟脚本重复执行可自动清理测试资源，不删除正式命名卷。
- 没有新增明文生产 secret，没有日志输出 Authorization/key/数据库密码。
- 不依赖 LiteLLM 内部表名；数据库升级/回滚文档基于镜像版本和卷快照。
- 仅计划文档阶段不修改任何业务代码；后续实现按 `FINAL_PLAN.md` 执行。

## 7. 官方语义核对来源

以下只用于校验 LiteLLM 外部产品语义，仓库事实仍以 `02-codebase-analysis.md` 为准：

- [Virtual Keys 与 PostgreSQL/spend](https://docs.litellm.ai/docs/proxy/virtual_keys)
- [Budgets 与 Rate Limits](https://docs.litellm.ai/docs/proxy/users)
- [Redis Caching](https://docs.litellm.ai/docs/proxy/caching)
- [Fallbacks](https://docs.litellm.ai/docs/proxy/reliability)
- [Admin UI](https://docs.litellm.ai/docs/proxy/ui)
- [OpenTelemetry callback](https://docs.litellm.ai/docs/observability/opentelemetry_integration)
