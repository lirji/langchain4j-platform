# 01 Requirements

## 任务目标

为 `edge-gateway` 增加一个只读的 `GET /version` 接口，返回当前应用的构建版本号与启动时间。

本阶段只做分析与规划，不修改业务代码。后续实现必须限制在 `edge-gateway` 模块及必要测试配置内，除非开发前再次确认需要抽象到共享模块。

## 已确认需求

- 接口路径：`/version`。
- HTTP 方法：只读查询，建议仅支持 `GET`。
- 返回内容至少包含：
  - 当前应用构建版本号。
  - 当前应用启动时间。
- 服务归属：`edge-gateway` 本地接口，不应代理到下游服务。
- 数据来源应来自运行中应用自身，而不是手写常量。
- 接口不涉及数据库写入、消息发布、跨服务调用。

## 建议业务规则

- `GET /version` 返回 `200 OK` 与 JSON。
- 建议响应字段：
  - `application`: 固定为当前 Spring 应用名，来自 `spring.application.name`，当前配置为 `edge-gateway`。
  - `version`: 构建版本号。
  - `startedAt`: 应用启动时间，ISO-8601 `Instant` 字符串。
- `version` 的来源优先级待实现方案决定：
  - 方案 A：从 `EdgeGatewayApplication.class.getPackage().getImplementationVersion()` 读取 jar Manifest 的 `Implementation-Version`。
  - 方案 B：从 Spring Boot Actuator `BuildProperties` 读取 `build.version`。
- 本地 IDE 或测试以 classes 方式运行时，Manifest 版本可能为 `null`；必须有可预测兜底值，例如 `unknown`。
- 启动时间应在 bean 创建时或应用启动事件时捕获一次，后续请求保持稳定，不应每次请求返回当前时间。
- 响应不应包含 API key、内部 JWT、主机名、环境变量、Git commit、构建机器等敏感或高基数字段，除非后续明确扩展。

## 认证与限流边界

从实际代码看，`edge-gateway` 有两个 reactive `GlobalFilter`：

- `ApiKeyToInternalTokenFilter`：对非开放路径校验 `X-Api-Key` 并注入 `X-Internal-Token`。
- `EdgeRateLimitFilter`：对非开放路径做租户限流。

当前两个过滤器的 `isOpen(String path)` 均只放行：

- `/actuator...`
- `/.well-known...`
- `/channel/feishu/events`
- `/channel/dingtalk/events`
- `/health`

因此 `/version` 默认会被鉴权和限流。任务没有明确 `/version` 是否公开。建议作为只读运维元信息接口，将 `/version` 加入两个过滤器的开放路径，使它与 `/health`、`/actuator` 的访问模型一致。该点属于需要产品/运维确认的歧义；若要求只允许带 API key 访问，则实现时不应修改白名单。

## 非目标

- 不改变现有业务路由规则。
- 不改变下游服务鉴权、租户传播、JWT 签发逻辑。
- 不增加数据库表或持久化启动时间。
- 不改造所有服务的统一版本接口。
- 不替代 `/actuator/info` 或 `/actuator/health` 的既有职责。
- 不新增写接口、缓存写入、审计事件或消息事件。

## 验收标准

- `GET /version` 命中 `edge-gateway` 本地处理器，不转发到下游 route。
- 响应为 JSON，包含非空 `version` 与 `startedAt`。
- `startedAt` 在同一进程生命周期内多次请求保持一致。
- `version` 在打包 jar 运行时可反映 Maven 项目版本，当前仓库版本为 `0.1.0-SNAPSHOT`。
- 若版本元数据缺失，接口仍返回 `200 OK`，`version` 使用明确兜底值 `unknown`。
- 如果采用开放访问规则，则无 `X-Api-Key` 请求 `GET /version` 不返回 `401`，且不消耗限流桶。
- 不修改 `docs/plans/version-endpoint-test-0711-2019/` 以外的文件，直到进入实现阶段。

## 歧义与易遗漏点

- `/version` 是否必须免鉴权：任务只说“只读”，未明确“公开”。当前计划建议免鉴权，但标记为待确认。
- 构建版本号定义：可以是 Maven project version，也可以是更完整的 build metadata。当前仓库已有 jar Manifest `Implementation-Version`，但没有 `build-info.properties`。
- 启动时间定义：是 JVM bean 创建时间、Spring context refreshed 时间，还是 application ready 时间。建议使用 `ApplicationReadyEvent` 捕获“应用就绪时间”；若追求极小改动，可使用 bean 构造时 `Instant.now()`。
- `/health` 当前被过滤器放行，但 `application.yml` 未定义 `/health` 本地路由；`/version` 不能照搬只加白名单，必须有本地 WebFlux handler 或 controller。
