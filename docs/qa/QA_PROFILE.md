# QA 环境档案（供 /qa-test 使用，人工可改）

> 首次由 /qa-test 勘察生成（2026-07-18）。跑功能测试前先读本文件；测试中发现的新环境知识回写到这里。

## 入口与探活

| 项 | 值 |
|---|---|
| 唯一对外入口 | edge-gateway `http://localhost:8080`（所有 API 经它转发） |
| 健康检查 | `curl -s localhost:8080/actuator/health` |
| 前端（capability showcase） | `http://localhost:8093` |
| 各微服务 | 8081–8093（不直连，QA 一律走网关；order-service 宿主机映射 8094） |

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
