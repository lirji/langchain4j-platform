# 04 Solution B - Spring Boot BuildProperties + Ready Event

## 核心思路

为 `edge-gateway` 的 Spring Boot Maven 插件启用 `build-info`，由构建生成 `META-INF/build-info.properties`。应用启动后通过 Spring Boot `BuildProperties` 读取构建版本，并通过 `ApplicationReadyEvent` 记录真正 ready 时间。`GET /version` 返回这些显式元数据。

## 架构与模块职责

- `edge-gateway/pom.xml`
  - 在 `spring-boot-maven-plugin` 下新增 `build-info` goal。
  - 新增 `spring-boot-starter-test` 作为测试依赖。
- `ApplicationStartupTime`
  - 监听 `ApplicationReadyEvent`。
  - 保存 `Instant startedAt`。
  - 若请求早于 ready 事件到达，理论上 Web server 已启动但事件未处理完成，需提供兜底，例如 bean 构造时间。该场景很窄，但要在实现中避免 `null`。
- `VersionController`
  - 注入 `ObjectProvider<BuildProperties>` 或 `Optional<BuildProperties>`。
  - `version` 优先取 `buildProperties.getVersion()`。
  - 若 build-info 不存在，兜底到 Manifest `Implementation-Version`，最后 `unknown`。
  - 返回 `application`、`version`、`startedAt`。
- 两个 Gateway `GlobalFilter`
  - 若确认公开访问，同步将 `/version` 加入开放路径。

## 核心流程

1. Maven package 阶段执行 Spring Boot `build-info` goal，生成 build metadata。
2. 应用启动，Spring Boot 自动创建 `BuildProperties` bean。
3. `ApplicationStartupTime` 在 `ApplicationReadyEvent` 记录 ready 时间。
4. 客户端请求 `GET /version`。
5. 本地 controller 返回：

```json
{
  "application": "edge-gateway",
  "version": "0.1.0-SNAPSHOT",
  "startedAt": "2026-07-11T12:19:00Z"
}
```

## 改动范围

- 修改 `edge-gateway/pom.xml`：
  - 增加 `spring-boot-maven-plugin` 的 `build-info` 执行。
  - 增加 `spring-boot-starter-test`。
- 新增 `edge-gateway/src/main/java/com/lrj/platform/edge/ApplicationStartupTime.java`。
- 新增 `edge-gateway/src/main/java/com/lrj/platform/edge/VersionController.java`。
- 可能修改：
  - `edge-gateway/src/main/java/com/lrj/platform/edge/ApiKeyToInternalTokenFilter.java`。
  - `edge-gateway/src/main/java/com/lrj/platform/edge/EdgeRateLimitFilter.java`。
- 新增测试：
  - `edge-gateway/src/test/java/com/lrj/platform/edge/VersionControllerTest.java`。
  - 视实现需要增加 filter 白名单测试。

## 扩展性

- 后续可自然加入 build group、artifact、name、time 等 build-info 字段。
- 可与 `/actuator/info` 的 build 信息保持一致。
- 更适合作为未来跨服务统一版本信息规范的基础。

## 实施成本

中等。代码量仍小，但会改变 `edge-gateway` 构建产物内容与 Maven 插件执行行为。测试时需要考虑 build-info 在 test classpath 中是否存在；因此 controller 应保留 Manifest/unknown 兜底。

## 优点

- 版本来源语义最明确，符合 Spring Boot 生态。
- ready 时间比 bean 构造时间更贴近“启动时间”。
- 后续扩展 build metadata 成本低。

## 缺点

- 改动构建配置，比当前需求更重。
- 测试或 IDE 运行时 `BuildProperties` 可能不存在，必须写好兜底逻辑。
- 若多个模块未来都要 build-info，需要进一步抽象或重复配置；本方案只处理 `edge-gateway`。

## 适用条件

- 运维侧希望 `/version` 与标准 build metadata 对齐。
- 未来可能扩展 build time、artifact、group、git commit 等字段。
- 可以接受一次小的构建配置变更。
