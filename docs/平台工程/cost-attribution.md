# 成本归因与 Token 预算（platform-metering）

本指南面向要给平台加上「谁烧了多少 token / 多少钱」可观测口径的开发者。能力全部来自共享库
`platform-metering`，通过 `ChatModelListener` SPI 挂进**每个发起 LLM 调用的服务**的 chat 链路，
按租户（`TenantContext`）做**日累加**。它有两条互补的计量维度：

- **Token 预算**（`app.token-budget.*`，**默认开**）—— 把 input+output token 一视同仁地按租户日累加，
  作为限额/配额的计量基础。
- **USD 成本归因**（`app.cost.*`，**默认关**）—— 用 model 单价把 token 翻成美元，补 token 预算「不分模型贵贱」
  的短板，让 `model-cascade`（成本路由）、`semantic-cache`（0-token 短路）、Anthropic prompt caching
  这几条「降本」叙事第一次有了 `$` 口径的收口。

两者都**默认落 Redis**（`store=redis`）。这不是可选优化，而是**水平扩容下的正确性前提**：多 pod 部署时
进程内计数各算各的，同一租户的日额度会被放大到 pod 数倍 —— 详见下文「为什么默认 Redis」。

> **暴露位置**：token 预算/成本是**管理面（actuator）**指标，挂在实际跑 LLM 的服务上并在其自身端口暴露，
> **不经边缘网关**（网关 `:8080` 只暴露 `health,info,gateway`）。当前平台里三处装配并 expose 了
> `tokenbudget`/`cost`：`conversation-service`（`:8081`）、`agent-service`（`:8085`）、`vision-service`（`:8090`）。
> 下文的 `/actuator/**` curl 均直连对应服务端口；业务 `/chat`、`/agent/**` 请求仍照常走网关 `:8080` + `X-Api-Key`。
> Redis 后端下，随便命中哪个服务/哪个 pod 的 `/actuator/tokenbudget`，读到的都是**同一份共享计数**。

---

## 1. 组件与数据流

```text
业务请求（经网关 :8080）─▶ conversation/agent/vision 服务
        └─ AiServices 调 ChatModel（GatewayChatModelFactory 注入的全局 bean）
             └─ 每个 ChatModelListener.onResponse(tokenUsage) 回调：
                 ├─ TokenBudgetChatModelListener  ─▶ tracker.consume(tenant, in+out)   // 默认开
                 └─ CostChatModelListener         ─▶ CostCalculator → USD              // 默认关
                       ├─ CostTracker.record(tenant, usd)   （per-tenant 日累加）
                       └─ Micrometer gen_ai.client.cost.usd （tag=model/provider，无 tenant）

读取快照（直连服务端口，管理面）：
  GET /actuator/tokenbudget ─▶ TokenBudgetTracker.snapshotAll()  // {tenant: {used,budget,day}}
  GET /actuator/cost        ─▶ CostTracker.snapshotAll()         // {tenant: {usd,currency,day}}
```

三点设计约束：

- **LLM 藏在 listener 背后，零侵入业务代码**。listener 声明成 Bean，由 `platform-gateway-client`
  的 chat 装配（`List<ChatModelListener>` 构造注入）自动灌进每个 chat builder —— 加计量维度**不改任何 controller/service**，
  和 logging/metrics listener 同一收集机制。
- **租户归属正确到子线程**。tenant 从 `TenantContext`（内部 JWT 还原出的 ThreadLocal）取；
  `MdcCopyingTaskDecorator` 已把 `TenantContext` 透传到 multi-agent 的 worker 子线程，
  所以并行 worker 发起的 LLM 调用也能正确记到发起租户名下。
- **失败调用不计量**。`onError` 不扣 budget、不计成本 —— 与「按成功消耗计费」的常见 SaaS 习惯一致。

---

## 2. Token 预算（默认开）

`TokenBudgetProperties`（`app.token-budget.*`）—— per-tenant 日 token 计量，覆盖 chat / extract /
multi-agent / reflexive / eval 等触发 LLM 调用的入口（`/rag/ingest` 走 embedding model，暂不计入，与成本口径一致）。

它跟限流（rate-limit）是两个维度：**限流挡突发 QPS，token 预算控当日总量**。一个租户可以 60 QPM 不超限，
但如果每次都让 multi-agent 烧 5k token，一天下来仍会爆账单 —— token 预算就是那条按「天」收口的账。

**计量口径**：MVP 不区分 model/provider 定价，所有 token（input + output 一视同仁）累加。
按「钱」区分贵贱的口径由下一节 USD 成本归因承担。

