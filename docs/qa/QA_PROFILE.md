# QA 环境档案（供 /qa-test 使用，人工可改）

> 首次由 /qa-test 勘察生成（2026-07-18）。跑功能测试前先读本文件；测试中发现的新环境知识回写到这里。

## 入口与探活

| 项 | 值 |
|---|---|
| 唯一对外入口 | edge-gateway `http://localhost:18080`（本机 `EDGE_HOST_PORT` 映射；所有 API 经它转发） |
| 健康检查 | `curl -s localhost:18080/actuator/health` |
| 前端（capability showcase） | `http://localhost:8093` |
| 各微服务 | 8081–8095（不直连，QA 一律走网关；vision-service 宿主机映射 18090、order-service 映射 8094、tax-service 映射 8095） |

## 启动方式

- 整套栈：`docker compose -f deploy/docker-compose.yml up --build`（或 `deploy/start-dev.sh` / `start-local.sh` / `start-all.sh`）
- 单服务：`mvn -pl <svc> spring-boot:run`（如 conversation-service :8081、edge-gateway :8080）
- 前端：`cd capability-showcase-frontend && npm run dev`（predev 自动生成 catalog）
- 变体栈：`deploy/docker-compose.{es|rag-full|failover|oracle}.yml`

## 测试凭据（edge-gateway `application.yml`，仅本地 dev）

| Key | 租户/用户 | scopes | QA 用途 |
|---|---|---|---|
| `dev-key-acme` | acme / alice | chat, ingest, approve, agent, channel, eval, vision, voice | 主力正向用例 |
| `dev-key-globex` | globex / bob | **仅 chat** | **越权用例**：打 agent/rag/eval 等应 403 |
| `dev-key-tenantA-admin` | tenantA / analyst-a | （见配置） | 租户隔离用例 |

- 无 key → 401；租户隔离：acme 写入的数据 globex 查不到。
- 登录会话路径：auth-service `/auth/login` 签发 Bearer；管理面（role-admin scope）只走登录会话，不挂 api-key。
- `INTERNAL_JWT_SECRET` 需 ≥32 字节（dev 默认值已满足）。

## 外部依赖与成本

- LLM 调用统一走 LiteLLM（`deploy/litellm/config.yaml`），`chat-default` 默认映射 DeepSeek，
  需 `DEEPSEEK_API_KEY`（**真实费用**）；可改指本机 Ollama 零成本。
- **断言约定**：LLM 生成内容不断言原文，只断言结构（状态码、JSON 字段存在性、SSE 帧序列、错误码语义）。

## 测试素材

- README.md —— 每个接口都有可直接运行的 curl 示例（权威）
- `docs/参考/api-reference.md`、`docs/参考/capabilities.md`、`docs/scenarios.md`
- `deploy/langchain4j-platform.postman_collection.json`
- `deploy/smoke-*.sh`（rag / a2a / nl2sql / rbac / failover / es-hybrid 等现成冒烟脚本）

## QA 关注点（项目特有）

- **feature-flag 大量默认关闭**（RAG 增强、GraphRAG、JDBC 持久化、Casdoor SSO、vision/voice…）：
  关着时接口应优雅降级而不是 500，本身就是一类用例。
- **SSE 流式**：`/chat/stream`、`/agent/tasks/**`、`/interop` A2A stream；`/async/tasks/**` 支持断点续订。
- **多租户**是核心横切面：每个写接口都值得配一条跨租户读的隔离用例。
- 默认实现全内存 → 重启数据即清空；需要持久化的用例记得开对应开关（如 `ASYNC_TASK_STORE=jdbc`）。

## 回归沉淀

- 平台自带 **eval-service**（`/eval/**`）：HTTP 回归客户端，可加载 baseline suite、做响应/oracle 断言、
  输出 JSON report。跑稳的高价值用例往这里沉淀，变成可重复回归集。

## 已知坑

