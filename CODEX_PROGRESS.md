# Codex Progress

## 任务目标

按Claude Engineering Skill修复Java/AgentScope七类可靠性缺口, 完成验证与正常Git交付。
规范状态: langchain4j-platform/docs/delivery/cross-runtime-reliability/DELIVERY_STATUS.md。
两仓任务分支fix/cross-runtime-reliability; 用户持续授权正常合并/push main, 不含生产部署。

## 已完成

- S1–S7全部实现并按逻辑单元提交/推送任务分支; 详见DELIVERY_REPORT和各切片文档。
- 干净Java1406tests/0fail/0error/13skip, Python518pass/89.27%; 真实MySQL/Redis/独立worker/SSE/TCP验证PASS。
- 两仓Code Hygiene无blocking; 主Agent对抗自复审与QA报告已准备。
- Python cryptography50.0.2/PyJWT2.15.1/urllib3 2.8.0审计0漏洞, runtime PCRE2补丁后远程CI36979153821 SUCCESS。
- Java固定IAM SDK源码929e9ca安装, CI补ripgrep/隔离供应方目录; 固定契约producer6f43ddf。
- 初始dirty和intent-to-add保留; Java19/Python6文件重建0偏差, 第三IAM仓只读。

## 已修改文件

- RAG/inbox/预算RPC和模型适配/退款回执/只读调度与worker/流式候选和取消/固定契约。
- 相关迁移、聚焦测试、Compose overlays、CI、文档与进度; 完整路径见任务分支diff。
- 原有用户贡献均未提交, 不以当前工作树干净作为验收要求。

## 未完成

- S8正常main合并进行中; 用户2026-10-02明确接受已披露版本扫描例外并要求合并。
- Python运行提交34df909, Java deba7bc; 后续只改交付文档。
- Java cutover36978970189全测试过但Compose断言失败; 行号诊断定位Linux SIGPIPE, deba7bc完整消费输入; 本地与远程36980014838 SUCCESS。
- Java supply-chain36978975220聚合SBOM85条HIGH/CRITICAL、31依赖坐标仍FAIL; 用户本轮接受合并例外, 不升级框架。
- MERGE_EXCEPTION.md记录最新用户决定; SECURITY_MIGRATION_PROPOSAL仅作后续技术债。

## 当前问题

- 已披露版本扫描仅作为本次源码合并例外; CI保持原失败结论和门禁, Java镜像扫描尚未进入。
- 原Python未提交新增测试有1个旧decode预期, 新PyJWT更早拒绝非规范JWT; 保留该测试, 不放宽安全验证。
- 生产runbook仍NO-GO, 不伪造真实模型、容量、恢复或历史ESS成功。

## 下一步建议

1. 在既有干净验证worktree按producer→consumer正常快进合并/push main。
2. 核对远程包含任务提交、保留原有dirty贡献; 同步最终交付状态。
3. 原门禁继续扫描, 记录CI实际结果; 不实施框架迁移或生产部署。

## 恢复 Prompt

请读取CODEX_PROGRESS与规范DELIVERY_STATUS/DELIVERY_REPORT, 继续未完成工作。
保护既有dirty贡献; 不重复7项分析, 不降低CI门禁; 本轮已获版本扫描合并例外, 不执行生产部署。

---

以下为历史任务记录，授权与当前状态以本文件上半部分和规范状态为准。

# Codex Progress

## 任务目标

Docker Desktop 虚拟盘清空后，恢复共享 `dev-infra`，并把能力门户 http://127.0.0.1:5274/ 里每个项目的 Docker 服务**逐个**拉起。内存不够时停掉上一批项目应用，只保留共享中间件与门户。随后按 `seed-mock` 给**除 langchain4j 外**的门户项目灌测试/演示数据（写入真实库表，不写死页面）。不要重跑 langchain4j `manage.py backup`。不提交、不推送。

## 已完成