```yaml
# conversation-service/agent-service/vision-service 的 application.yml（已内置，下为等价展开）
app:
  token-budget:
    enabled: true                       # 默认开；=false 则不装配 listener，零回调开销
    store: ${TOKEN_BUDGET_STORE:redis}  # 默认 redis（多 pod 共享计数）；in-memory 回退单 JVM
    timezone: Asia/Shanghai             # 日历日重置依据的时区（成本归因复用同一基准）
    daily-tokens:
      default: 100000                   # 每租户每天 10 万 token
      overrides:
        tenantA: 500000                 # 大客户单独提配额
    anonymous-multiplier: 0.05          # anonymous（未鉴权）只享受 default 的 5%
    redis:
      key-prefix: "token:budget:"       # 实际 key = <prefix><date>:<tenantId>
```

**读当日快照**（直连服务端口，管理面不经网关）：

```bash
# 先经网关打几次 chat 产生 token 用量
curl -s http://localhost:8080/chat \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"你好"}'

# 再直连服务读 per-tenant 当日 token 快照（Redis 后端下是跨 pod 共享计数）
curl -s http://localhost:8081/actuator/tokenbudget | jq
# → {
#     "acme":      { "used": 1287, "budget": 100000, "day": "2026-07-09" },
#     "anonymous": { "used": 42,   "budget": 5000,   "day": "2026-07-09" }
#   }
```

**关于「拦截」**：`TokenBudgetTracker` 接口已备好 `wouldExceed(tenant)`（预检是否用满）与
`secondsUntilReset()`（次日 0 点倒计时，供 429 的 `Retry-After`）两个钩子，为「超额拦截」预留了完整语义。
**但当前平台只回填计量、尚未接入 pre-check guard filter** —— 即 token 预算目前是**可观测**指标，
不硬拦请求。要落硬拦截，只需加一个前置 filter：预检 `wouldExceed` → 命中则 `429 + Retry-After=secondsUntilReset()`；
Redis 后端已就位，可直接做多 pod 硬预算（拦截兜底当前由限流承担，见 `operations.md`）。

---

## 3. USD 成本归因（默认关）

`CostConfig`（`app.cost.*`，仅 `app.cost.enabled=true` 时整体装配）—— 把 token 用量按 model 单价翻成 **USD**，
per-tenant 日累加 + Micrometer 指标。默认关是因为**本地 ollama 免费、无需核算**；用云 provider
（openai/anthropic/deepseek/gemini）时再开。`enabled=false` 时 calculator/tracker/listener 一律不装配，零开销、零回归。

### 3.1 定价表与换算

`CostProperties`（`app.cost.pricing.<model>`）给每个 model 配四档单价，单位 **USD / 1,000,000 tokens**（云厂商标准口径）：
`input` / `output` / `cache-read` / `cache-write`。model 匹配规则：**精确命中 → 最长前缀命中 → `default`**：

- 精确：`gpt-4o` 命中 `gpt-4o`；
- 最长前缀：配一条 `gpt-4o-mini` 就能匹配 `gpt-4o-mini-2024-07-18`，定价表不必穷举全版本号；
- 兜底 `default`：本地免费模型留 0 —— `CostCalculator` 算出 `totalUsd<=0` 时直接**不打点、不累计**。

`CostCalculator` 是无状态纯函数（确定性单测 `CostCalculatorTest`）。**最关键的细节是 Anthropic prompt caching 的输入拆分**：
`AnthropicTokenUsage.inputTokenCount` **已经包含** cache-read + cache-write 的 token，而这三者单价不同
（cache-read≈0.1×、cache-write≈1.25×、普通 input 1×）。所以要把普通 input 单独拆出来：

```
regularInput = max(0, input − cacheRead − cacheWrite)   // 夹到 ≥0，防脏数据算出负成本
totalUsd = regularInput×inputRate + output×outputRate
         + cacheRead×cacheReadRate + cacheWrite×cacheWriteRate   // 均 ÷1e6
```

非 Anthropic 场景 cacheRead/cacheWrite 传 0，退化成 `input×inputRate + output×outputRate`。
`cache-read`/`cache-write` 未在 yml 配（值 <0）时回退到 `input` 单价。

```yaml
app:
  cost:
    enabled: false                       # 默认关；用云 provider 时置 true
    store: redis                         # 默认 redis（多副本汇总同一份成本账）；in-memory 回退单 JVM
    currency: USD                        # 仅展示用，不参与换算
    default: { input: 0.0, output: 0.0 } # 未命中 model 的兜底（本地免费模型留 0）
    redis: { key-prefix: "cost:usd:" }
    pricing:                             # USD / 1,000,000 tokens
      gpt-4o-mini:      { input: 0.15, output: 0.60 }
      gpt-4o:           { input: 2.50, output: 10.00 }
      claude-haiku-4-5: { input: 1.00, output: 5.00, cache-read: 0.10, cache-write: 1.25 }
      deepseek-chat:    { input: 0.27, output: 1.10 }
```

