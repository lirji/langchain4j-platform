# 03 Solution A - Manifest Version + Local WebFlux Controller

## 核心思路

在 `edge-gateway` 内新增一个本地只读 WebFlux controller，直接响应 `GET /version`。版本号从应用 jar Manifest 的 `Implementation-Version` 读取，启动时间在 bean 初始化时捕获并保存为不可变 `Instant`。

该方案利用当前构建产物已经存在的 Manifest 元数据，不新增 build-info 生成步骤。

## 架构与模块职责

- `VersionController`
  - 归属包：`com.lrj.platform.edge`。
  - 暴露 `GET /version`。
  - 返回不可变响应模型，建议 Java record：`VersionResponse(String application, String version, Instant startedAt)`。
  - `version` 来源：`EdgeGatewayApplication.class.getPackage().getImplementationVersion()`。
  - `application` 来源：`Environment#getProperty("spring.application.name", "edge-gateway")`。
  - `startedAt`：构造 controller 或独立 bean 时 `Instant.now()` 捕获。
- `ApiKeyToInternalTokenFilter`
  - 若确认公开访问，在 `isOpen` 增加 `path.equals("/version")`。
- `EdgeRateLimitFilter`
  - 若确认公开访问，在 `isOpen` 增加 `path.equals("/version")`。

## 核心流程

1. 客户端请求 `GET /version`。
2. 若 `/version` 被加入开放路径：
   - `ApiKeyToInternalTokenFilter` 直接放行，不要求 `X-Api-Key`。
   - `EdgeRateLimitFilter` 直接放行，不读取 JWT、不消耗限流桶。
3. Spring WebFlux 本地 controller 处理请求。
4. 返回 JSON：

```json
{
  "application": "edge-gateway",
  "version": "0.1.0-SNAPSHOT",
  "startedAt": "2026-07-11T12:19:00Z"
}
```

## 改动范围

- 新增 `edge-gateway/src/main/java/com/lrj/platform/edge/VersionController.java`。
- 可能修改：
  - `edge-gateway/src/main/java/com/lrj/platform/edge/ApiKeyToInternalTokenFilter.java`。
  - `edge-gateway/src/main/java/com/lrj/platform/edge/EdgeRateLimitFilter.java`。
- 测试实现需要修改 `edge-gateway/pom.xml` 增加 `spring-boot-starter-test`，并新增 `edge-gateway/src/test/java/com/lrj/platform/edge/VersionControllerTest.java`。

## 扩展性

- 适合当前“只返回版本与启动时间”的最小需求。
- 后续若要增加 git commit、build time 等字段，需要另找 metadata 来源，Manifest 默认不包含这些字段。
- 若未来多个服务都要统一 `/version`，该 controller 可以再抽象进共享 starter，但本方案不提前抽象。

## 实施成本

低。实现文件少，不改变构建生命周期，不依赖额外插件执行结果。

## 优点

- 改动最小。
- 与当前 jar 实际 Manifest 状态匹配。
- 打包后版本可直接反映 Maven project version。
- 不引入构建行为变化，回滚简单。

## 缺点

- IDE 或测试直接运行时 `Implementation-Version` 可能为 `null`，需要返回 `unknown`。
- 启动时间如果在 controller 构造时捕获，严格来说不是“ApplicationReady”时间，而是 bean 初始化时间。可通过独立 `ApplicationReadyEvent` 监听器弥补，但会略增代码。
- Manifest 元数据可扩展性弱，不如 `BuildProperties` 明确。

## 适用条件

- 业务只需要应用版本号与稳定启动时间。
- 允许本地开发环境显示 `unknown` 版本。
- 不要求 `/actuator/info` 或 build metadata 统一治理。
