# 开发者指南

## 代码组织

项目是 Maven multi-module：

```text
platform-*              共享库
*-service               独立微服务
edge-gateway            业务边缘网关
deploy/                 本地 compose 和 smoke 脚本
docs/                   项目文档
```

新增业务能力时优先判断它属于哪个限界上下文：

- 登录 / 会话 / RBAC：`auth-service`
- 对话编排：`conversation-service`
- 知识库/RAG（四路混排 ES）/GraphRAG：`knowledge-service`（文档级 ReBAC 判权委派外部 auth-platform，默认关）
- Agent/DAG：`agent-service`
- 通用异步状态：`async-task-service`
- 审批流程：`workflow-service`
- 数据分析：`analytics-service`
- 渠道：`channel-service`
- 对外互操作：`interop-service`
- 回归评测：`eval-service`
- 多模态视觉：`vision-service`
- 语音闭环（ASR→对话→TTS）：`voice-service`
- 能力展示前端（独立 Vite/Vue3，非 Maven 模块）：`capability-showcase-frontend`

## 新增跨服务接口

推荐流程：

1. 先在 `platform-protocol` 定义 request/reply DTO。
2. 在服务内新增 controller/service。
3. 调用方通过 HTTP client 依赖协议 DTO，不直接依赖对方 service module。
4. 使用 `platform-security` 的出站拦截器透传内部 JWT。
5. 使用 `platform-observability` 透传 trace id。
6. 补 controller/service 单测。

避免：

- 一个服务直接引用另一个 service module 的实现类。
- 在下游服务重新解析外部 API key。
- 把 DTO 散落在调用方模块里。

## 新增服务模块 checklist

- `pom.xml` 加入父工程 modules。
- 增加 `Dockerfile`。
- 增加 `src/main/resources/application.yml`，显式端口和 actuator health。
- 接入 `platform-security`。
- 接入 `platform-observability`。
- 如调用 LLM，使用 `platform-gateway-client`。
- 如有跨服务协议，加入 `platform-protocol`。
- 在 `edge-gateway` 增加 route（如需前端浏览器直调，同时把前端 origin 加进 `GATEWAY_CORS_ORIGINS`）。
- 在 `deploy/docker-compose.yml` 增加 service（如需一键脚本拉起，核对 `start-all.sh`/`start-local.sh` 的服务过滤）。
- 在 `docs/参考/capabilities.md`、`docs/参考/架构文档.md`、`docs/参考/api-reference.md`、`docs/参考/operations.md` 更新说明；若引入新存储，同步 `docs/参考/databases.md` 与 `docs/平台工程/数据库与中间件清单.md`。

## 测试策略

按风险分层：

- 纯逻辑：普通 JUnit 单测。
- controller：MockMvc 或直接 controller 单测。
- 跨服务 client：mock server 或 mocked RestTemplate。
- 存储：H2/JDBC focused tests。
- 全局回归：`mvn test`。
- 本地集成：`docker compose -f deploy/docker-compose.yml config` 和 smoke 脚本。

常用验证命令：

```bash
mvn -pl knowledge-service -am test
mvn -pl agent-service -am test
mvn test
mvn -DskipTests package
docker compose -f deploy/docker-compose.yml config
```

## 配置原则

- 默认配置应适合本地开发和单测。
- 生产依赖通过环境变量覆盖。
- 新增开关默认尽量保守，尤其是 code execution、browser、真实 webhook、外部 provider。
- 涉及持久化的能力提供 in-memory 默认实现，方便测试。
- 对外超时、重试、batch size、TTL 应配置化。

## 安全约定

- 外部只进入 `edge-gateway`。
- 下游服务只认 `X-Internal-Token`。
- scope 判断放在实际拥有资源的服务里。
- 文档级细粒度授权（ReBAC）委派**外部 auth-platform IAM**（SpiceDB）：`knowledge-service` 经 SDK `@CheckAccess` 判权，超出本平台的 scope 模型（scope 管「能不能调这类接口」，ReBAC 管「能不能看这份文档」）；**默认关**（`RAG_AUTHZ_MODE=disabled`），仅 enforce 档真过滤。身份侧另有默认关的 Casdoor SSO（edge Bearer 换发内部 JWT，带可选 `dept` claim 供部门级隔离）。
- webhook 不转发内部 JWT。
- code execution、browser、MCP 等高风险能力默认关闭。

## 文档约定

每个新能力落地后至少更新：

- `docs/参考/capabilities.md`：能力矩阵和限制。
- `docs/参考/api-reference.md`：新增或变化的 HTTP API。
- `docs/参考/架构文档.md`：如果引入新服务、新存储或新调用链。
- `docs/参考/operations.md`：如果新增环境变量、端口、启动依赖。
- `docs/参考/databases.md` / `docs/平台工程/数据库与中间件清单.md`：如果引入新的数据库/中间件/落库开关。
- `docs/README.md`：如新增专题文档，补索引链接。
- `docs/迁移/migration-roadmap.md`：更新已完成/待办状态。

## 提交建议

保持 commit 粒度和功能边界一致：

```text
feat(knowledge): ingest images via native CLIP multimodal embedding
feat(agent): propagate async task cancellation
docs(platform): add architecture and capability guides
test(async-task): cover lease expiry reclaim
```

避免把多个不相关能力塞进一个 commit。每个 commit 至少能说明：

- 改了哪个 bounded context。
- 新增或修复了什么行为。
- 如何验证。