- **项目能力/深度分析落盘（2026-09-16）**：只读扫描后写入 `.engineering/analysis/`（非产品代码）。四件套 + 能力报告：`PROJECT_DEEP_ANALYSIS.md`、`ARCHITECTURE.md`、`BUSINESS_FLOWS.md`、`EVIDENCE_INDEX.md`、`PROJECT_CAPABILITY_REPORT.md`。本机 `mvn test` 未跑（UNVERIFIED）。未改业务代码；分析产物已随本轮一起提交并推送。
- **能力演进探索落盘（2026-09-16，跨仓口径修订）**：按「本仓 + `agentscope-platform` 是同一产品两半、Agent 编排已迁出」重写 `.engineering/exploration/` 五件套（`CAPABILITY_MAP` / `CAPABILITY_GAPS` / `OPPORTUNITIES` / `EVOLUTION_ROADMAP` / `EVIDENCE_INDEX`），共 459 行、31 条证据。结论：P0 是①跨语言 boundary 契约在 Java 侧无校验且 cutover CI 无兼容门禁、②`/agent/**` 默认走 Python 后 Java 租户 token 预算/成本计量被绕过；P1 是退役门禁的两个 Java 侧机器检查与统一 Prometheus/告警面。7 项列入 Not Recommended Now（含现在删 `agent-service`、Chat 迁进 orchestrator、通用缓存）。只读产出，未改产品代码/配置/测试/依赖，未跑 `mvn test`（UNVERIFIED）；探索产物已随本轮一起提交并推送。
- **O1 跨语言 boundary 契约兼容门禁（2026-09-16，跨两仓）**：闭合「Agent 编排迁出后 Java 消费侧无契约护栏」的 P0 缺口。
  - agentscope 侧（producer）：`scripts/export_contracts.py` 新增 `contracts/manifest.json`，对 40 个 JSON 契约逐个固定 sha256（含手写 boundary schema），并纳入既有 `--check`；新增 2 个测试，其中一个证明手写 schema 漂移也会被抓到。
  - langchain4j 侧（consumer）：`deploy/sync-agent-contracts.sh`（verify/`--write`，无上游 worktree 时 skip）把 Java 真正消费/生产的 18 个契约 vendored 到 `platform-protocol/src/main/resources/contracts/agentscope/` 并固定 digest；新增 `AgentScopeContractManifestTest`(3) + `AgentScopeContractConformanceTest`(10) 做双向校验（生产方向 = Jackson 序列化过 schema；消费方向 = Java 读取字段仍被契约发布，允许只读子集）；root pom 新增 test-scoped `com.networknt:json-schema-validator` 1.5.1；`agentscope-cutover-ci.yml` 新增 `Verify agentscope contract compatibility` 步骤与 path filter。
  - 门禁有效性已用 3 次注入漂移验证（改 digest / 改 `schema_version` const / 把 `finalAnswer` 改名），三项分别被对应测试拦住并给出可定位报错，随后恢复复验为绿。
  - 验证：`mvn -B -pl interop-service,eval-service,conversation-service -am test` BUILD SUCCESS（platform-protocol 13 项全绿）；`deploy/test-{supply-chain,production-cutover,runtime-hardening,database-migration}-config.sh` 四项 PASS；agentscope `ruff` PASS、`mypy src` 87 files Success、`pytest` 472 项全绿。
  - **残留缺口（已写入 `docs/架构边界/ai-runtime-boundaries.md`）**：两仓 CI 都访问不到对方仓库，「本仓副本 == 上游最新」只在本地/跨仓联动时被证明；彻底闭合需把契约作为版本化制品发布给 Java 构建解析，属后续变更。
  - 已提交（`4cb82fc`）并推送。
