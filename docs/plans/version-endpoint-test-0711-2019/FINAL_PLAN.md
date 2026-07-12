# FINAL PLAN

## 背景、目标与非目标

`edge-gateway` 是平台入口服务，当前基于 Spring Cloud Gateway 做业务 API 路由、API key 到内部 JWT 的转换，以及边缘限流。任务目标是在该模块增加一个只读 `GET /version` 本地接口，返回当前应用构建版本号与启动时间。本文件是实施规划；本轮不修改任何业务代码，后续实现阶段才按清单修改 `edge-gateway`。

非目标：

- 不改造下游服务。
- 不增加数据库、消息、异步任务或审计链路。
- 不新增写接口。
- 不统一所有服务的版本接口。
- 不修改 Gateway 现有业务路由语义。

## 已确认的业务规则

- 路径为 `/version`。
- 方法为 `GET`。
- 响应 JSON 至少包含：
  - `version`：构建版本号。
  - `startedAt`：应用启动时间。
- 建议额外包含 `application`，值来自 `spring.application.name`，当前为 `edge-gateway`。
- `startedAt` 在同一进程生命周期内保持稳定。
- 版本元数据缺失时返回 `unknown`，不让接口失败。
- 待确认但有默认建议：`/version` 免 API key、免限流，与当前开放的 `/actuator`、`/health` 运维类路径保持一致。若业务或安全要求必须鉴权，实施时不要修改两个 filter 的白名单，并相应调整测试预期。

## 当前代码与调用链分析

实际读取到的 `edge-gateway` 生产代码只有三个 Java 文件：

- `edge-gateway/src/main/java/com/lrj/platform/edge/EdgeGatewayApplication.java`
  - Spring Boot 启动类。
- `edge-gateway/src/main/java/com/lrj/platform/edge/ApiKeyToInternalTokenFilter.java`
  - `GlobalFilter`，order `-100`。
  - 非开放路径读取 `X-Api-Key`，根据 `platform.security.api-keys` 映射租户，调用 `InternalToken#mint` 签发内部 JWT。
  - 当前开放路径包含 `/actuator`、`/.well-known`、两个 channel callback、`/health`。
- `edge-gateway/src/main/java/com/lrj/platform/edge/EdgeRateLimitFilter.java`
  - `GlobalFilter`，order `-90`。
  - 非开放路径读取内部 JWT，解析 tenant 后限流。
  - 开放路径列表与 `ApiKeyToInternalTokenFilter` 重复。

`edge-gateway/src/main/resources/application.yml` 配置了 Gateway routes，但没有 `/version` route；因此 `/version` 应由本地 WebFlux controller 处理。

`platform-security` 的 servlet 入站鉴权 filter 只在 servlet Web 应用装配。`edge-gateway` 是 reactive Gateway，应主要受上述两个 `GlobalFilter` 影响。

构建版本来源方面，当前已构建 jar 的 Manifest 实际包含 `Implementation-Version: 0.1.0-SNAPSHOT`，但 jar 内没有 `build-info.properties`。因此最小方案可以读取 Manifest。

## 候选方案对比与评分

| 维度 | 方案 A：Manifest 本地 controller | 方案 B：BuildProperties + ready event |
| --- | ---: | ---: |
| 正确性 | 4 | 5 |
| 改动风险 | 5 | 4 |
| 复杂度 | 5 | 3 |
| 可维护性 | 4 | 4 |
| 扩展性 | 3 | 5 |
| 测试难度 | 4 | 3 |
| 回滚成本 | 5 | 4 |
| 总分 | 30 | 28 |

方案 A 更符合本次轻量需求和现有仓库状态。方案 B 更规范，但需要修改 Maven build-info，当前属于过度扩展。

## 最终方案及选择原因

采用合并后的轻量方案：

- 版本号读取 Manifest `Implementation-Version`。
- 启动时间使用独立启动时间 bean 捕获：
  - bean 创建时记录 fallback 时间。
  - 监听 `ApplicationReadyEvent` 后记录 ready 时间。
- 新增本地 WebFlux `@RestController` 暴露 `GET /version`。
- 若按推荐公开访问，则同时在两个 `GlobalFilter#isOpen` 中加入 `/version`；若要求鉴权，则不改白名单。
- 新增 `edge-gateway` 测试依赖与测试用例。

