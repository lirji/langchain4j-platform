# Test Plan

## 测试目标

验证 `edge-gateway` 的 `GET /version` 是本地只读接口，返回稳定版本号与启动时间，并且不会破坏现有 Gateway 鉴权、限流和路由行为。

## 测试前提

当前 `edge-gateway` 没有测试目录，`edge-gateway/pom.xml` 未声明 `spring-boot-starter-test`。实现阶段需要新增测试依赖，否则无法按项目现有 JUnit 5 风格编写测试。

## 单元测试

### VersionController 直接实例化测试

建议文件：

- `edge-gateway/src/test/java/com/lrj/platform/edge/VersionControllerTest.java`

覆盖点：

- `version` 有来源时返回该值。
- `version` 来源为空时返回 `unknown`。
- `application` 使用 `spring.application.name`，缺失时兜底 `edge-gateway`。
- 同一 controller 实例多次调用，`startedAt` 相同。
- `startedAt` 不晚于请求断言时间，不为空。

验收标准：

- 所有断言稳定，不依赖真实系统时区。
- `startedAt` 使用 `Instant` 序列化，避免本地时区差异。

### ApplicationStartupTime 测试

若采用 ready event bean：

- bean 初始化时有 fallback start time。
- 触发 `ApplicationReadyEvent` 后 `startedAt` 更新为 ready 时间。
- 多次读取不返回 `null`。

验收标准：

- ready event 前后行为明确。
- 不需要真实启动完整 Spring 应用即可测试核心逻辑。

## WebFlux / Gateway 集成测试

建议新增：

- `edge-gateway/src/test/java/com/lrj/platform/edge/VersionEndpointIntegrationTest.java`

建议使用：

- `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`
- `WebTestClient`
- 测试属性将 `app.rate-limit.store=in-memory`，避免 Redis 依赖。

覆盖点：

- `GET /version` 返回 `200 OK`。
- 响应 `Content-Type` 为 JSON。
- JSON 包含：
  - `application == "edge-gateway"`。
  - `version` 非空。
  - `startedAt` 非空且可解析为 `Instant`。
- 如果最终规则为公开访问：
  - 不带 `X-Api-Key` 请求仍返回 `200 OK`。
  - 不返回 `401`。
- 如果最终规则为需要鉴权：
  - 不带 `X-Api-Key` 返回 `401`。
  - 带配置中的 `dev-key-acme` 返回 `200 OK`。

## Filter 回归测试

建议覆盖两个 filter 的开放路径逻辑，避免只改一个 filter：

- `ApiKeyToInternalTokenFilter` 对 `/version` 放行。
- `EdgeRateLimitFilter` 对 `/version` 放行。
- 非开放路径如 `/chat` 在缺失 API key 时仍返回 `401`。

如果 `isOpen` 保持 private，推荐通过集成测试覆盖实际行为，不强行改访问级别。

## 回归测试

执行命令建议：

```bash
mvn -pl edge-gateway test
```

如修改 `edge-gateway/pom.xml` 并依赖上游模块解析：

```bash
mvn -pl edge-gateway -am test
```

## 异常与边界场景

- 版本元数据缺失：返回 `version: "unknown"`。
- 请求方法错误：`POST /version` 不应被当作只读接口处理，预期 `405 Method Not Allowed` 或 WebFlux 默认错误响应。
- 启动时间为空：不允许出现；应有 fallback。
- Redis 不可用：若 `/version` 免限流，不应触发 Redis 限流依赖；集成测试可用 `app.rate-limit.store=in-memory` 降低环境耦合。
- Gateway route 冲突：`application.yml` 当前没有 `/version` route，新增本地 controller 后不应转发。

## 最终验收标准

- `mvn -pl edge-gateway test` 通过。
- `GET /version` 返回稳定 JSON。
- 当前构建 jar 运行时 `version` 为 `0.1.0-SNAPSHOT`。
- `startedAt` 在同一进程内多次请求不变。
- 若公开访问被采纳，无 API key 请求成功，且 `/chat` 等业务路径仍保持原鉴权行为。