- **F1 `mvn package` 反应堆修复（2026-09-16）**：`database-migrations` 的 Spring Boot `repackage` 默认让可执行 fat jar 顶替普通 jar，导致 `platform-eventbus` 的迁移测试在 `package` 阶段找不到 `com.lrj.platform.migrations`（`mvn test` 不跑 package，所以一直看不见）。改为 `<classifier>exec</classifier>` 让两种 jar 并存，同步 `database-migrations/Dockerfile` 与 `deploy/dev-infra/smoke.py` 的 jar 名；`deploy/test-supply-chain-config.sh` 新增两条静态门禁：Dockerfile 里 COPY 的 jar 名必须等于该模块按 classifier 真实产出的名字，且必须能被 `supply-chain.yml` 的上传通配符匹配到。后者当场发现连带缺口——CI 上传用 `*-0.1.0-SNAPSHOT.jar` 匹配不到新的 `-exec.jar`，而 `image-scan`/`release-images` 唯一的 jar 来源就是这个 artifact，已一并把上传与两处 attest 的通配符改成 `*-0.1.0-SNAPSHOT*.jar`。门禁按「退回旧通配符」注入验证会红。已提交（`05e06d0`）并推送；`mvn -o -DskipTests package` 全反应堆 BUILD SUCCESS。
- **F2 agentscope `mypy` 裸跑（2026-09-16）**：补 `src/agentscope_platform/py.typed`，`uv run mypy`（不带 `src`）不再因缺 marker 退出码 2。改动在 agentscope 仓，已提交（`bc1b854`）并推送。
- **F5 Java Agent 退役门禁的两个机器检查（2026-09-16）**：把门禁第 4、5 条从「人工翻库/翻日志」变成可判定。
  - `InteropToolDispatcher` 改为 fail-closed：agent 类工具只在 AgentScope live discovery 宣告了该能力时才代理，未宣告返回明确原因，PING 等本地工具不受影响。新增 `InteropToolDispatcherDiscoveryTest`（宣告则代理 / 未宣告拒绝 / discovery 不可达时全拒 / PING 始终可用 / 宣告了但 interop 不能代理的仍拒）。
  - 新增只读 `GET /async/drain-inventory`：按 kind + 状态 + 租约持有者盘点未完结 Agent 任务，内存与 JDBC 两套实现（JDBC 走 SQL 聚合，保证各副本看同一份中心队列），只报归属事实、由门禁脚本裁决哪个 leaseOwnerId 属于哪个运行时。新增 `AsyncTaskDrainInventoryTest`(5)。
  - 文档：`docs/架构边界/java-agent-retirement-gate.md` 补两条检查的执行方式，`docs/参考/api-reference.md` 与 `docs/平台工程/长任务处理指南.md` 收录新端点。
  - 已提交并推送：F5 拆两个提交（`b300c92` interop fail-closed、`40e5d47` drain inventory）。
