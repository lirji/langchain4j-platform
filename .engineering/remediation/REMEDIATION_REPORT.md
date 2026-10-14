# Remediation Report（2026-09-25）

**Final Status：COMPLETE_WITH_LIMITATIONS**：backlog 中全部 FIX_NOW 项已实现并验证。
这里的「限制」指：未提交（用户未要求）；O2 跨语言配额仍需两仓协同；真实 Redis 集成测试需环境开关，CI 默认跳过。

## Execution Intent / Workflow

- Intent：已有项目 + 已有分析 → 验证发现 → 有界整改（EXISTING_PROJECT + PROJECT_REMEDIATION）。
- 路由：`intent-mapper.py` → `PROJECT_REFACTORING` → `project-refactoring`（EXECUTE），见 `EXECUTION_STATE.md` GAP-1。
- 基线：`e8f11cb`，全反应堆 1354 测试全绿。Plan v1（未重规划），State v2。

## Recovered State

复用 `.engineering/analysis`、`.engineering/exploration`、`CODEX_PROGRESS.md`，未重新全仓分析。
O1/O3/O4/O5/O7 与 F1/F2/F5/F6/F7/F10 为 ALREADY_RESOLVED（本轮基线测试复证）。

## Findings → Final State

| ID | 发现 | 结论 | 最终 |
|---|---|---|---|
| R1 | Casdoor 关闭时 edge 信任客户端自带 `X-Internal-Token` | CONFIRMED（实现偏离 `公网化-OIDC-改造方案.md` C2/R1b 的既定设计） | RESOLVED |
| R2 | token 预算只记账不拦截 | CONFIRMED | RESOLVED |
| R3 | Redis 语义缓存忽略 `max-entries-per-tenant`，桶无界 | CONFIRMED | RESOLVED |
| R4 | 生产 RS256 无机器门禁 | PARTIALLY_CONFIRMED | RESOLVED（门禁） |
| R5 | Casdoor 字段默认与 yml 相反 | CONFIRMED | RESOLVED（注释） |
| R6 | ReBAC 默认关 + public KB 默认开 | INVALID（写公共库需 scope） | INVALIDATED |
| R7 | channel 签名默认关 | INVALID（路径需内部 JWT，开启缺 secret 时 fail-closed） | INVALIDATED |
| R8 | ingest GC 默认关 | 数据删除策略决定 | DEFERRED |
| R9 | 内存关键词镜像不持久 | 生产腿为 ES | DEFERRED |
| R10 / N2 | Agent 双栈 | 退役门禁未满足 | DEFERRED |
| O2 | 跨语言租户配额 | 需两仓 + 计费口径 | DEFERRED（R2 是 Java 侧前置） |
| O9 | per-service CI | 有效 | DEFERRED（PREPARE_NEXT） |
| O6/O8/O10 | 见 OPPORTUNITIES | 触发条件未满足 | DEFERRED |

## Executed Changes

| 切片 | 改动 | 行为兼容 |
|---|---|---|
| S1 | `InboundInternalHeaderStripFilter`（-140，常驻）；`ApiKeyToInternalTokenFilter` 改按 `EdgeAuthenticatedTenant` 属性判断已认证；Casdoor 的剥离保留为纵深防御 | CHANGED（有意）：Casdoor 关闭时客户端自带内部头 → 401。Casdoor 开启（默认 only）行为 UNCHANGED |
| S2 | `platform-metering` 新增 `TokenBudgetGuardFilter`（servlet，+30，排在内部鉴权之后）+ `app.token-budget.enforce` / `guard.paths` | CHANGED（有意）：用满当日预算后，生成类 POST → 429 + Retry-After。未用满 UNCHANGED；`TOKEN_BUDGET_ENFORCE=false` 回滚 |
| S3 | `RedisSemanticCacheStore` 以 Lua + 顺序 ZSET 原子执行 FIFO 上限；ttl/失效同时覆盖两把 key | CHANGED（有意）：Redis 档从无界变为每租户 ≤ `max-entries-per-tenant`（默认 1000），与内存档语义一致；旧桶首次写入时自动收敛，无需迁移 |
| S4 | `deploy/test-production-cutover-config.sh`：生产 Helm 必须 RS256，签名私钥 Secret 只允许 edge-gateway 挂载 | 仅门禁 |
| S5 | `CasdoorSecurityProperties` 注释写明 yml 才是生效默认 | 仅注释 |

API 契约、数据 schema、事件、状态机均未变；无 DB migration。文档同步：`docs/参考/operations.md`、`docs/参考/架构文档.md`、
`docs/平台工程/cost-attribution.md`、`docs/对话与检索/semantic-cache.md`。

## Verification Matrix

