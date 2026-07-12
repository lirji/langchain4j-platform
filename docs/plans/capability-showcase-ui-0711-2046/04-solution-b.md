# 04 Solution B - Dedicated Showcase BFF + SPA

## architecture-designer 视角

### 方案定位

新增独立 `capability-showcase-ui` 服务，后端作为 BFF 提供能力目录、健康探测、轻代理和静态 SPA 托管；浏览器只访问展示服务或经 `edge-gateway` 访问展示服务。业务服务不改或少改，目录初期由配置 manifest 驱动。

### 架构

```
Browser
  └─ /showcase/** (same origin)
       └─ capability-showcase-ui
            ├─ static SPA
            ├─ GET /showcase/api/catalog
            ├─ GET /showcase/api/health/services
            ├─ POST /showcase/api/proxy/**
            └─ edge-gateway / internal service calls
```

### 模块职责

- `CapabilityCatalogController`：返回能力域、模块、端点、请求 schema、示例、开关、scope。
- `CapabilityCatalogService`：读取 `capabilities.yml`，合并已知 live discovery：`/agent/capabilities`、`/channel/capabilities`、`/eval/capabilities`、`/interop/mcp/tools`。
- `CapabilityProxyController`：可选代理业务调用，集中处理 baseUrl、API Key 透传、multipart、SSE。
- SPA：按 catalog 渲染模块化工具台。
- Edge route：新增 `/showcase/**` 指向 showcase 服务。

### 核心流程

1. 用户经 `http://localhost:8080/showcase` 打开展示页。
2. SPA 调 `GET /showcase/api/catalog` 获取能力目录。
3. BFF 从本地配置返回全量 manifest，并对部分服务做 live 补充。
4. 用户输入 API Key，BFF 代理或 SPA 经同源调用触发业务端点。
5. BFF 将业务响应、状态码、响应头、SSE 事件统一包装给前端展示。

### 改动范围

- 根 `pom.xml`：新增 `<module>capability-showcase-ui</module>`。
- 新增 `capability-showcase-ui/pom.xml`。
- 新增 Spring Boot app 与静态前端资源。
- `edge-gateway/src/main/resources/application.yml`：新增 `/showcase,/showcase/**` 路由。
- `deploy/docker-compose.yml`：新增服务与 `SHOWCASE_EDGE_BASE_URL`。
- 可选：`README.md` / `docs` 增加运行说明。

### 扩展性

- 可逐步从静态 manifest 过渡到服务能力发现，不强迫所有业务服务一次性改造。
- BFF 可封装 SSE/multipart/错误格式差异，前端模块复用度高。
- 可以后续增加 UI 预设持久化，但 MVP 不需要数据库。

## risk-reviewer 视角

- 兼容性：新增服务与网关路由，对业务代码影响小；路由前缀需避免与现有 `/showcase` 冲突（当前未发现冲突）。
- 事务：BFF 不落库则无新事务；代理调用不得吞掉后端 409/422 等业务状态。
- 并发：BFF 代理 SSE 会占用服务线程/连接；需设置超时和最大并发。
- 幂等：BFF 不应自动重试非幂等 POST；只对 catalog/health 做短缓存。
- 性能：manifest 可本地缓存；大文件上传经 BFF 会多一跳，需限制文件大小并允许 direct mode。
- 安全：API Key 不落库；BFF 日志必须脱敏 `X-Api-Key`、`Authorization`、音频/图片 base64。
- 数据迁移：MVP 无；若后续保存用户 preset，再单独设计表。
- 灰度：先在网关加隐藏路由 `/showcase`，不影响业务流量；也可独立端口试运行。
- 回滚：移除网关 route 和 compose 服务即可；业务服务无需回滚。

### 失败场景

- BFF catalog 与真实能力漂移：通过 live health + “待验证/未启用”状态缓解。
- BFF 代理 SSE 实现错误导致流式体验不稳定：第一阶段可让 SPA 对 SSE 端点 direct call，同源经网关。
- 上传代理 OOM：必须走 streaming multipart，不把文件读成大 byte[] 再转发；实现细节待开发验证。

### 实施成本

中。适合成为最终推荐的首期方案：MVP 可控，长期可演进。