- **F6 统一 Prometheus 指标面与关键告警（2026-09-16）**：此前 16 个 Java 服务里多数在 exposure 写了 `prometheus` 却没有 registry（端点 404），且 actuator 与业务接口同端口、被内部 JWT filter 保护——内部 JWT 只活 5 分钟，Prometheus 与 kubelet 都拿不到静态凭据，指标面事实上不可用。
  - 依赖：`micrometer-registry-prometheus` 收进 `platform-observability`（`runtime` 且**不加 `optional`**，否则不传递），`edge-gateway` / `config-server` 不依赖该库故各自直接声明。
  - 端口：按用户选定的方案 A，全部 16 个服务 actuator 迁到独立 management 端口（业务端口 +1000，`MANAGEMENT_PORT`）。Spring 的 management 子上下文不继承业务端口的租户 filter，运维面因此免鉴权、业务面鉴权不变；AgentScope 是单端口应用，`/metrics` 与既有 `/health`、`/readiness` 取同一姿态（去掉 `RunContextDependency`）。
  - 抓取与告警：新增 `deploy/prometheus/prometheus.yml`（14 个默认拓扑 Java 服务 + AgentScope，profile 化的 agent/eval 故意不列）与 `alerts.yml`（8 条）。规则只引用代码里确实注册过的序列，逐条核对过 Java Micrometer 名与 Python OTel 渲染名（渲染器补 `_total`、不追加 unit 后缀）；刻意不设延迟/TP99 门限——本仓没有正式性能验收目标。
  - 运行面：compose 加 `observability` profile 的 prometheus（UI `:19090`）并发布 smoke 脚本要用的 mgmt 端口；Helm `platform-lib` 渲染 `mgmt` 容器端口与 Service 端口、探针改打 `mgmt`，非 Spring 服务用 `managementPort: 0` 回落到 `http`（`ternary` + `hasKey`，因为 sprig 的 `default` 把显式 0 当空值）；`deploy/dev-infra/smoke.py` 与 5 个 `deploy/smoke-*.sh` 的健康等待改用 management 端口。
  - 防漂移：新增 `deploy/test-observability-config.sh`（少 `prometheus` / 少 management 端口 / 端口不等于业务口+1000 / registry 不在 classpath / 漏抓取目标 / 把 profile 服务写进默认抓取 / 告警缺 severity·summary·description / Helm values 端口与 `server.port` 不一致，共 8 类），接进 `supply-chain.yml`；8 项注入回归全部被抓到，还原后为绿。
  - 文档：`docs/平台工程/observability-guide.md` 重写 3.1/3.3/3.4/4/5/6/7 并新增 3.5 告警小节（删掉「registry 未引入、抓取会 404」的过时结论），`docs/参考/operations.md` 第 15 节、`docs/平台工程/cost-attribution.md`、`deploy/helm/README.md`、`docs/README.md` 同步端口口径。
  - 验证：`promtool check config` 通过（1 rule file / 8 rules，需挂到 `/etc/prometheus`）；`helm template` 渲染确认 14 个 Spring 服务 mgmt=业务+1000 且探针在 mgmt、AgentScope 无 mgmt 且探针在 http；`mvn -B -pl platform-observability,platform-security,async-task-service,knowledge-service,edge-gateway,config-server -am -DskipITs test` 全绿（platform-security 62 / platform-observability 7 / knowledge-service 303 / async-task-service 68 / config-server 1 / edge-gateway 58）。
  - 已提交（`4ab1b2a`）并推送。
- **F7 图三元组版本 provenance，解锁 query 角色图检索（2026-09-16）**：图命中此前不带 `docId`/`version`，两个后果——过期版本的三元组会被继续召回，且 enforce 档因为无 `docId` 把图命中整类 fail-closed 丢弃；`query` 角色索性在启动校验里禁掉图检索。
  - 新增 `GraphSourceId` 作为 `<docId>/v<version>/<name>#<index>` 的唯一定义：写入（`GraphIngestor`）、版本 GC 的前缀删除（`KnowledgeVersionGarbageCollector`）、查询侧还原（`GraphRetrievalSource`）此前各自拼/各自拆同一个字符串，任一侧改格式另两侧不会发现。`docId` 是 16 hex、`version` 是 `v<数字>`，故按前两个 `/` 切分即可，文件名含 `/` 也无歧义。
  - `GraphRetrievalSource` 把还原出的 `docId`/`version` 放进命中，图命中因此与向量/ES 命中走同两道过滤：按 Registry 当前版本丢弃过期命中，以及 enforce 档的文档级判权。融合去重键仍是三元组自身 id，不会被同文档的 chunk 命中吞掉。
  - 新增 `app.rag.graph.require-provenance`（`RAG_GRAPH_REQUIRE_PROVENANCE`，默认 `false`）：为 true 时丢弃还原不出版本归属的历史三元组——它们既证不明新鲜度也证不明可读性，放行等于绕过上述两道过滤。`combined` 保持默认 false（召回口径不变），`KnowledgeRuntimeBoundaryConfig` 把 query 角色的「禁用图检索」改成「开图检索必须 require-provenance=true」。历史数据不需要迁移脚本：旧三元组在该开关下不参与查询，文档下次入库即带 provenance，旧版本由 GC 按前缀清理。
  - 部署：`docker-compose.knowledge-split.yml` 的 `knowledge-query` 增加 `RAG_GRAPH_REQUIRE_PROVENANCE=true` 并把 `RAG_GRAPH_ENABLED` 变成可覆盖（默认仍关，属容量决定）；Helm `knowledge-query` 同步；production overlay 注明关闭是容量决定而非能力限制。
  - 验证：新增 `GraphSourceIdTest`(9)、`GraphRetrievalSourceTest`(5)、`KnowledgeGraphCommittedVersionTest`(3)，`KnowledgeQueryServiceAuthzTest` 补一条「带 provenance 的图命中在 enforce 下按文档判权，可读留、不可读过滤」，`KnowledgeRuntimeBoundaryConfigTest` 改为断言新规则；`mvn -B -o -pl knowledge-service test` 319 项全绿（4 skip）。compose config、`helm template`、`deploy/test-{observability,production-cutover}-config.sh` 均 PASS。
  - 文档：`docs/架构边界/knowledge-runtime-split.md` 新增「图三元组的版本 provenance」小节并改写 query 角色约束，`docs/对话与检索/rag-guide.md` 新增版本 provenance 与判权小节，`docs/参考/operations.md` 环境变量表与 `docs/对话与检索/可靠入库接入.md` 生产要求同步。
  - 已提交（`96298f6`）并推送。
