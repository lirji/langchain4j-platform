# 方案 C：独立 RBAC 管理 SPA

## 1. 定位

保留 `capability-showcase-frontend` 作为能力试用台，只做身份提示、权限 gate 和共享 RAG 改造；另建独立 `rbac-admin-frontend` 静态 SPA 专门管理用户/角色。两个站点都通过 edge 调 auth-service，共享后端会话协议但不共享浏览器内存 token。

## 2. 架构与职责

```text
showcase SPA                 admin SPA
  ├─ 能力/RAG                  ├─ 用户/角色/审计入口
  └─ 登录/跳转 admin           └─ 独立登录/bootstrap
          \                      /
           \ Authorization Bearer
                edge-gateway
                   -> auth-service / knowledge-service
```

- showcase 不承载管理 CRUD，降低普通用户攻击面与 bundle。
- admin SPA 有独立路由、状态、设计和发布节奏。
- 两个应用各自保留 access token 内存和 refresh 流程。
- 可计划新增 `platform-console-ui` workspace/shared package 共享 auth client、tokens、错误模型；当前仓库没有前端 monorepo，需新建 npm workspace 或复制少量代码。

## 3. 核心流程

- 用户在 showcase 点击“管理中心”打开 admin 域名。
- 因 access token 不跨页面内存，admin SPA 用其同站/同域 httpOnly refresh cookie bootstrap；若 cookie domain/path 不覆盖 admin 站点则重新登录。
- admin SPA 只接受 Bearer，不提供 API Key UI。
- RBAC CRUD 和并发控制同方案 B 的加固契约。
- showcase 的共享 RAG 仍直接调用 knowledge-service。

## 4. 部署变体

推荐同一父域、两个路径：

- `https://console.example.com/showcase/`
- `https://console.example.com/admin/`

这样 refresh cookie 仍可配置 Path `/auth`，两个 SPA 都通过同源 `/auth/` 反代；无需扩大 cookie domain。若用两个不同子域，则需要重新评估 SameSite=None/Secure、CORS allowCredentials、cookie domain 和 CSRF。

## 5. 改动范围

- 新增完整 `rbac-admin-frontend/` 工程、Dockerfile/nginx、compose/Helm service。
- showcase 仍需改 AuthControl、permission gate、RAG visibility 和跨站链接。
- auth/knowledge 后端契约加固与方案 B 相同。
- 若引 workspace，还需调整根 npm 组织、CI cache、锁文件和发布流程；当前根工程是 Maven 聚合，前端并未纳入根构建。

## 6. 扩展性与实施成本

- 管理域可独立扩展、独立灰度、独立安全扫描和部署。
- 普通 showcase bundle 最小；管理团队可有独立开发节奏。
- 初始成本高于 B：重复壳、认证 bootstrap、主题、错误模型与测试基建；预计 6–8 个开发阶段。

## 7. 风险评审

### 兼容性

- 后端仍使用现有 Bearer/edge 链，业务兼容性好。
- 浏览器会话跨站行为是最大兼容风险；不同域部署可能导致 refresh cookie 不可用。

### 事务、并发、幂等

- 与 B 相同，依赖后端 version/事务；独立 SPA 不自动提升正确性。
- 两个 SPA 同时刷新同一个轮转 cookie 可能发生竞争：各自 authStore 内 single-flight 只在单页面有效，跨 tab/跨应用不协调。
- 并发 refresh 可能一个成功、另一个拿旧 cookie 失败并被迫登录；需要 BroadcastChannel 协调或服务端 refresh token family 宽限策略。

### 性能

- 每个应用首包更小；但跨应用跳转需要完整新文档和 bootstrap。
- 共用静态依赖若没有 CDN 缓存复用，会重复下载 Vue/runtime。

### 安全

- 管理站点隔离可收紧 CSP、来源白名单和网络访问。
- 若扩大 refresh cookie Domain 到父域，任一受控子域的风险面增大；推荐同源路径部署。
- 管理 SPA 完全移除 API Key 是明确优势。

### 数据迁移

- auth DB version 迁移同 B；前端自身无 DB。
- 新静态服务和路由需要部署迁移，但不涉及业务数据。

### 灰度与回滚

- 可以只向管理员开放 admin SPA，回滚不影响 showcase。
- 两套前端版本与后端合同必须维护兼容矩阵。
- 删除 admin route/服务即可回滚 UI；cookie/CORS 改动需同步恢复。

## 8. 失败场景

- showcase 登录成功，跳 admin 后因 cookie 域/Path 不符再次要求登录。
- 两个站点同时 bootstrap，refresh token 轮转导致其中一个会话失败。
- shared package 版本漂移，两个应用对 AuthSession/错误码解释不同。
- 管理站发布新合同，但 showcase 仍用旧 auth client，401 行为出现差异。

## 9. 已知弱点与适用结论

该方案在组织规模较大、管理端需要独立网络边界或频繁演进时很有吸引力；当前仓库只有一个小型静态前端且共享同一设计/认证底座，立即拆分会产生明显重复。它适合作为未来边界，不是当前最经济的落点。