> **时区复用**：成本累加的日历日重置**复用 `app.token-budget.timezone`**（`Asia/Shanghai`），
> 避免成本面板与 token 面板跨日时刻不一致 —— 两块看板永远在同一时刻翻页。

### 3.2 指标 vs 明细

- **Micrometer**：`gen_ai.client.cost.usd` counter，tag 为 `model`/`provider`，**刻意不带 tenant tag**
  （防 Prometheus label 基数爆炸）。用于跨租户的全局成本趋势。
- **per-tenant 明细**：走 `CostTracker` 的日快照 + `GET /actuator/cost` 端点。用于「哪个租户在烧钱」。

### 3.3 启用并读取

```bash
# 开成本核算（示例：用 property 覆盖 conversation-service 里默认的 app.cost.enabled=false）
mvn -pl conversation-service spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dapp.cost.enabled=true"
#（或整栈 docker compose 起，云 provider 经 LiteLLM 路由）

# 经网关打几次 chat 后，直连服务读 per-tenant 当日累计 USD
curl -s http://localhost:8081/actuator/cost | jq
# → {
#     "acme":      { "usd": 0.00042, "currency": "USD", "day": "2026-07-09" },
#     "anonymous": { "usd": 0.00001, "currency": "USD", "day": "2026-07-09" }
#   }

# Prometheus 里的成本 counter（按 model/provider tag，不带 tenant）
curl -s http://localhost:8081/actuator/prometheus | grep gen_ai_client_cost_usd
```

> 成本是**纯观测**指标（只累计、永不拦截）—— 拦截语义归 token 预算（及未来的 cost-based guard filter）。

---

## 4. 为什么默认 Redis：水平扩容下的正确性

新平台把 token 预算 / 成本 / 限流的默认后端全部定为 **Redis**（对齐 `security` 包的限流、`conversation`
的语义缓存/记忆），这一节说明**为什么这不是可选优化、而是正确性要求**。

### 4.1 进程内计数在多 pod 下是「真 bug」

日 token 预算的进程内实现（`InMemoryTokenBudgetTracker`）每个副本各持一份 `ConcurrentHashMap` 计数。
部署 N 个 pod 时，同一租户的请求被负载均衡打散到各 pod，各算各的 —— **实际配额被放大到 N 倍，限额形同虚设**。
这跟「缓存命中率略低」这类性能问题不同，是**限额语义在水平扩容下直接失效**，属正确性 bug。
成本归因同理：in-memory 后端下每个 pod 只记自己那份，看板要手动跨 pod 加总才是真账。

所以两者的默认后端都是 Redis：

- token 预算 `store: ${TOKEN_BUDGET_STORE:redis}`（conversation yml 显式默认 redis；装配层
  `PlatformMeteringAutoConfiguration` 的 redis bean 亦 `matchIfMissing=true`）；
- 成本 `app.cost.store`：装配层 `CostConfig` 的 redis bean `matchIfMissing=true` —— **属性缺省即取 redis**，
  仅显式 `app.cost.store=in-memory` 才回退单 JVM。

多 pod 验证：起两个实例（不同端口）都指同一 Redis，交替打请求，两边 `/actuator/tokenbudget` 读到
**同一个**累计值（in-memory 后端则各是各的）。

### 4.2 共享范式 `RedisDailyCounters`

「per-tenant 日计数落 Redis」抽成一处纯函数 helper（`RedisDailyCounters`），token 预算（整数 `INCRBY`）
与成本（小数 `INCRBYFLOAT`）复用同一套 key 布局与过期逻辑，两个 tracker 的差别只在累加命令：

| 关注点 | 做法 |
| --- | --- |
| **key 内嵌日期** | `<prefix><date>:<tenantId>`（如 `token:budget:2026-07-09:acme`、`cost:usd:2026-07-09:acme`）。date 在前 → **跨日零成本重置**（新的一天自然换 key、旧 key 到点自动过期，无需定时清理任务）；tenantId 在末段 → `snapshotAll` 能 `SCAN <prefix><today>:*` 枚举并按定长前缀切出 tenantId（tenantId 含 `:` 也不误切） |
| **累加原子** | 走 Lua（`INCRBY`/`INCRBYFLOAT` + `PEXPIREAT` 次日午夜 epoch），一次往返、服务端原子，多 pod 并发不丢更新、不覆盖 |
| **自动过期** | `PEXPIREAT` 设到**配置时区**（`Asia/Shanghai`）的次日 0 点，key 到点自动清，Redis 不堆历史，与内存版跨日归零语义一致 |
| **容错（fail-safe）** | Redis 抖动不拖垮主链路：`consume`/`record` 失败仅告警（漏记比拒服务代价小）；读快照失败按 0（宁可放行不误拒）。与限流「读失败放行」同一取向 |
| **一致性口径** | 沿用内存语义：先（未来的 guard 会）预检、请求跑完再 `consume` 回填 —— **最终一致**，单请求极端情况下可能轻微越额；要严格「预扣」可把预检也并进 Lua（未来项） |

