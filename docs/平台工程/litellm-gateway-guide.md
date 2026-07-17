# LiteLLM 网关能力指南（spend 记账 / virtual key / 预算硬保底 / 缓存 / failover / OTel）

> 2026-07 起 LiteLLM 不再只是"无状态 provider 路由器"：接入自带 Postgres 后，spend 记账、
> 管理 UI、per-tenant virtual key、美元预算与 TPM/RPM 硬限流、Redis 响应缓存、正式 fallback、
> OTel span 全部可用。**所有新能力默认关或对现状零影响**——`tenant-attribution` 默认 `none`、
> Java 追踪默认关、fallback 只在主 provider 故障时介入。
>
> 规划与实施记录：`docs/plans/litellm-integration-0717-1006/`（FINAL_PLAN + 进度）。

## 1. 架构与双轨分工

```
Java 服务(6个: conversation/workflow/analytics/knowledge/agent/vision)
  └─ platform-gateway-client（唯一 ChatModel Bean）
       └─ TenantAwareChatModel  ── user=tenantId（USER/VIRTUAL_KEY 档）
            └─ OpenAiChatModel  ── customHeaders(Supplier)：per-request Authorization + traceparent
                 └─ LiteLLM :4000 ── 路由/failover/缓存/spend/预算 ──▶ deepseek / ollama / ...
                      ├─ litellm-postgres（spend、virtual key、team；LiteLLM 仅支持 PG，独立于业务 MySQL）
                      ├─ redis（响应缓存，namespace litellm.cache）
                      └─ jaeger :16686（LiteLLM 自身 OTel span）
```

三层各管一段，**不建伪强一致账**：

| 层 | 职责 | 硬拒绝 | 定位 |
| --- | --- | --- | --- |
| LiteLLM + PG | provider 边界 spend；key/user 预算；TPM/RPM | 是 | provider spend 运营权威（仍需与云商账单对账） |
| platform-metering + Redis | 租户 token 日预算、应用价目 USD 估算、`/actuator/{tokenbudget,cost}` | 是（token 超额） | 业务观测账（见 [成本归因指南](cost-attribution.md)） |
| edge 限流 | API 入口 (tenant, endpoint) QPM | 是 | 流量保护 |

两边的数在 cache 命中 / retry / fallback / 流式取消场景下**预期不逐请求相等**——按 tenant/day/model
看趋势对账，不要当 bug 修。

## 2. 快速开始

```bash
# 起栈（新增 litellm-postgres / jaeger 会自动拉起；镜像已固定 v1.74.3-stable）
docker compose -f deploy/docker-compose.yml up -d litellm

# 管理 UI（本地开发默认凭据 admin / litellm-ui-dev，生产必须换）
open http://localhost:4000/ui
# Jaeger UI
open http://localhost:16686
```

UI 里能看到：每次请求用了哪个模型、输入/输出 token、按内置价目算出的费用（deepseek-chat
有价；ollama 记 0）、按 key/end-user 聚合的 spend、预算与限流配置。

**镜像升级流程**（固定 tag 的意义）：先 `docker run --rm -v langchain4j-platform_litellm-postgres-data:/d
alpine tar czf - /d > litellm-pg-backup.tgz` 快照卷（或 `pg_dump`），再改 `LITELLM_IMAGE_TAG`
起新版本让 Prisma 自动 migrate；回滚 = 恢复快照 + 旧 tag（**不假设 schema 向后兼容**）。

## 3. 租户归因三档（`platform.gateway.tenant-attribution`）

| 档 | 请求体 `user` | Authorization | LiteLLM 侧得到什么 |
| --- | --- | --- | --- |
| `none`（默认） | 不动 | 共享 master key | 与接入前逐字一致，看不到租户 |
| `user` | 强制=可信 tenantId | 共享 master key | spend 按 end-user（=租户）归集；可配 per-customer 预算 |
| `virtual-key` | 强制=tenantId | **该租户的 virtual key** | key 级预算/TPM/RPM/模型白名单硬保底 |

