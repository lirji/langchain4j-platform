# Remediation Backlog（唯一权威整改清单）

> 2026-10-14 交付更新：下文为 2026-09-25 的历史实施记录，“未提交/不 push”等描述适用于当时。当前已按用户明确指令分组提交并推送 main；最新验证和状态以 `docs/delivery/pending-reliability-delivery/DELIVERY_RESULT.md` 为准。

- 路由：`intent-mapper.py` →「技术债治理」= `PROJECT_REFACTORING` → `project-refactoring`（执行模式）。
  「remediation」关键词会路由到只允许改 Runtime 文件的 `remediation` 技能，见 `EXECUTION_STATE.md` 的 GAP-1。
- 基线：`e8f11cb`（main）。工作树开工前已有用户未提交改动 `capability-showcase-frontend/nginx.conf`，本轮不触碰。
- 来源：`.engineering/analysis/PROJECT_DEEP_ANALYSIS.md` §17/§18、`PROJECT_CAPABILITY_REPORT.md` §13/§14、
  `.engineering/exploration/OPPORTUNITIES.md` O1–O10 / N1–N7、`CODEX_PROGRESS.md`（F 系列）。
- 优先级：P0 正确性/安全，P1 核心可靠性/成本，P2 可维护/性能，P3 可选。

## 验证结论总表

| ID | 来源 | 结论 | 分类 | 优先级 |
|---|---|---|---|---|
| R1 | §17-6「入站内部头剥离依赖 Casdoor filter」 | CONFIRMED，且比原述更重：Casdoor 关闭时 `ApiKeyToInternalTokenFilter` 以「请求里已有 `X-Internal-Token`」判断已认证，直接放行客户端自带的内部头 | FIX_NOW | P0 |
| R2 | §17-3 / §18「预算不拦截」 | CONFIRMED：`TokenBudgetTracker.wouldExceed` 无生产调用方；Javadoc 引用的 `TokenBudgetGuardFilter` 不存在；`ChatModelListener` 异常被 langchain4j 吞掉，无法在 listener 拦截 | FIX_NOW | P1 |
| R3 | §17-5 / §18「语义缓存 HVALS」 | CONFIRMED，且有更具体缺陷：默认 `store=redis` 的 `RedisSemanticCacheStore` 完全忽略 `max-entries-per-tenant`（只有内存实现生效），默认 ttl=0 → 每租户 Hash 无界增长、每次 `/chat` 全量 HVALS | FIX_NOW | P2 |
| R4 | §17-1「HS256 + 默认 secret」 | PARTIALLY_CONFIRMED：Helm 生产默认 RS256，且验签固定算法（`InternalToken:212`，RS256 节点拒 HS256）；compose/本地仍用公开 dev secret（开发用途可接受）。缺口是「生产必须 RS256」没有机器门禁 | FIX_NOW（门禁） | P1 |
| R5 | §18 P3「Casdoor Javadoc 与 yml 相反」 | CONFIRMED：字段默认 `enabled=false/DUAL`，yml 生效默认 `true/only` | FIX_NOW（注释） | P3 |
| R6 | §17-2 / §18「ReBAC 默认关 + public KB 默认开」 | INVALID（作为缺陷）：写公共库需 `public-ingest` scope（`DocumentController:188`），读并入不越过真实租户边界；ReBAC 是文档级增强、默认关属已文档化的设计 | REJECT | — |
| R7 | §17-7「channel 签名默认关」 | INVALID（作为缺陷）：`/channel/inbound` 不在 `EdgeOpenPaths`，仍需内部 JWT；开启但缺 secret 时 fail-closed（`ChannelSignatureVerifier:30`） | REJECT | — |
| R8 | §17-8「ingest GC 默认关」 | CONFIRMED 但属数据删除策略：默认开启 = 默认删历史版本，需运维决定 retention | DEFER | P2 |
| R9 | §17-5「内存关键词镜像不持久」 | PARTIALLY_CONFIRMED：`DocumentMirror` 进程内；持久关键词腿由 ES 承担（`docker-compose.es.yml`） | DEFER | P3 |
| R10 | §17-4 / N2 Agent 双栈 | CONFIRMED，退役门禁未满足 | DEFER | — |
| O2 | 跨语言租户配额收口 | CONFIRMED，需两仓 + 计费口径 | PREPARE_NEXT（R2 是 Java 侧前置） | P0 |
| O9 | per-service CI | CONFIRMED | PREPARE_NEXT | P2 |
| O6/O8/O10 | 见 OPPORTUNITIES | 触发条件未满足 | DEFER | P2–P3 |
| O1/O3/O4/O5/O7、F1/F2/F5/F6/F7/F10 | 前序轮次 | ALREADY_RESOLVED（`CODEX_PROGRESS.md`，本轮基线 `mvn -o test` 全绿复证） | — | — |

