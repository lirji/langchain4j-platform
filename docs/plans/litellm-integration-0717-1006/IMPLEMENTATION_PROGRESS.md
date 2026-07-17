# LiteLLM 接入实施进度

> 对照 `FINAL_PLAN.md` 分阶段记录。每阶段：做了什么 / 测试结果 / 是否满足完成标准。

## 阶段一+二：领域模型 + 核心 Java（✅ 2026-07-17）

**新增** `platform-gateway-client`：
- `tenant/TenantAttributionMode`（NONE/USER/VIRTUAL_KEY 枚举，非法值绑定失败→启动失败）
- `tenant/TenantIdentityProvider`、`tenant/TenantVirtualKeyResolver`（SPI）
- `tenant/EnvironmentTenantVirtualKeyResolver`（Environment 逐 key 查询，不经 configprops 暴露；空白=缺失）
- `tenant/TenantVirtualKeyMissingException`（fail-closed，消息不含 key）
- `tenant/TenantAwareChatModel` + `tenant/TenantAwareStreamingChatModel`（覆盖两参/三参 chat；
  便捷重载经字节码核验的漏斗全汇入覆盖点；listener 只在 delegate 侧执行一次）
- `tenant/TenantContextIdentityAutoConfiguration`（@ConditionalOnClass(TenantContext)）
- `GatewayRequestHeadersSupplier`（virtual-key Authorization + W3C trace 注入，每次新 Map）

**修改**：
- `GatewayClientProperties`：+`tenantAttribution`（默认 NONE）
- `GatewayChatModelFactory`：全部出口（build/buildDeterministic/指定模型/流式→cascade 自动覆盖）套
  wrapper + customHeaders(Supplier)；保留两参兼容构造
- `PlatformGatewayClientAutoConfiguration`：注入 SPI（ObjectProvider 匿名兜底+告警）、默认
  Environment resolver（@ConditionalOnMissingBean）、Tracer/Propagator getIfUnique
- `pom.xml`：optional platform-security（eval-service 不被传递污染）
- `AutoConfiguration.imports`：+TenantContextIdentityAutoConfiguration

**测试**：`mvn -pl platform-gateway-client test` → **33 全绿**（新增 24 + 既有 cascade 9 无回归）。

**关键验证（FINAL_PLAN §14 两处存疑全部解除）**：
- `GatewayTenantAttributionHttpContractTest`（JDK HttpServer 抓真实报文）：
  `customHeaders(Supplier)` **覆盖**静态 Authorization（有且仅有一个值=租户 key，master key 不出现在
  报文），**每请求求值**（两次调用换租户 header 跟着换），`user` 落 JSON body（pretty 格式
  `"user" : "x"`）。→ 无需方案 B 回退。
- 并发 200 任务×16 线程不串租户；listener 恰好一次；防伪造（调用方 user 被覆盖）。

**完成标准自检**：三档同步/流式单测过 ✅；user 不可伪造 ✅；listener 一次 ✅；
build/deterministic/指定模型/cascade 全覆盖 ✅（cascade 经 factory.build 收口）；默认 none ✅。

## 阶段三：Compose + LiteLLM 配置（✅ 2026-07-17）

**`deploy/docker-compose.yml`**：
- litellm：镜像固定 `v1.74.3-stable`（`LITELLM_IMAGE_TAG` 可覆盖；tag 经 `docker manifest inspect`
  验证存在）；+DATABASE_URL（litellm-postgres）、STORE_MODEL_IN_DB=false（config 仍是模型路由权威）、
  UI_USERNAME/UI_PASSWORD、REDIS_HOST/PORT、OTEL_EXPORTER/ENDPOINT/SERVICE_NAME；
  depends_on PG healthy + redis
- +`litellm-postgres`（postgres:16-alpine，healthcheck，命名卷 litellm-postgres-data，
  **不映射宿主端口**——authz-postgres 等易撞 5432）