```bash
# user 档（整栈）
PLATFORM_GATEWAY_TENANT_ATTRIBUTION=user docker compose -f deploy/docker-compose.yml up -d

# virtual-key 档：另需给每个发起 LLM 调用的服务注入租户 key（Secret/env，不进 git）
PLATFORM_GATEWAY_TENANT_ATTRIBUTION=virtual-key \
PLATFORM_GATEWAY_TENANT_VIRTUAL_KEYS_TENANTA=sk-... \
docker compose -f deploy/docker-compose.yml up -d
```

语义要点（实现见 `platform-gateway-client` 的 `tenant/` 包）：

- **user 不可伪造**：调用方传入的 `user` 一律被 `TenantContext` 还原出的 tenantId 覆盖；
  身份取自内部 JWT，经 `TenantIdentityProvider` SPI 解耦（eval-service 无 security 依赖时
  归 `anonymous` 并启动告警）。
- **virtual-key fail-closed**：当前租户查不到 key（`platform.gateway.tenant-virtual-keys.<tenant>`
  空/缺失）→ 调用 provider 前抛 `TenantVirtualKeyMissingException`，**绝不回退 master key**。
- 覆盖全部出口：同步 / 流式 / `buildDeterministic`（judge）/ 指定模型 / cascade cheap·strong·rater。
- 非法档位值 → Spring 绑定失败 → 启动失败（宁可起不来，不带错误归因偷跑）。
- key 不进 `@ConfigurationProperties` Map（避免 configprops 暴露）、不进日志与异常消息。

## 4. virtual key 签发 / 预算 / 轮换 runbook

```bash
MASTER=sk-litellm-master   # 生产从 Secret 取

# 签发（alias 约定：<tenant>-v<n>，轮换时 n+1）
curl -s -X POST http://localhost:4000/key/generate \
  -H "Authorization: Bearer $MASTER" -H "Content-Type: application/json" \
  -d '{"key_alias":"tenantA-v1","max_budget":10.0,"budget_duration":"30d",
       "tpm_limit":100000,"rpm_limit":600,"metadata":{"tenant":"tenantA"}}'
# → {"key":"sk-...","...} 把 key 经 Secret 注入对应服务的
#    PLATFORM_GATEWAY_TENANT_VIRTUAL_KEYS_TENANTA

# 查 spend / 预算余量
curl -s "http://localhost:4000/key/info?key=sk-..." -H "Authorization: Bearer $MASTER"

# 轮换：先签 <tenant>-v2 → 滚动更新服务 env → 观察旧 key spend 归零后 /key/delete 旧 key。
# 回收（立即失效）：
curl -s -X POST http://localhost:4000/key/delete \
  -H "Authorization: Bearer $MASTER" -H "Content-Type: application/json" \
  -d '{"keys":["sk-old..."]}'
```

注意：

- 生产预算/TPM/RPM 数值由业务/运维确认后配置，不要用演示值上线；LiteLLM 硬阈值应**高于**
  应用层告警线（platform-metering 的 token 预算先响，LiteLLM 兜底）。
- 若给 key 配了 `models` 白名单，必须同时允许 `chat-default` 与 `chat-default-fallback`，
  否则主故障时 fallback 会被授权层拦掉（冒烟第 6 步能暴露此错配）。
- 预算拒绝是 OpenAI 兼容 4xx（业务侧走既有异常映射）；key 缺失是应用配置错误（fail-closed，
  不触达 provider）——两者日志特征不同，排障先分清。

## 5. 响应缓存（Redis）

`deploy/litellm/config.yaml` 里 `litellm_settings.cache: true`，参数：ttl 600s、
namespace `litellm.cache`（与应用侧限流/token 预算 key 区隔）。复用栈内 redis 容器。