## FIX_NOW 条目

### R1 edge 信任客户端自带内部头（P0）
- Problem：`EDGE_CASDOOR_ENABLED=false`（文档化回滚路径）时，没有任何 filter 剥离入站 `X-Internal-Token`；`ApiKeyToInternalTokenFilter` 见到该头就跳过 api-key 校验并转发。
- Evidence：`CasdoorTokenExchangeFilter`（唯一 strip 点，`@ConditionalOnProperty edge.casdoor.enabled`）；`ApiKeyToInternalTokenFilter:54-56`。
- Root Cause：「已认证」用可被客户端控制的 header 表达，而不是只能由认证 filter 写入的 exchange 属性（`EdgeAuthenticatedTenant` 已存在且被所有认证 filter 写入）。
- Business Impact：compose 部署（HS256 公开 dev secret）可伪造任意租户；任何部署可重放泄漏的内部 JWT 绕过 edge 限流与租户归属。
- Strategy：①新增常驻 `InboundInternalHeaderStripFilter`（order -140，早于所有认证 filter，不依赖 Casdoor 开关）；②`ApiKeyToInternalTokenFilter` 改以 `EdgeAuthenticatedTenant` 判断已认证；③Casdoor filter 不再承担剥离职责（单点定义）。
- Verification：新 filter 单测 + ApiKey filter「伪造头无 api-key → 401」「会话已认证 → 放行」+ edge 全量回归。
- Rollback：revert 单个提交；无配置/数据变更。

### R2 token 预算只记账不拦截（P1）
- Strategy：`platform-metering` 新增 `TokenBudgetGuardFilter`（servlet，order 在 `InternalTokenAuthFilter` 之后），对**发起新生成工作的 POST**（默认 `/chat/**`、`/extract/**`、`/agent/**`、`/vision/**`、`/conversation/workflow/**`）预检 `wouldExceed(tenant)`，超额 429 + `Retry-After`；GET 查询/任务状态/检索不拦。`app.token-budget.enforce`（`TOKEN_BUDGET_ENFORCE`，默认 true）可回滚为只记账。
- Behavior：CHANGED（有意）——超出日预算的租户在上述入口得到 429；未超额行为不变。
- Verification：filter 单测（超额 429 / 未超额放行 / GET 不拦 / 非守护路径不拦 / enforce=false 放行 / 探针不拦）+ 4 个依赖模块回归。
- Rollback：`TOKEN_BUDGET_ENFORCE=false` 或 revert。

### R3 Redis 语义缓存无界（P2）
- Strategy：`RedisSemanticCacheStore` 维护同租户插入顺序 ZSET，写入后按 `max-entries-per-tenant` 淘汰最旧（与内存实现同一 FIFO 语义），ttl 同步作用于两把 key，失效操作同步清理。
- Verification：mock 单测 + env 门控的真实 Redis 集成测试（dev-infra `:46379`）。
- Rollback：revert；旧数据无需迁移（无 ZSET 的旧桶首次写入后自然纳入，历史超额条目由 `invalidate` 或 ttl 清理——见实现说明）。

### R4 生产 RS256 无门禁（P1）
- Strategy：`deploy/test-production-cutover-config.sh` 增加断言：Helm 渲染的 `platform-config` 中 `PLATFORM_SECURITY_JWT_ALGORITHM=RS256`。注入回归验证。
- Rollback：revert 脚本。

### R5 Casdoor Javadoc 漂移（P3）
- Strategy：注释说明「字段默认是缺配置时的回落值，生效默认由 application.yml 决定（enabled=true, mode=only）」；不改字段默认（测试直接 new 该对象，改默认会改变测试语义）。