- **F10 跨域统一幂等 / 限流接入范式（2026-09-16）**：O7 的触发条件已经真实发生——钉钉与飞书两个入站桥各自复制了一份进程内 `ConcurrentHashMap` 去重（两处 Javadoc 都写着「生产多副本需换 Redis/JDBC」），而同一个 `channel-service` 里两个 Kafka listener 早已在用 `ProcessedEventStore`。
  - **幂等**：新增 `InboundIdempotency`（platform-eventbus），把入站回调的正确顺序收在一处——`markProcessed` 的原子返回值**抢占** → 交渠道线程池异步处理 → 失败或线程池拒收时 `releaseClaim` **归还**。入站不能照抄 Kafka listener 的「先查后标记」：控制器 3s 内必须 ack、处理是异步的，两步之间的窗口会让并发重投在多副本上各处理一次。原先「先 put 进 map 再处理」更糟：处理抛异常时消息被永久标记为已处理，渠道重投也进不来 = 静默丢客服提问；两个 bridge 的 `process()` 因此改为向上抛异常。
  - `ProcessedEventStore` 新增 `releaseClaim`（内存 remove / JDBC DELETE），`InMemoryProcessedEventStore` 改为**有界窗口**（`LinkedHashMap` + `removeEldestEntry`，默认 10 万条 FIFO 淘汰）——入站回调持续产生新 id，原来的无界 map 等于内存泄漏。key 形如 `inbound:<source>:<messageId>`，前缀避免钉钉 msgId 与飞书 messageId 在同一张 `PROCESSED_EVENT` 表里互撞。多副本仍需 `CHANNEL_DEDUP_STORE=jdbc`（Helm values 已注明）。
  - **限流**：`EdgeRateLimitFilter` 此前对 `EdgeOpenPaths` 整体 bypass，于是 `/auth/login|register|refresh` 与 `/channel/{feishu,dingtalk}/events` 是公网上唯一无速率保护的面，而每条渠道事件都会触发下游检索 + LLM 花费。改为按**客户端 IP** 限桶：`auth=30`、`channel-callback=600`（`RATE_LIMIT_AUTH_QPM` / `RATE_LIMIT_CHANNEL_CALLBACK_QPM`），`/actuator/**`、`/health`、`/.well-known/**` 仍不限（限流探针会误伤存活检查）。默认**不信任** `X-Forwarded-For`（可伪造 = 每请求换个假 IP 就绕开），只有边缘前面确有会覆写该头的可信 LB 时才设 `RATE_LIMIT_CLIENT_IP_HEADER`。
  - **防漂移用结构而不是脚本**：`EdgeOpenPaths` 从一串 `||` 改成「路径 → 限流 family」的 `Map.of` 表 + 探针前缀白名单。`Map.of` 不接受 null value，因此「新增了免鉴权路径却忘了给限流」在结构上不可能发生。
  - 验证：新增 `InboundIdempotencyTest`(7)、`EdgeRateLimitFilterTest`(10)，`ProcessedEventStoreTest` +3（有界窗口 / 两种实现的归还语义），`EdgeOpenPathsTest` +2，两个 bridge test 各 +2（多副本共享 store 去重、处理失败后重投能重来）。`mvn -B -o -pl platform-security,platform-eventbus,edge-gateway,channel-service,auth-service -am test` 全绿（platform-security 62 / platform-eventbus 20 / auth-service 95 / channel-service 69 / edge-gateway 70）；`helm template`、`docker compose config`、`deploy/test-observability-config.sh` 均 PASS。
  - 文档：`docs/平台工程/eventbus-guide.md` 新增 2.2.1「入站回调的幂等」并补内存窗口口径，`docs/参考/operations.md` 网关限流一节补免鉴权 family 表与 XFF 说明，`docs/互操作渠道/dingtalk-guide.md` 改写去重与白名单段，`docs/参考/架构文档.md`、`docs/参考/api-reference.md` 同步。
  - 已提交（`49202ac`）并推送。