- +`jaeger`（all-in-one:1.57，与 observability-guide.md 版本一致；UI/OTLP 端口可环境变量覆盖防冲突）
- 6 个 LLM 消费服务（conversation/workflow/analytics/knowledge/agent/vision）：
  +`PLATFORM_GATEWAY_TENANT_ATTRIBUTION`（默认 none）、`MANAGEMENT_TRACING_ENABLED`（默认 false）、
  `MANAGEMENT_OTLP_TRACING_ENDPOINT`
- `docker compose config --quiet` 通过

**`deploy/litellm/config.yaml`**：
- +`chat-default-fallback`（ollama/llama3.1，注释明确能力不等价、仅降级兜底；视觉刻意不配 fallback）
- +`fallbacks`（语法与 config.failover.yaml 一致——该语法 smoke 真机验证过）
- +`cache: true` + cache_params（redis type/ttl 600/namespace litellm.cache；host/port 走容器 env）
- +`callbacks: ["otel"]`
- 刻意不写 database_url：DATABASE_URL env 存在即自动启用（LiteLLM 原生行为），
  无 PG 时本配置仍可独立跑（**与 FINAL_PLAN §7.2 的偏差**，原因如上）

**与 FINAL_PLAN 的偏差**：litellm 容器未加自身 healthcheck——官方镜像内无 curl/wget，
错误的 healthcheck 比没有更糟；就绪判定由 smoke 脚本 /health/liveliness 轮询承担。

**主栈安全**：改动时主栈正在运行（24h+）——config.yaml 是只读挂载，运行中容器不受影响，
重启后新配置才生效。未触碰任何运行中容器。

## 阶段三b：failover 测试栈 + 冒烟扩展（✅ 代码完成，冒烟运行中）

- `docker-compose.failover.yml`：+smoke-postgres/smoke-redis/jaeger(:16690 防撞主栈)；
  litellm 同一固定 tag + DATABASE_URL/REDIS/OTEL env；无命名卷，down -v 只清测试 project
- `config.failover.yaml`：+test-only 单价（每调用 $0.02，让预算硬拒绝确定性可触发）、
  cache、otel callbacks、proxy_batch_write_at=1（缩短 spend 落库等待）
- `smoke-failover.sh` 扩为 8 步：cache ping → virtual key 签发 → spend 落 PG →
  预算硬拒绝(4xx) → spend 跨重启存活 → failover(mock-a→mock-b) → Jaeger 查 litellm-proxy trace →
  双上游全挂有界失败

## 阶段四：测试（进行中）

- `mvn -pl platform-gateway-client test`：**33 全绿** ✅
- `mvn -pl conversation-service,agent-service,analytics-service,workflow-service,eval-service,vision-service -am test`：**BUILD SUCCESS，零失败** ✅
- `mvn test`（全仓）：BUILD FAILURE —— **唯一失败 `KnowledgeAuthzIntegrationTest`（3 Errors，
  "cannot determine uploader's home department ... under enforce"）。已用干净 HEAD(61be88d)
  worktree 复跑证实：存量问题，与本次改动无关**（部门隔离功能的测试漂移，未在本任务顺手修）。
  除该存量项外全仓 213 项测试其余全部通过。