同一范式已在平台复用三处：token 预算、成本、限流（限流走 token-bucket 变体，另见 `operations.md`）。

**直接看 Redis**：

```bash
docker run -d --name redis -p 6379:6379 redis:7   # 本地起 Redis

redis-cli KEYS 'token:budget:*'                    # → token:budget:2026-07-09:acme
redis-cli GET  'token:budget:2026-07-09:acme'      # 当日已用 token
redis-cli PTTL 'token:budget:2026-07-09:acme'      # 距次日午夜的毫秒数
redis-cli KEYS 'cost:usd:*'                        # → cost:usd:2026-07-09:acme（成本开启时）
```

---

## 5. 配置 / 端点速查

**Token 预算**（`platform-metering`，默认**开**；expose 在 conversation:8081 / agent:8085 / vision:8090）

| 配置项（property / ENV） | 默认 | 说明 |
| --- | --- | --- |
| `app.token-budget.enabled` | `true` | 关则不装配 listener，零回调开销 |
| `app.token-budget.store`（`TOKEN_BUDGET_STORE`） | `redis` | `redis` 多 pod 共享计数 / `in-memory` 单 JVM |
| `app.token-budget.timezone` | `Asia/Shanghai` | 日历日重置时区（成本复用此基准） |
| `app.token-budget.daily-tokens.default` | `100000` | 每租户每天 token 额度 |
| `app.token-budget.daily-tokens.overrides.<tenant>` | — | 单租户提额 |
| `app.token-budget.anonymous-multiplier` | `0.05` | anonymous 只享 default 的 5% |
| `app.token-budget.redis.key-prefix` | `token:budget:` | Redis key 前缀 |
| `GET /actuator/tokenbudget` | — | `{tenant: {used,budget,day}}` 快照（直连服务端口） |

**USD 成本归因**（`platform-metering`，默认**关**，需云 provider 时开）

| 配置项（property / ENV） | 默认 | 说明 |
| --- | --- | --- |
| `app.cost.enabled` | `false` | 开则装配 calculator/tracker/listener |
| `app.cost.store` | `redis`* | `redis` 多副本汇总同一份账 / `in-memory` 单 JVM |
| `app.cost.currency` | `USD` | 仅展示，不参与换算 |
| `app.cost.default.{input,output}` | `0.0` | 未命中 model 的兜底单价（本地免费留 0） |
| `app.cost.pricing.<model>.{input,output,cache-read,cache-write}` | — | USD / 1M tokens；匹配：精确 → 最长前缀 → default |
| `app.cost.redis.key-prefix` | `cost:usd:` | Redis key 前缀 |
| Micrometer `gen_ai.client.cost.usd` | — | counter，tag=`model`/`provider`（无 tenant，防基数爆炸） |
| `GET /actuator/cost` | — | `{tenant: {usd,currency,day}}` 快照（直连服务端口） |

\* `app.cost.store` 的绑定字段默认 `in-memory`，但装配层 redis bean `matchIfMissing=true` —— **属性缺省即取 redis**，
仅显式设 `app.cost.store=in-memory` 才回退单 JVM。

> 暴露约定：`tokenbudget`/`cost` 已加进上述三个服务 yml 的 `management.endpoints.web.exposure.include`；
> 边缘网关 `:8080` **不**转发 actuator，请直连服务端口读取。

---

## 6. 未来演进

- **cost-based 硬拦截**（USD/day 硬预算，超额 429）：当前只观测不拦；Redis 后端已就位，仿 token 预算加一个
  guard filter 即可多 pod 硬预算。
- **token 预算 pre-check guard filter**：接口的 `wouldExceed`/`secondsUntilReset` 已备好，接一个前置 filter 即成硬限额。
- **embedding token 成本**：目前只算 chat model，`/rag/ingest` 的 embedding 调用未计入（与 token 预算口径一致）。

相关文档：观测/指标见 `operations.md`，接口总览见 `api-reference.md`，整体架构见 `架构文档.md`，
降本相关能力见 `agent-guide.md`（多 Agent 编排）与 `rag-guide.md`（含 L1 语义缓存）。
