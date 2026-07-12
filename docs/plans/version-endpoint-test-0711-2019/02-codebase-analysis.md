# 02 Codebase Analysis

## 模块结构

`edge-gateway` 是 Maven 多模块工程中的服务模块，父工程为根目录 `pom.xml`：

- 根 `pom.xml`
  - Java 21。
  - Spring Boot `3.3.5`。
  - Spring Cloud `2023.0.3`。
  - 聚合模块包含 `edge-gateway`。
- `edge-gateway/pom.xml`
  - 依赖 `spring-cloud-starter-gateway`。
  - 依赖 `spring-boot-starter-actuator`。
  - 依赖 `platform-security`。
  - 当前未声明 `spring-boot-starter-test`。

## edge-gateway 当前文件

- `edge-gateway/src/main/java/com/lrj/platform/edge/EdgeGatewayApplication.java`
  - Spring Boot 启动类。
  - 包名 `com.lrj.platform.edge`。
  - 当前无额外 bean、controller 或 route locator。
- `edge-gateway/src/main/java/com/lrj/platform/edge/ApiKeyToInternalTokenFilter.java`
  - `@Component`。
  - 实现 `GlobalFilter, Ordered`。
  - `getOrder()` 返回 `-100`，早于路由转发。
  - 非开放路径读取 `platform.security.api-key-header`，默认 `X-Api-Key`。
  - 校验 `InternalSecurityProperties#getApiKeys()` 中的 key 绑定。
  - 使用 `InternalToken#mint(...)` 签发内部 JWT。
  - 注入 `platform.security.internal-header`，默认 `X-Internal-Token`。
  - 删除外部 API key header，避免转发到内网。
- `edge-gateway/src/main/java/com/lrj/platform/edge/EdgeRateLimitFilter.java`
  - `@Component`。
  - 实现 `GlobalFilter, Ordered`。
  - `getOrder()` 返回 `-90`，在 API key filter 之后。
  - 非开放路径从内部 JWT 还原 tenant。
  - 使用 `RateLimiterRegistry#tryConsume(tenant, family)` 限流。
  - 拒绝时返回 `429` JSON。
- `edge-gateway/src/main/resources/application.yml`
  - `server.port: 8080`。
  - `spring.application.name: edge-gateway`。
  - 配置 Spring Cloud Gateway routes，转发 `/chat`、`/rag`、`/agent`、`/channel` 等下游路径。
  - `management.endpoints.web.exposure.include: health,info,gateway`。
  - 未看到 `/version` route 或 handler 配置。

## 开放路径现状

`ApiKeyToInternalTokenFilter#isOpen(String path)` 和 `EdgeRateLimitFilter#isOpen(String path)` 当前逻辑重复，均开放：

- `path.startsWith("/actuator")`
- `path.startsWith("/.well-known")`
- `path.equals("/channel/feishu/events")`
- `path.equals("/channel/dingtalk/events")`
- `path.equals("/health")`

受影响点：若 `/version` 需要免鉴权，两个过滤器都必须同步加入 `path.equals("/version")`。若只改一个，会出现无 API key 被 `401` 或被限流逻辑再次校验内部 JWT 的不一致行为。

## 安全与自动配置

`platform-security/src/main/java/com/lrj/platform/security/PlatformSecurityAutoConfiguration.java`：

- 始终装配 `InternalToken`。
- servlet 入站 JWT filter 仅在 `ConditionalOnWebApplication(type = SERVLET)` 时装配。
- `edge-gateway` 使用 Spring Cloud Gateway，是 reactive 应用，因此不会装配 `InternalTokenAuthFilter`。

这意味着 `/version` 在 `edge-gateway` 内的鉴权行为主要由上述两个 `GlobalFilter` 控制。

## 构建版本可用来源

实际检查当前已构建 jar：

- `edge-gateway/target/edge-gateway-0.1.0-SNAPSHOT.jar` 的 Manifest 包含：
  - `Implementation-Title: edge-gateway`
  - `Implementation-Version: 0.1.0-SNAPSHOT`
  - `Start-Class: com.lrj.platform.edge.EdgeGatewayApplication`
  - `Spring-Boot-Version: 3.3.5`
- jar 内没有发现 `build-info.properties`。
- `edge-gateway/target/maven-archiver/pom.properties` 包含：
  - `artifactId=edge-gateway`
  - `groupId=com.lrj.platform`
  - `version=0.1.0-SNAPSHOT`

因此目前无需额外 Maven 配置即可通过 package Manifest 读取版本，但从 IDE/classes 直接运行时该值可能缺失。若要使用 `BuildProperties`，需要在 Maven 插件中新增 `build-info` goal。

## 测试现状

- `edge-gateway/src/test` 当前不存在。
- `edge-gateway/pom.xml` 当前未引入 `spring-boot-starter-test`。
- 其他服务存在大量 JUnit 5 测试，常见模式：
  - 简单 controller 单元测试直接实例化 controller。
  - Spring Boot web 测试在各模块内自行配置。
- `spring-cloud-starter-gateway` 带来 WebFlux/Gateway 运行时，测试 `/version` 推荐使用 `WebTestClient` 或 `@SpringBootTest(webEnvironment = RANDOM_PORT)`。

## 可复用代码

- `InternalSecurityProperties` 可读取 header 名称，但 `/version` 不需要直接依赖。
- 现有 filter 的 `isOpen` 白名单可扩展，但当前重复实现，没有共享常量。
- Spring Boot/Java 标准能力可复用：
  - `Package#getImplementationVersion()`。
  - `ApplicationReadyEvent`。
  - `org.springframework.boot.info.BuildProperties`，前提是生成 build-info。
  - `Environment#getProperty("spring.application.name")`。

## 预计受影响文件

方案 A 最小 Manifest 方案：

- 新增 `edge-gateway/src/main/java/com/lrj/platform/edge/VersionController.java` 或 `VersionHandler`。
- 修改 `edge-gateway/src/main/java/com/lrj/platform/edge/ApiKeyToInternalTokenFilter.java`，如决定免鉴权则加入 `/version`。
- 修改 `edge-gateway/src/main/java/com/lrj/platform/edge/EdgeRateLimitFilter.java`，如决定免限流则加入 `/version`。
- 修改 `edge-gateway/pom.xml`，若新增测试则加入 `spring-boot-starter-test`。
- 新增 `edge-gateway/src/test/java/com/lrj/platform/edge/VersionControllerTest.java` 或 WebFlux 集成测试。

方案 B BuildProperties 方案：

- 修改 `edge-gateway/pom.xml`，为 `spring-boot-maven-plugin` 增加 `build-info` 执行，且加入测试依赖。
- 新增 `edge-gateway/src/main/java/com/lrj/platform/edge/VersionController.java`。
- 新增 `edge-gateway/src/main/java/com/lrj/platform/edge/ApplicationStartupTime.java` 或类似启动事件 bean。
- 修改两个 filter 的开放路径，条件同方案 A。
- 新增对应测试。

待验证：Spring Cloud Gateway 与 MVC controller 不应混用。由于 `edge-gateway` 是 Gateway/WebFlux，应实现 WebFlux-compatible `@RestController` 或 functional route，而不是引入 `spring-boot-starter-web`。