- 8093 端口前端和 order-service 容器内端口重叠（宿主机 order 映射 8094），探活别搞混。
- tax-service 容器内 8094、宿主 8095；业务验收仍从 edge-gateway 的 `/tax/**` 进入。
- Playwright 浏览器二进制首次需联网安装。
- **（0718）本机 edge 网关宿主端口是 18080 而非 8080**（8080 被 apollo-configservice 占，
  见 `deploy/.env` 的 `EDGE_HOST_PORT`）；mysql 宿主映射 13307（3306 被本机 MySQL 占）。
  `deploy/.env` 的这两行是本机永久约束，**勿删**——删了 compose 重建会端口冲突翻车。
- **（0718）edge 默认 `EDGE_CASDOOR_MODE=only`**：legacy Bearer/API Key 一律 401（上表 dev-key-*
  在 only 模式下全部失效）；需要 legacy 凭据的 QA 先临时在 `deploy/.env` 设 `dual` 并
  `docker compose up -d --no-deps edge-gateway`（**必须 --no-deps**，否则连带重建依赖容器）。
- **（0718）前端 dev 5173 可能双监听串台**：recsys console vite 占 `[::1]:5173`，showcase
  `--host` 实例占 `*:5173`——`localhost:5173` 会解析到 IPv6 打进 recsys，
  **QA 一律用 `http://127.0.0.1:5173`**。带 Origin 的浏览器请求经 vite 代理已剥 Origin
  （vite.config stripOrigin），不再触发网关 CORS 403；纯 curl 不带 Origin 测不出 CORS 类问题。
- **（0718）登录凭据**：alice/bob/analyst-a 演示账号密码 `demo12345`（auth-service 种子，
  `.env.local` 注入 VITE_DEMO_PASSWORD 后登录页演示卡可一键登录）。

## 2026-07-22 全能力体检现场

- Compose 当前 25 个容器均为 `running`；edge `:18080`、conversation `:8081`、order `:8094`
  以及前端 `:8093` 的只读探活均成功。
- 业务服务 readiness 探测中，conversation/workflow/analytics/knowledge/agent/async/auth/channel/
  interop/eval/vision/voice/order/config/edge 均为 `200 UP`。vision 的宿主端口是 `18090`，不要误用
  容器端口 `8090`。
- 本机 LiteLLM、Qdrant、Elasticsearch、Ollama、Casdoor OIDC discovery 均可达；当时 Ollama 已有
  `nomic-embed-text`、`qwen2.5vl`、`llama3.1` 等 7 月 22 日配置所需模型（当前默认已切百炼，见下文）。
- 历史现场中 Voice 曾出现“部署开关已开、静态目录仍关闭、provider 凭据为空”的三方漂移。
  2026-07-29 已加入百炼原生 ASR/TTS adapter，并把部署开关、目录状态、依赖凭据纳入同一回归。
- 当前 edge 为 `EDGE_CASDOOR_ENABLED=true` + `EDGE_CASDOOR_MODE=only`；Casdoor 本地 discovery
  可达。不得用 legacy `dev-key-*` 直接跑网关正向用例，除非测试方案明确临时切 `dual`。
- 当前工作区有未提交改动，运行容器并非全部由当前源码统一重建；正式黑盒测试前需先确认是测试
  “现有运行镜像”还是“当前工作区源码重建后的镜像”，避免把版本漂移误报为功能缺陷。

### 2026-07-22 全能力体检新增环境知识

- Compose 虽要求业务请求经 edge，但 conversation 等下游端口直接发布到宿主机，且
  `InternalTokenAuthFilter` 只绑定身份、不强制拒绝无内部 JWT 的请求；直连 `/chat` 会以
  `anonymous` 身份真实调用模型。QA 不应把直连 200 当作正常鉴权，生产必须关闭端口暴露或强制内部认证。