- `deploy/smoke-failover.sh`（8 步真机冒烟，镜像 v1.74.3-stable）：
  - 首跑失败（180s 就绪超时）。**配置二分诊断**（7 个变体 C/D/E/F/A/B/G/H）结论：
    配置全部无罪——D(最小+DB)/E(+batch_write)/F(+fallbacks+单价) 均秒级 200；
    失败根因 = ①首跑与 mvn 全仓测试并行、6 核 Docker VM 资源挤兑，DB 模式首启
    （prisma generate+migration）超出 180s 等待窗；②诊断期间 Docker Desktop daemon
    一度 502 并重启（全部容器 Exited 255），产生误导性的 A/B/G "死亡"；
    H 验尸（ExitCode=3 非 OOM，P1001 Can't reach smoke-postgres）证实是 PG 被清的连锁。
  - 附带验证：LiteLLM 丢 DB 时 fail-fast exit 3（行为正确）。
  - 修复：脚本就绪等待 180s→300s；脚本中文消息曾出现 UTF-8 坏字节（`FIRST�: unbound
    variable`），整体重写为 ASCII 消息版并校验字节完整。
  - **重跑结果（分段完成，全部 8 步断言通过）**：
    - [1] cache ping OK（Redis 接通）
    - [2] virtual key 签发+调用 OK（reply-from-a）
    - [3] spend=$0.02 落 PG（test 单价生效）
    - [4] 预算硬拒绝 HTTP 400（fail before provider）
    - [5] 重启后 spend=$0.02 完好（PG 持久；重启就绪仅 13s——首启慢是 migration）
    - [6] failover OK（主=reply-from-a，停 mock-a 后=reply-from-b）
    - [7] OTel：首验失败——`OTEL_ENDPOINT` 裸端口 404（"Failed to export batch code: 404"
      日志确凿），**修复为带 `/v1/traces` 完整路径**（两个 compose + FINAL_PLAN §8.4 已更新）。
      ⚠️ 修复后复验未完成：本机当前容器极多（主栈+apollo+authz+recsys 同跑），smoke litellm
      连续被 **OOMKilled=true**（docker inspect 证实），无法稳定接请求。修复方向无疑义
      （LiteLLM OTLP HTTP exporter 需完整路径）；**遗留验证项**：环境空闲时跑
      `bash deploy/smoke-failover.sh` 一次性全验 8 步。
    - [8] 双上游全挂有界失败 OK（HTTP 500，不悬挂）
    - 注：[5]-[8] 因后台任务被中断改为手动逐步执行，断言逻辑与脚本一致；
      本机慢启动/Exited(137) 的根因均为内存压力（OOMKilled），非配置/镜像问题。

## 阶段五：文档与检查（进行中）

- ✅ 新增 `docs/平台工程/litellm-gateway-guide.md`（能力总览/三档归因/key 签发轮换 runbook/
  缓存红线/failover 前置与告示/OTel/回滚总表/冒烟说明）
- ✅ `docs/README.md` 平台工程索引 +1 行，cost-attribution 条目补双轨分工指引
- ✅ diff secret 扫描：本任务改动仅含 dev/test 占位值（均可 env 覆盖），无真实凭证
- ⚠️ 工作区混有**非本任务**的未提交工作（order-service 新模块、agent OrderQuery 动作、
  AGENTS/CLAUDE/README/helm/docs参考 等改动）——本任务未触碰，提交时需分开
- 默认值审核：tenant-attribution=none ✅、MANAGEMENT_TRACING_ENABLED=false ✅、
  cache/fallback 属显式启用的需求变化（FINAL_PLAN 目标 5/6）✅

## Codex 独立审查（跨模型闭环，2026-07-17）

Codex 对任务范围 diff 审查提出 10 项发现，逐条判定后经用户批准实施 6 项修复（修后 36 测试全绿）：

**已修复**：
- 兼容构造器 fail-fast：virtual-key 档 + null header supplier 构造期即抛（原可静默回退 master key）+ 单测
- 空白 virtual key fail-closed：supplier 兜底 trim 校验（防自定义 resolver 违反契约）+ 单测
- 冒烟 key 不进 URL：/key/info?key= 改为 /key/list 按 alias 过滤（master 鉴权）
- 冒烟 wait_ready 改以 readiness 为门禁（liveness 过早放行会偶发断言失败）
- config 加 router_settings.cooldown_time=30（双层重试放大缓解；failover 测试栈同构，冒烟起得来即验证键合法）
- 冒烟新增 cache hit 铁证断言（主上游死后同 prompt 仍答 reply-from-a=只可能来自 Redis 缓存）
- 新增并发 Authorization 隔离 HTTP 契约测试（64 并发逐对配对断言，验证 supplier 调用线程求值模型）

