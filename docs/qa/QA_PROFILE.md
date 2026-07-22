# QA 环境档案（供 /qa-test 使用，人工可改）

> 首次由 /qa-test 勘察生成（2026-07-18）。跑功能测试前先读本文件；测试中发现的新环境知识回写到这里。

## 入口与探活

| 项 | 值 |
|---|---|
| 唯一对外入口 | edge-gateway `http://localhost:18080`（本机 `EDGE_HOST_PORT` 映射；所有 API 经它转发） |
| 健康检查 | `curl -s localhost:18080/actuator/health` |
| 前端（capability showcase） | `http://localhost:8093` |
| 各微服务 | 8081–8094（不直连，QA 一律走网关；vision-service 宿主机映射 18090、order-service 映射 8094） |

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
- 本机 LiteLLM、Qdrant、Elasticsearch、Ollama、Casdoor OIDC discovery 均可达；Ollama 已有
  `nomic-embed-text`、`qwen2.5vl`、`llama3.1` 等当前配置所需模型。
- 当前 Compose 把 `VOICE_ENABLED` 置为 `true`，但静态能力目录仍将 3 项 voice 能力标为
  `flag-off`，前端执行闸门会阻止调用；同时 voice 容器的 `VOICE_API_KEY` 为空、base URL 为云端
  OpenAI，实际调用预计 401。全能力测试需把“部署开关、目录状态、依赖凭据”三者作为独立断言。
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
- `RAG_MULTIMODAL_ENABLED=true` 但未提供容器可达的 `RAG_MULTIMODAL_BASE_URL` 时，会回落到
  `localhost:8000` 并让 `/rag/image*` 返回 500。无多模态 embedding 服务时应关闭开关。
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
- 图片向量与 Voice 的后端开关、Compose 和前端目录统一默认关闭；图片向量显式开启但 base URL 为空时启动 fail-fast。
- edge DNS/连接池使用短 TTL/生命周期；受控占用旧 IP 后重建 auth-service，确认新容器 IP 已变化，
  edge 容器未重启即在第 5 次轮询恢复 200。
- 本轮再次连接浏览器控制运行时，实例列表仍为空；真实 UI 仍标 `BLOCKED`。前端 HTTP、552 项测试、类型检查和生产构建已通过。