- 共享栈已重建并在跑：MySQL 8.4 `:43306`、PostgreSQL 16 `:45432`、Redis 7 `:46379`、Kafka 3.8 `:49092`、MinIO `:49000`、Nacos 2.4.3。未起 RabbitMQ、marketing-obs、Kibana。
- 数据恢复：langchain4j `deploy/.dev-infra-state/20260908-110350`；workflow `pg_restore` 53 张表；risk MySQL dump 17 张表。
- 门户 10 个项目此前已逐个起过并探活通过（12GiB 不能并存）。
- **seed-mock（2026-09-14）**，优先复用各仓已有脚本：
  | 项目 | 做法 | 入库结果 |
  |---|---|---|
  | marketing | `scripts/seed.sh` | 3 活动、权益/受众/旅程闭环 mock |
  | wms | `test-data/init-test-data.sh` | Cell A/B 主数据 + 入库 6 / 出库 3 / 履约 3，`DATA_VERIFY=SUCCESS` |
  | transaction-center | `scripts/seed-demo-business.py` | 3 订单、2 支付、4 退款；wipe 后旧清单已挪到 `demo-business-manifest.json.pre-wipe-20260914` |
  | drools | console `ACTIVITY_MARKETING_SEED_CATALOG_DATA=true` | `__dev__` 2 店 6 商品、`acme` 2 店 4 商品、行政区 3212 |
  | recsys | `import-items` + `seed-ads --ads=200 --advertisers=20` | item 9742；广告主 20（分片 10+10）、广告 200、创意 600、竞价词 614 |
  | benefit | 新增幂等 `scripts/seed-mock.sql` | tenant=`dev-tenant`：2 SKU、2 发放单、1 待补发 |
  | recon | 新增幂等 `scripts/seed-mock.sql` | 场景定义 2；`recon_src_*` 各 2 行；现金 ODS 应发 1 |
  | workflow | 沿用 9 月 8 日 restore | 历史流程 4、流程定义 4（运行中待办 0） |
  | risk | 沿用 dump | 案件 2、决策 2、规则草稿 4；引擎内 `MARKETING_AWARD` 绑定不在 DB |
  | langchain4j | 跳过（已 restore） | — |
