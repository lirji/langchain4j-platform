# 05 Solution C - Edge Gateway Hosted Console

## architecture-designer 视角

### 方案定位

直接在 `edge-gateway` 中托管展示页静态资源，并在网关增加轻量能力目录端点。所有调用天然同源，浏览器不会遇到 CORS；展示页与边缘鉴权/限流在同一个服务边界内。

### 架构

```
Browser
  └─ edge-gateway (:8080)
       ├─ /showcase/index.html static
       ├─ /showcase/api/catalog
       ├─ existing GlobalFilter auth/rate-limit
       └─ existing Gateway routes
```

### 模块职责

- `edge-gateway` 静态资源：展示页入口。
- `CapabilityCatalogHandler/Controller`：从 `application.yml` route 和配置 manifest 返回目录。
- 现有 `ApiKeyToInternalTokenFilter`：继续负责业务 API Key 换发。
- 前端模块：同 Solution A/B。

### 核心流程

1. 用户打开 `http://localhost:8080/showcase`。
2. 页面读取网关本地目录端点。
3. 业务调用直接打当前 origin 的 `/chat`、`/rag`、`/agent` 等路径。
4. 网关现有过滤器处理鉴权、租户、限流和路由。

### 改动范围

- `edge-gateway/pom.xml`：可能增加静态资源构建/打包插件或前端资源依赖。
- `edge-gateway/src/main/resources/application.yml`：新增 showcase catalog 配置。
- `edge-gateway/src/main/java/...`：新增 catalog controller/handler。
- 静态前端资源放在 `edge-gateway/src/main/resources/static/showcase`（当前不存在）。

### 扩展性

- 同源体验最好，适合内部演示。
- 但 `edge-gateway` 本应专注鉴权/路由/限流，把 UI 和 catalog 放入网关会增加边缘服务职责。
- 前端迭代会迫使网关重新构建/发布，发布耦合较高。

## risk-reviewer 视角

- 兼容性：改动核心入口服务，风险高于独立服务；静态资源路径和 gateway route 匹配需仔细处理。
- 事务：无新增事务。
- 并发：网关已承载所有业务流量，展示页静态请求与长连接可能影响业务网关资源池。
- 幂等：同其他方案。
- 性能：静态资源由网关提供，简单；但大规模使用展示页会增加网关负载。
- 安全：API Key 仍在浏览器；优势是同源无 CORS。需要确保 `/showcase` 本身是否免鉴权有清晰规则。
- 数据迁移：无。
- 灰度：网关发布风险较大，可用 feature flag 控制 catalog/静态资源是否暴露。
- 回滚：需回滚网关版本，影响面大。

### 失败场景

- 静态资源路径被 `ApiKeyToInternalTokenFilter` 拦截，导致打开页面也要 API Key；需要扩展 `isOpen(...)`，但这改变了网关鉴权白名单。
- 前端资源错误导致网关发布频率提高。
- 展示页长连接压垮网关，影响真实业务 API。

### 实施成本

中低，但架构职责不清。适合临时内嵌演示，不推荐作为长期方案。