- `workflow-service` 的 HTTP AI 客户端默认 `CONVERSATION_BASE_URL=http://localhost:8081`；当前 Compose
  未覆盖该变量，容器内会连接自身 localhost 并降级。整栈测试前应显式配置
  `http://conversation-service:8081`。
- edge 为 Casdoor `only` 时，`eval-service` 的 `EVAL_API_KEY=dev-key-acme` 无法访问受保护目标；
  retrieval 客户端还会把 401 吞成空命中。此模式下评测服务需要内部 JWT/服务身份，不能继续依赖 legacy API key。
- `RAG_MULTIMODAL_ENABLED=true` 仍要求容器可达的 provider。标准百炼加载器会配置原生
  `qwen3-vl-embedding`；绕过加载器手工启动时，缺少 base URL/key 会 fail-fast。
- Obsidian 双链以 `docId` 写入 GraphStore，而文档删除路径按 `displayName#` 清理，删除笔记后会残留
  图谱三元组；清理验证必须额外查 `/rag/graph/query`。
- 单独 `--force-recreate` auth 等后端容器后，edge 可能继续缓存旧 Docker IP 并返回 500；本机确认
  重建 edge 后恢复。滚动重建测试需检查 edge 的下游 DNS 刷新行为。
- 通用异步任务 `DELETE /async/tasks/{id}` 是“取消”而非物理删除；终态 QA 任务无法经 API 清除，
  必须使用专用测试库/定期保留策略并记录残留。
- 当前浏览器控制运行时可能没有可用实例；此时真实 UI 用例标 BLOCKED，不得用源码断言冒充 UI 黑盒结果。

### 2026-07-22 修复后运行基线

- 上述条目保留为缺陷发现时的历史现场。当前业务服务默认 `INTERNAL_AUTH_REQUIRED=true`、
  `INTERNAL_API_KEY_FALLBACK=false`；13 个下游宿主端口无凭据访问业务路径均返回 401，health/info 仍开放。
- Workflow Compose 已固定 `CONVERSATION_BASE_URL=http://conversation-service:8081`；真实退款流程完成且无降级日志。
- Eval 默认不再配置 legacy `EVAL_API_KEY`。可信 edge 回调使用带专用用途声明的短时服务令牌，并限制可信 origin；
  目标鉴权/网络异常显式失败，不再吞成空检索结果。
- 此处是 7 月 22 日的历史基线。7 月 29 日标准本地百炼启动已改为开启 Voice 和图片向量；
  application.yml/Helm 的无凭据默认仍关闭，显式开启但 provider 配置缺失时启动 fail-fast。
- edge DNS/连接池使用短 TTL/生命周期；受控占用旧 IP 后重建 auth-service，确认新容器 IP 已变化，
  edge 容器未重启即在第 5 次轮询恢复 200。
- 本轮再次连接浏览器控制运行时，实例列表仍为空；真实 UI 仍标 `BLOCKED`。前端 HTTP、552 项测试、类型检查和生产构建已通过。

## 2026-07-27 指标评测预检

- 当前 Compose 栈未运行，edge `:18080`、conversation `:8081`、analytics `:8083`、
  knowledge `:8084`、agent `:8085`、eval `:8089` 均不可达。
- 本机已安装 Ollama，模型目录约 32 GB，已有 `nomic-embed-text`、`llama3.1`、`qwen3:8b/14b`
  等模型，但 Ollama 服务当前未启动。
- 当前 shell 已配置 `DEEPSEEK_API_KEY`，未配置 `JINA_API_KEY`；因此可测默认 DeepSeek/LLM
  reranker，不可把本轮结果表述成 Jina Reranker 实测。
- 本机没有 k6；已有 Node.js、`jq`，可用项目内 Node 基准脚本采样延迟并计算 P50/P95/平均值。
- 本轮评测目标和审批卡点见 `docs/qa/resume-metrics-0727-1539/QA_PLAN.md`。测试只允许访问
  localhost；完整测试会产生多次 DeepSeek API 调用，执行前必须确认成本策略。