| 切片 | 单测 | 注入回归 | 模块回归 | 真实冒烟 |
|---|---|---|---|---|
| S1 | +6（含按 order 串起 Casdoor 关闭时的整条认证链） | 还原旧代码 → 3 项失败（伪造头被转发） | edge 76 全绿 | 真 jar + Casdoor 关闭：伪造头 401，api-key 通过 |
| S2 | +7 filter，+3 装配 | — | metering/conversation/agent/knowledge/vision 全绿 | 真 jar + dev-infra Redis：429 + Retry-After，另一租户放行，指标计数 |
| S3 | +4 真实 Redis | 去掉裁桶 → 3 项失败 | conversation 136 全绿 | 集成测试即在真实 Redis 上执行 |
| S4 | — | HS256 与私钥误挂两类注入均被拦截 | 5 个 deploy 门禁 PASS | — |

全反应堆：1374 / 0 fail / 10 skip（基线 1354 / 0 / 6）。

## Remaining Risks / Debt

- R2 语义是「用满后的下一次被拒」：单次大请求可以把当日用量推过预算；并发请求在同一时刻都能通过预检。
  要硬上限需预留/预扣（reserve-then-settle），属于 O2 的计费口径设计。
- R2 预算默认 10 万 token/天/租户；eval 回归或演示租户可能开始收到 429，需要按租户 `overrides` 提额。
- R2 只覆盖 Java 入口；`/agent/**` 默认走 Python orchestrator 时，模型消耗仍不计入（O2）。
- R3 的两把 key 在 Redis Cluster 下会 CROSSSLOT（本仓与现有其它 Lua 脚本一样假设单机/主从 Redis）。
- 真实 Redis 集成测试需 `DEV_INFRA_SMOKE=true`，CI 默认跳过。
- deploy 门禁脚本隐式依赖 `rg`（GAP-2）。

## Architecture Before → After

- Before：edge 的「已认证」信号有两个来源（exchange 属性 / 可伪造的 header），剥离逻辑挂在一个可关闭的 filter 上；
  预算只有记账面；Redis 缓存容量只靠 TTL。
- After：edge 认证信号单一（`EdgeAuthenticatedTenant`），剥离挂在不可关闭的第一道 filter；预算有入口执行面
  （记账 listener + 预检 filter 共享同一 tracker）；两个缓存后端的容量语义一致。没有新增服务、中间件或存储技术。

## Next Evolution Candidates

1. O2 跨语言配额收口（两仓）：让 Python orchestrator 共用 `lc4j:token:budget:<day>:<tenant>` 计数契约，或在 LiteLLM 层按租户限额；可叠加 reserve-then-settle。
2. O9 per-service CI 模板；顺带让 deploy 门禁在缺 `rg` 时明确报错。
3. 给 `platform_token_budget_guard_total` 增加告警规则（需先有真实运营阈值）。
4. R8 的 retention 决策（运维）后再打开 ingest version GC。

## Interview / Engineering Knowledge（仅限本轮真实证据）

**边缘认证信号的可信来源（R1）**
- 背景：三种入站凭证、一个可回滚的 SSO 开关。原问题：api-key filter 用「请求里有内部头」表示「前面的 filter 认证过」，而剥离客户端内部头的逻辑只在 Casdoor filter 里——回滚关掉 Casdoor，边缘认证即被绕过。
- 难点：单个 filter 的单测都绿；漏洞只在「开关组合 × filter 顺序」下出现，而且与已批准设计文档的约束恰好相反。
- 决策：认证信号只取不可伪造的 exchange 属性；剥离放进不受开关影响、order 最早的 filter；用按 order 串起整条链的测试锁住回滚路径。
- 取舍：Casdoor 内的剥离保留为纵深防御（重复但无害），不做链路重排。

**预算为什么不能在 listener 里拦（R2）**
- langchain4j 吞掉 `ChatModelListener` 抛出的异常，因此 listener 天然只能记账。执行面放在入口 servlet filter，与 listener 共享同一个 tracker。
- 取舍：Redis 不可用时 fail-open（成本控制不应放大成可用性事故），并用 `outcome=check_failed` 计数让它可观测；单次越界的精度问题留给 O2 的预扣设计。

**Redis 缓存容量的原子淘汰与无迁移升级（R3）**
- HSET + ZADD NX + ZPOPMIN + HDEL 放在一段 Lua 里；对没有顺序记录的旧条目按 score 0 补登记，让它们最先被淘汰。上线后首次写入即自动收敛，不需要迁移脚本。
- 注入「不裁桶」后 3 项真实 Redis 测试失败，证明测试确实覆盖了该语义。

## Engineering Skill Runtime Observation

- Router：关键词驱动，"remediation" 被路由到只允许改 Runtime 文件的技能（GAP-1）；换用「技术债治理」表述后路由正确。
- Planner / Gate：`project-refactoring` 的 Baseline / Test-Protection Gate 只有文字契约、没有机器工具（GAP-3）；本轮用手写 backlog + state 执行。
- Evidence reuse：好——既有分析直接给出候选；只对 10 项做定点验证，其中 2 项判为 INVALID、1 项发现比原述更严重。
- False PASS：3 个 deploy 门禁在无 `rg` 时报「配置缺失」，属于 false BLOCK 形态（GAP-2，项目侧）。
- 人工介入：0 次。重规划：0 次。阻断：0 个。测试失败：仅计划内的注入失败，外加 2 次因 `~/.m2` 快照过期导致的编译失败（改用 `-am` 解决）。