**判定不修（含理由）**：
- "eval-service 漏配归因 env"：查证不成立——compose 里 eval 本就无 GATEWAY_BASE_URL（回归客户端打 edge），与存量一致
- knowledge-service 自建 OpenAiChatModel 绕过 factory（连 listeners/预算/成本都绕过）：**属实但为存量架构**，
  超出本次批准范围 → 列为独立后续任务（建议高优先级：切到 GatewayChatModelFactory）
- 冒烟深度（TPM/RPM/跨租户隔离/备份恢复演练）：维持 FINAL_PLAN 生产门禁清单，演示环境不展开

## 全栈重建部署（✅ 2026-07-17）

`mvn -DskipTests package` BUILD SUCCESS → `docker compose up -d --build`（25 容器全 Up）。
部署参数：`EDGE_HOST_PORT=18080 VISION_HOST_PORT=18090 MYSQL_HOST_PORT=23306`
（8080/8090 被 apollo 占、13306 被 apollo-db 占）；DEEPSEEK key 从旧容器 env 提取转发。

**真机验证（生产 compose 栈）**：
- LiteLLM v1.74.3-stable + PG + 新 config（fallbacks/cache/otel/`router_settings.cooldown_time`）
  readiness 200——cooldown 键合法性顺带验证 ✅
- 端到端 LiteLLM→DeepSeek：200，12 tokens ✅
- **spend 记账落 PG**：/spend/logs 1 条（deepseek-chat，$1.96e-06，12 tokens）✅
- **OTel 遗留验证项关闭**：Jaeger 查到 litellm-proxy trace（`/v1/traces` 修复生效）✅
- 8 个关键端点（edge/litellm/jaeger/前端/conversation/knowledge/agent/vision）全 200 ✅
- 注意：业务 API 经 edge 现为 401——`EDGE_CASDOOR_MODE=only`（用户提交 a48dee2 默认开启）
  且 authz-casdoor 容器未跑；属预期配置行为，需另起 auth-platform 栈方可登录调用。

## 遗留清单（汇总）

1. ~~OTel `/v1/traces` 修复后的 smoke 复验~~ → **已在生产栈关闭**（Jaeger 见 litellm-proxy trace）；
   完整 8 步冒烟 `bash deploy/smoke-failover.sh` 仍可在空闲时跑一遍作为回归
2. ~~knowledge-service ChatModel 切 GatewayChatModelFactory（存量绕过，连应用侧计量都缺失）~~
   → **✅ 2026-07-17 已修复**：knowledge-service +依赖 `platform-gateway-client` + `platform-metering`；
   `KnowledgeChatModelConfig.knowledgeChatModel` 改为注入 `GatewayChatModelFactory` 并
   `build(modelName, 0.0)`（temp=0 确定性），审计 / 按租户 token 预算 / 成本 / LiteLLM 侧租户归因
   对 RAG 增强（rerank=llm·query-expansion·contextual·grounding）的 LLM 调用统一生效。
   base-url/api-key/timeout 收归 `platform.gateway.*`；仅保留 `RAG_LLM_MODEL` 模型名覆盖。
   自动装配默认 `chatModel` 经 `@ConditionalOnMissingBean` 让位；`streaming.enabled=false` 省一个
   无用流式 Bean。新增 `KnowledgeChatModelConfigTest`（1 项）锁定「经工厂 + temp=0」；
   `mvn -pl knowledge-service test`（排除下方存量 Authz）BUILD SUCCESS。
3. KnowledgeAuthzIntegrationTest 3 个存量 Error（部门 enforce 测试漂移，与本次无关）
4. 生产化门禁项：预算阈值业务确认、PG 备份演练、TPM/RPM 冒烟、`enable_redis_auth_cache` 键名验证