## 2026-07-27 简历指标评测结果

- 完整报告和可直接使用的简历表述位于
  `docs/qa/resume-metrics-0727-1539/QA_REPORT.md` 与 `RESUME_BULLETS.md`。
- 200 条项目文档合成集上，向量单路 Recall@10 为 80.0%，vector + keyword + ES BM25 +
  graph 四路 RRF 为 98.5%；本轮没有 Jina 凭据，不能归因于 Jina Reranker。
- 150 条固定种子库 NL2SQL 集上，最终执行成功率 99.3%，结果正确率 95.3%；当前响应不暴露
  attempt，不能报告首次执行成功率。
- 6 节点冷请求各 5 次：线性平均 61.7 秒，并行平均 39.4 秒。20 题 Replanner A/B 的外部
  Rubric 为 70%→75%，但开启组出现 1 次 500 且 P95 增至 140.3 秒，不作为简历质量亮点。
- 30 组完全相同问题受控重复测试中，冷均值 2463.4 ms，命中后均值 6.7 ms、P95 10 ms。
  为排除 LiteLLM L2 干扰，测试前删除了 425 个本地 `litellm.cache:*` 临时键。
- 评测结束时 Compose 服务与 Ollama 保持运行；agent-service 恢复为 Replanner 开启配置。

## 2026-07-29 百炼模型栈更新

- 当前运行栈 embedding 为百炼 `text-embedding-v4`（1024 维），collection 基名
  `knowledge_segments_bailian_v4`；旧 Ollama 768 维集合保留用于回滚。
- rerank 为百炼 `qwen3-rerank`；视觉逻辑名 `vision-default` 映射百炼 `qwen3-vl-plus`。
- 真实 embedding 迁移计数、rerank 请求、LiteLLM 视觉请求和 `/vision/caption` 端到端请求均通过。
- Ollama 现在只承担 `chat-default-fallback` 的 `llama3.1`；7 月 22/27 的 Ollama embedding/vision
  描述是当时测试快照，不代表当前默认配置。

## 2026-07-29 Chat Stream 页面预检

- 页面目标为 `http://127.0.0.1:8093/m/chat/chat.stream`；静态深链与 catalog 均为 200，
  catalog 中 `chat.stream` 为 `ready / POST /chat/stream / sse`。
- 当前 edge 是 Casdoor `only`，匿名和 legacy `dev-key-acme` 调 `/chat/stream` 都返回 401；
  页面正向流必须用真实 OIDC 浏览器会话，不能拿 legacy API key 结果冒充。
- 本轮浏览器运行时发现零个可用实例，UI 真点用例必须标 BLOCKED；底层 internal-token SSE
  冒烟为 200、三段 token、`done`、4.24 秒。
- 新确认 P1：conversation 的 RAG read timeout 为 3 秒，但当前 LLM rerank + hybrid/ES/graph
  配置让 `/rag/query` 约 43 秒才完成；连续两次 20 秒客户端超时，每个观察 trace 有 103
  个 `llm.request` audit event。conversation 因此静默退回无 RAG 回答，下游仍继续执行。
- 证据与复现见 `docs/qa/chat-stream-page-0729-1513/QA_REPORT.md`。

## 2026-07-29 多模态访问复检

- Chrome 现有 Casdoor 会话可正常进入 `/m/multimodal/**`，身份为 `alice / acme / Bearer`。
- edge、vision、voice 健康检查均为 `UP`，但健康不代表视觉 provider 凭据已就绪。
- 初始 LiteLLM 容器未注入 `BAILIAN_API_KEY`；`vision-default` 没有视觉 fallback，因此
  `/vision/caption` 连续两次返回 500 `AuthenticationError`。修复跨 shell 凭据加载、增加
  百炼模型预检并重建 LiteLLM 后，Chrome `/vision/caption` 与 API `/chat/vision` 均返回 200。