- **knowledge-service 百炼拉起（2026-09-15）**：先前 `RAG_EMBEDDING_BASE_URL` 为空导致 crash loop、检索 500。已 `source load-bailian-env.sh` 后 `--force-recreate knowledge-service`；embedding 指向百炼 `text-embedding-v4`。顺带拉起停着的 `lc4j-qdrant` / `lc4j-elasticsearch`。网关未登录打 `/rag/query` 现为 401（服务可达），不再 500。
- **侧栏子菜单不跳转（2026-09-15）**：上次「留在工作台只滚动」不符合预期。已改回与 Agent/工作流一致：点「上传文档」等子菜单整页进入该能力的 `CapabilityRunner`；对话模式仍留在控制台只切模式。相关 vitest 57 项通过。前端容器已按此行为重建，网关仍烘焙 `http://localhost:18080`。

## 当前运行

- 能力门户 `:5274` + Casdoor `:8000` + SpiceDB
- 共享 `dev-infra` core + **lc4j Qdrant / Elasticsearch**
- langchain4j 栈（含已起来的 `knowledge-service`、网关 `:18080`、前端 `:8093`）
- **交易** `trade-api`（演示库已有业务单）
- **Drools MySQL** `:3307`（目录数据在卷里；console 已停）
- **recsys Postgres** `:55432` + Redis `:56379`（电影/广告在卷里）
- WMS 整栈已停（种子在 `wms-local_*` 卷，可再起）

## 未完成 / 已知缺口

- 12GiB Docker VM **无法同时**跑满 10 个项目控制台。
- recsys `ad_embedding=0`：未跑 `backfill-embedding`（需 embedding provider）。
- risk 管理面 `source_rule_binding` 为空；发布绑定需走治理 API，不能拿 E2E 草稿当线上规则。
- workflow 无运行中待办；要新待办需起审批服务走真实发起，不能手插 Flowable 运行表。
- WMS 接口校验跳过：缺 `WMS_TEST_BEARER_TOKEN`，匿名被拒。
- marketing `offer-decision-service` 在 generation 未激活时仍 503。
- 9 月 8 日之后写进旧 Docker 卷的数据无法恢复。

## Git 发布

- `a5a5ef7` `fix(frontend): restore sidebar capability page navigation`
- `f8d0be6` `feat(knowledge): recover ingestion jobs and parallel retrieval`
- `d1548ec` `feat(deploy): add shared dev-infra compose entry`
- `488f122` `fix(runtime): isolate Redis keys and Kafka consumer groups`
- 远程 `origin/main` 已快进到 `488f122`。
- `f0ef44c` `feat(frontend): overlay RAG runtime honesty on the catalog`
- 远程 `origin/main` 已快进到 `f0ef44c`。本地工作树已无待提交代码。

## 下一步（一次只起一个控制台）

```bash
unset COMPOSE_PROJECT_NAME

# 交易控制台
cd /Users/liruijun/personal/LLM/transaction-center
docker compose --project-directory deploy -f deploy/compose.yaml --env-file deploy/.env.demo up -d --no-build --wait

# Drools UI（目录已在库）
cd /Users/liruijun/personal/LLM/drools-demo
docker compose -f deploy/docker-compose.yml up -d --no-build

# recsys UI（item/广告已在库；embedding 仍空）
cd /Users/liruijun/personal/LLM/recsys
scripts/dev-local.sh up

# WMS（种子已在隔离卷）
cd /Users/liruijun/personal/LLM/wms-platform
docker compose --env-file .env -f compose.yaml up -d

# 权益/对账幂等补种
bash /Users/liruijun/personal/LLM/benefit-center/scripts/seed-mock.sh
bash /Users/liruijun/personal/LLM/recon-platform/scripts/seed-mock.sh
```

`COMPOSE_PROJECT_NAME` 只在调用共享 `dev-infra/compose.yaml` 且 cwd 不是该目录时设为 `dev-infra`。不要重跑 langchain4j `manage.py backup`。

## 恢复 Prompt

请读取 CODEX_PROGRESS.md。共享库与门户项目测试数据已按 seed-mock 写入。继续时按「下一步」只起一个控制台；未经授权不提交或推送。