选择原因：

- 当前 jar 已确认有 Manifest 版本，不需要改变构建流程。
- ready event 让 `startedAt` 更贴近“启动完成时间”。
- 改动集中在 `edge-gateway`，不会影响共享库与下游服务。
- 可直接交给开发 Agent 实施。

已知弱点：

- IDE/classes 直接运行时 Manifest 版本可能缺失，接口会返回 `unknown`。
- 白名单仍在两个 filter 中重复维护，本次只做最小同步修改，不抽象共享常量。
- 若未来需要 build time、commit id、artifact 等字段，需要演进到 `BuildProperties` 或 git metadata。

## 精确修改清单

### 新增文件

- `edge-gateway/src/main/java/com/lrj/platform/edge/ApplicationStartupTime.java`
  - 类：`ApplicationStartupTime`
  - 方法：
    - 构造器：记录 fallback `Instant`。
    - `onApplicationReady(ApplicationReadyEvent event)`：记录 ready `Instant`。
    - `startedAt()`：返回 ready 时间，若未 ready 则返回 fallback。
- `edge-gateway/src/main/java/com/lrj/platform/edge/VersionController.java`
  - 类：`VersionController`
  - 方法：
    - `version()`：处理 `GET /version`。
    - `resolveVersion()`：读取 `EdgeGatewayApplication.class.getPackage().getImplementationVersion()`，为空则返回 `unknown`。
  - 内部或同文件 package-private record：
    - `VersionResponse(String application, String version, Instant startedAt)`。
- `edge-gateway/src/test/java/com/lrj/platform/edge/VersionControllerTest.java`
  - 覆盖版本兜底、应用名、启动时间稳定性。
- `edge-gateway/src/test/java/com/lrj/platform/edge/VersionEndpointIntegrationTest.java`
  - 使用 `WebTestClient` 覆盖真实 HTTP 行为。

### 修改文件

- `edge-gateway/src/main/java/com/lrj/platform/edge/ApiKeyToInternalTokenFilter.java`
  - 方法：`private boolean isOpen(String path)`
  - 推荐公开访问时修改：加入 `|| path.equals("/version")`。
- `edge-gateway/src/main/java/com/lrj/platform/edge/EdgeRateLimitFilter.java`
  - 方法：`private boolean isOpen(String path)`
  - 推荐公开访问时修改：加入 `|| path.equals("/version")`。
- `edge-gateway/pom.xml`
  - 在 `<dependencies>` 中新增测试依赖：
    - `org.springframework.boot:spring-boot-starter-test`，`scope=test`。
  - 不启用 `build-info`。

## 数据库、接口、配置、消息结构变更

- 数据库：无变更。
- 消息结构：无变更。
- 配置：不新增运行时配置。
- 接口：新增 `GET /version`。
- 鉴权/限流：默认建议公开并免限流；如实施前确认必须鉴权，则不改变现有 filter 白名单，接口仍只读但需要 `X-Api-Key`。

建议响应：

```json
{
  "application": "edge-gateway",
  "version": "0.1.0-SNAPSHOT",
  "startedAt": "2026-07-11T12:19:00Z"
}
```

## 分阶段实施步骤及依赖关系

### 阶段 1：数据结构与领域模型

步骤：

- 新增 `VersionResponse` record。
- 新增 `ApplicationStartupTime`。

完成标准：

- `VersionResponse` 字段为 `application`、`version`、`startedAt`。
- `ApplicationStartupTime#startedAt()` 永不返回 `null`。

### 阶段 2：核心业务逻辑

步骤：

- 在 `VersionController` 中实现版本解析。
- 版本解析顺序：Manifest `Implementation-Version` -> `unknown`。
- 读取应用名：`Environment#getProperty("spring.application.name", "edge-gateway")`。

完成标准：

- 版本为空、空白时均返回 `unknown`。
- 启动时间来自 `ApplicationStartupTime`。

### 阶段 3：接口与适配层

步骤：

- `VersionController` 增加 `@RestController` 与 `@GetMapping("/version")`。
- 若确认采用默认公开访问，在两个 filter 的 `isOpen` 方法加入 `/version`；若确认需要鉴权，则跳过该步骤。