- 验证：同一 prompt 连打两次，第二次毫秒级返回；`curl /cache/ping -H "Authorization: Bearer $MASTER"`。
- 运维红线：**禁止对共享 Redis `FLUSHALL/FLUSHDB`**——限流/预算/语义缓存/文档注册表同库。
  按 namespace `SCAN`+`DEL` 或等 TTL。
- 回滚：config 里 `cache: false` + 重启 litellm 容器即可，数据等 TTL 自然过期。

## 6. 正式 failover

`chat-default`（DeepSeek）→ `chat-default-fallback`（本机 Ollama `llama3.1`）。

- 前置：宿主机 `ollama pull llama3.1` 且 Ollama 在 :11434（同 nomic embedding 的前置方式）。
- **能力不等价告示**：llama3.1 在工具调用/JSON schema/上下文窗上弱于 deepseek-chat，
  只作故障降级，不承诺等价——重要链路灰度前先跑代表性用例。
- 视觉（vision-default）刻意无 fallback：没有第二个可用视觉 provider，不虚构。
- 回滚：删掉 config.yaml 的 `fallbacks` 块 + 重启 litellm。
- 验证：`bash deploy/smoke-failover.sh`（8 步冒烟，见 §8）。

## 7. OTel：Java 与 LiteLLM 同 trace

- LiteLLM 侧常开：`callbacks: ["otel"]` → span 直达 Jaeger（`service.name=litellm-proxy`）。
- Java 侧默认关：`MANAGEMENT_TRACING_ENABLED=true` 开启后，gateway-client 出站自动带
  `traceparent`（`GatewayRequestHeadersSupplier` 注入），LiteLLM span 与 Java GenAI span
  同 trace ID，Jaeger UI 一条链看穿"应用 → 网关 → provider"。
- 已知边界：后台线程（agent worker 等）若未携带 trace 上下文，LiteLLM 产生新 root trace——
  监控关联率即可，本轮不改 executor（见 FINAL_PLAN §6.1）。
- 回滚：Java 侧关 env；LiteLLM 侧删 `callbacks` 行。互不影响业务。

## 8. 冒烟与测试

```bash
# 网关能力冒烟（TEST-ONLY 隔离栈 :4010/:16690，8 步断言，零云 key 依赖，自动清理）
bash deploy/smoke-failover.sh
# 覆盖：cache ping → virtual key 签发 → spend 落 PG → 预算硬拒绝(4xx) →
#       spend 跨重启存活 → failover → Jaeger 查 litellm-proxy trace → 双上游全挂有界失败

# Java 侧
mvn -pl platform-gateway-client test        # 33 用例：三档×同步/流式、HTTP 契约、并发隔离、装配
```

`GatewayTenantAttributionHttpContractTest` 是关键契约测试：从真实 HTTP 报文断言
`customHeaders(Supplier)` 每请求求值且**覆盖**静态 Authorization（master key 不触网）。
若未来升级 langchain4j 后该测试失败，说明 header 合并语义变了——按 FINAL_PLAN §6
切"per-tenant 固定 delegate 池"（方案 B）路径，勿带病启用 virtual-key。

## 9. 回滚总表

| 能力 | 回滚动作 | 影响 |
| --- | --- | --- |
| 归因 | env 改回 `none` 滚动重启 | LiteLLM 看不到租户；全局预算+edge 限流兜底；**别删 key** |
| 缓存 | config `cache: false` + 重启 litellm | 无；数据等 TTL |
| fallback | 删 `fallbacks` 块 + 重启 | 主故障时恢复直接失败 |
| OTel | 删 `callbacks` / Java env 关 | 无 |
| PG | 保留卷停用 DATABASE_URL 即回无状态模式；版本回滚需恢复对应快照 | spend/key 冻结 |

主栈**永远不要** `docker compose down -v`（会清 mysql/qdrant/redis/es/litellm-pg 全部数据卷）；
只有 `llm-failover-smoke` 测试 project 的 trap 会清自己的匿名卷。