- 百炼实时目录确认 `qwen3-vl-plus` 存在，故保留该模型；根因是凭据注入而非模型下架。
- 后续原生 adapter 回归已通过：`/voice/transcribe`、`/voice/chat`、`/rag/image`、
  `/rag/image-search` 均经 edge 返回 200；Chrome 目录显示语音/图片检索“就绪”、图片入库
  “需授权”。完整证据见 `docs/qa/multimodal-access-0729-2318/QA_REPORT.md`。

## 2026-07-29 旧 Java Agent 异步镜像验证（范围更正）

- Chrome 控制运行时本轮可用；通过 Casdoor OIDC 的 `alice / acme` 真实会话完成 UI 黑盒测试。
  Casdoor 租户用户口令与 auth-service demo 账号不是同一套；本机 provision 的 alice 使用
  auth-platform 租户种子凭据。
- 本节只描述旧 Java `agent-service` 的 external enabled + non-authoritative 镜像模式，
  不代表独立 `agentscope-platform` 的迁移实现。旧 Agent 本地执行，中心
  `async-task-service` 使用 JDBC 持久化同一 taskId。
- 真实 Agent 任务在本地与中心均为 SUCCEEDED，中心 SSE 在重启前后都完整回放
  PENDING/RUNNING/SUCCEEDED，证明任务与事件日志持久化有效。
- 旧 Java 镜像 kind 为 `agent.task`，但中心 `/async/tasks/{id}/events` 的允许集合不含该值；
  Agent 侧也没有转发 `AgentTaskProgressEvent`。因此细粒度进度尚未迁移，详见
  `docs/qa/agent-async-migration-0729-1639/QA_REPORT.md`。
- AgentScope 候选使用 `agent.run`、`agent.dag` 等真实 kind，DAG 细粒度事件已通过；
  正确验收报告位于同级仓库
  `../agentscope-platform/docs/qa/agentscope-migration-acceptance-0729-1653/QA_REPORT.md`。

## 2026-07-30 全项目集中回归环境

- 本机 Docker client 29.5.2 可用，但 Docker daemon socket 不存在；Compose 只能做
  `config` 静态验证，不能启动真实依赖。
- edge `:18080`、前端 `:8093` 和 AgentScope `:8085` 均不可达，HTTP/SSE/UI 黑盒用例标
  `BLOCKED`，不得用单元测试或 Helm 渲染冒充端到端通过。
- 无需 Docker 的全量 Java、跨仓契约、Flowable/H2、Embedded Kafka、前端和 Python 门禁均已
  通过；完整证据见 `docs/qa/overall-project-0730-1401/QA_REPORT.md`。

## 2026-07-30 Chrome 真实整栈复检

- Docker Desktop 恢复后已用 `deploy/start-all.sh --recreate` 重建标准本地栈；Chrome
  Casdoor 会话为 `alice / acme / Bearer`。
- 浏览器覆盖全部 9 模块。同步/SSE Chat、RAG 查询与 JSON 入库回查、AgentScope ReAct 与
  自动规划 DAG、异步任务、NL2SQL、退款工作流、视觉、MCP、A2A 和渠道发现均通过。
- 标准 Compose 没有运行 `eval-service`，但目录把 Eval 12 项全部标成 ready；浏览器检索评测
  稳定复现“无法连接服务”，记 P1。
- Knowledge split 的 MinIO 和 bucket init 成功，query 角色可启动；ingest-api/worker 因 AWS
  S3 client 缺少 `TlsSocketStrategy` 退出，S3 原文闭环未通过。默认 ingest API 宿主端口
  18085 还会与 AgentScope 冲突。
- Chrome 扩展未开启文件 URL 权限，本轮 multipart Voice 上传受浏览器环境阻塞；Base64 视觉
  仍真实调用百炼并返回 200。
- 完整用例、trace、测试数据和 Go/No-Go 见
  `docs/qa/overall-project-0730-1401/CHROME_REPORT.md`。