完成标准：

- `GET /version` 本地返回 `200 OK` JSON。
- 无 API key 请求按最终确认的公开或鉴权规则表现正确。
- `/chat` 等原有业务路径鉴权行为不变。

### 阶段 4：测试

步骤：

- 在 `edge-gateway/pom.xml` 加入 `spring-boot-starter-test`。
- 新增 controller 单元测试。
- 新增 WebFlux 集成测试。
- 执行 `mvn -pl edge-gateway test`；如依赖解析需要，再执行 `mvn -pl edge-gateway -am test`。

完成标准：

- 测试全部通过。
- 集成测试证明 `/version` 不转发下游、不要求 Redis 外部服务。

### 阶段 5：文档与最终检查

步骤：

- 如项目 API 文档需要，后续可更新 `docs/参考/api-reference.md`。本次实现 Agent 可视需求决定是否更新，若用户仍限制只改代码模块则跳过。
- 检查 `git diff`，确认无无关文件变更。

完成标准：

- diff 只包含 `edge-gateway` 相关代码与测试，或额外明确文档。
- 无密钥、环境值、构建产物提交。

## 测试方案

- 单元测试：
  - `VersionControllerTest` 验证响应字段、版本兜底、启动时间稳定。
  - `ApplicationStartupTime` 验证 ready event 前后都能返回非空时间。
- 集成测试：
  - `GET /version` 返回 `200`。
- 不带 `X-Api-Key` 请求 `/version` 返回 `200`，前提是采纳默认公开规则；若确认需要鉴权，则改为断言 `401`，并增加带 `dev-key-acme` 的成功用例。
  - 不带 `X-Api-Key` 请求 `/chat` 仍返回 `401`，验证没有放宽业务接口。
  - `startedAt` 可解析为 `Instant`。
- 回归命令：

```bash
mvn -pl edge-gateway test
```

必要时：

```bash
mvn -pl edge-gateway -am test
```

## 风险、监控、灰度与回滚方案

风险：

- `/version` 公开后泄露版本号，可能被外部用于指纹识别。缓解：只返回版本和启动时间，不返回 commit、host、环境、依赖版本；如安全策略更严格，则保留 API key 鉴权。
- 两个 filter 白名单遗漏一个会导致行为不一致。缓解：集成测试覆盖无 API key 访问 `/version`。
- 本地运行版本为 `unknown`。缓解：验收说明明确，jar 包运行读取 Manifest。
- Redis 依赖影响测试。缓解：测试设置 `app.rate-limit.store=in-memory`，且 `/version` 免限流。

监控：

- 可通过现有 `/actuator/health` 观察应用健康。
- `/version` 本身无需新增指标；如未来需要，可复用 Gateway/HTTP 访问日志或 Micrometer http server metrics。

灰度：

- 该接口无状态、只读、无数据库变更，可按普通应用版本发布。
- 在单实例或一个 pod 上先验证 `GET /version` 响应，再扩大部署。

回滚：

- 回滚应用镜像或代码提交即可。
- 无数据库迁移、无配置迁移、无消息兼容问题。

## 最终验收清单

- [ ] `GET /version` 返回 `200 OK`。
- [ ] 响应 JSON 包含 `application`、`version`、`startedAt`。
- [ ] jar 包运行时 `version` 为 Maven 版本，当前为 `0.1.0-SNAPSHOT`。
- [ ] 版本缺失时返回 `unknown`。
- [ ] 同一进程多次请求 `startedAt` 保持一致。
- [ ] 如果采纳公开规则，无 API key 请求 `/version` 成功。
- [ ] 无 API key 请求 `/chat` 等业务接口仍失败。
- [ ] `mvn -pl edge-gateway test` 通过。
- [ ] diff 不包含业务无关改动。

## 架构复审记录

复审结论：本计划与当前仓库结构一致，没有引用不存在的 controller、route 或数据库表。最终方案避免了不必要的 build-info 变更，但吸收了 ready event 的时间语义。唯一需要实施前确认的是 `/version` 是否免鉴权；计划已给出公开与鉴权两种处理边界，默认推荐公开。
