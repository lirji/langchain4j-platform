# 仓库指南

## 项目结构与模块组织

这是一个基于 Java 21、Spring Boot 3.3 的多模块 Maven 平台。根目录下的 `pom.xml` 是聚合配置文件，同时也负责统一管理依赖版本。共享库位于 `platform-*` 模块中：`platform-security`、`platform-observability`、`platform-gateway-client`、`platform-protocol`、`platform-audit`、`platform-metering` 和 `platform-eventbus`（跨服务事件总线抽象）。运行时服务包括 `auth-service`（账号登录 + RBAC，:8092）、`conversation-service`、`knowledge-service`（RAG 四路混排，含 Elasticsearch BM25 全文）、`agent-service`、`analytics-service`、`workflow-service`、`async-task-service`、`channel-service`、`interop-service`、`eval-service`、`vision-service`（图像描述，:8090）、`voice-service`（语音闭环 ASR→对话→TTS，:8091）和 `edge-gateway`。另有可选的 `config-server`（Spring Cloud Config），以及前后端分离的独立前端 `capability-showcase-frontend`（Vite/Vue3 静态 SPA，:8093，不属于 Maven 构建）。近期新增外部授权面：`edge-gateway` 叠加 Casdoor OIDC SSO 换发内部 JWT、`knowledge-service` 支持文档级细粒度授权（均接外部 auth-platform IAM，默认关）。

每个模块都遵循标准的 Maven 目录结构：代码位于 `src/main/java`，配置位于 `src/main/resources`，测试位于 `src/test/java`。部署相关资源位于 `deploy/` 目录；更全面的说明文档位于 `docs/` 目录。

## 构建、测试与开发命令

* `mvn test`：运行所有模块的完整测试套件。
* `mvn -DskipTests package`：构建所有模块并生成服务产物。
* `mvn -pl knowledge-service test`：运行单个模块的测试。
* `mvn -pl agent-service -am test`：运行指定模块及其所需的上游依赖模块测试。
* `docker compose -f deploy/docker-compose.yml up --build`：启动 `README.md` 中描述的本地平台栈。
* `bash deploy/smoke-qdrant-rag.sh`：运行 Qdrant RAG 冒烟测试。

## 编码风格与命名规范

使用 Java 21，并遵循各模块中已有的 Spring 编程习惯。包名统一放在 `com.lrj.platform.<module>` 下。使用 4 个空格缩进，类名应具有明确含义，并使用常见的 Spring 组件后缀，例如 `Controller`、`Service`、`Config`、`Properties`、`Client`、`Store` 和 `Test`。优先使用构造器注入；在实际可行的情况下，请使用不可变的请求和响应模型。跨服务 DTO 应放在 `platform-protocol` 中，避免重复定义接口契约。

## 测试规范

测试通过 Spring Boot 测试依赖，以 JUnit 风格由 Maven 执行。测试代码应放在各自模块的 `src/test/java` 目录下，并与生产代码保持相同的包路径。单元测试和控制器测试命名应使用 `*Test` 后缀，例如 `KnowledgeQueryServiceTest` 或 `AgentControllerTest`。新增功能需要补充聚焦测试，尤其是租户与安全上下文传递、异步任务状态变更、RAG 排序、工作流状态流转以及 API 控制器相关逻辑。

## 提交与 Pull Request 规范

近期提交历史采用 Conventional Commit 风格的标题，例如 `feat(knowledge): configure hybrid RAG ranking weights` 和 `docs(deploy): document platform rollout and compose setup`。提交信息应使用简短的类型和作用域，例如 `feat`、`fix`、`docs`、`test`、`refactor` 或 `chore`。

Pull Request 应包含简洁的变更摘要、受影响模块、已执行的测试命令，以及所有配置或部署相关变更。相关 Issue 需要一并关联。对于 API 变更，应提供示例请求或响应说明；对于本地平台栈变更，应说明对 Docker Compose 的影响。

## 安全与配置建议

不要提交密钥、API Key 或本地凭据。优先使用环境变量，例如 `GATEWAY_API_KEY`、`RAG_VECTOR_STORE_PROVIDER` 以及各服务的基础 URL。租户认证和内部 JWT 行为应与 `edge-gateway` 和 `platform-security` 保持一致。

平台现消费外部 auth-platform IAM（Casdoor SSO + SpiceDB ReBAC），鉴权在 `edge-gateway` 叠加、均默认关、引入即安全：

* **Casdoor OIDC SSO**（多租户方案 C，与自建账号登录并存）：`EDGE_CASDOOR_ENABLED`（默认 `false`）、`EDGE_CASDOOR_MODE`（`dual`（默认，验不过透传 legacy）| `only`）、`CASDOOR_AUDIENCES`（默认 `ragshared0client00000001`，按 `<base>-org-*` audience 家族 + `(owner,aud)` 绑定校验）。
* 内部 JWT 新增**附加 `dept` claim**（部门层级隔离；旧 token 无该字段 → 向后兼容，见 `platform-security` `InternalToken`）。
* 知识库文档级细粒度授权：`RAG_AUTHZ_MODE`（`disabled`（默认）| `shadow` | `enforce`）+ `AUTHZ_SERVER_URL` / `AUTHZ_SERVER_TOKEN`（接 auth-platform-server）。`AUTHZ_SERVER_TOKEN` 属服务凭据，走 secrets、勿提交。
