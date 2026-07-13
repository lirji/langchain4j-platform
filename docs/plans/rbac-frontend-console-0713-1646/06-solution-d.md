# 方案 D：专用 Console BFF + 服务端会话

## 1. 定位

新增一个 Console BFF（计划新增模块，仓库当前不存在），浏览器不再直持 access token，也不直调 auth/knowledge 管理接口；BFF 以同源 httpOnly session 与 CSRF token服务 Vue UI，聚合 RBAC、共享知识库和运行时能力配置。

## 2. 架构与职责

```text
Browser Vue SPA
  └─ same-origin /console/api/** + httpOnly console session + CSRF
          ▼
console-bff（计划新增 Spring Boot 服务）
  ├─ session store / CSRF / rate limit
  ├─ auth admin DTO 聚合与错误归一
  ├─ knowledge visibility DTO 聚合
  └─ 用内部服务凭证或用户委托令牌调用 edge/services
          ▼
auth-service / knowledge-service / edge-gateway
```

BFF 可作为静态资源宿主，或 nginx 把 `/console/api` 反代给它。

## 3. 身份委托选项

必须在实现前选择，不能含糊：

1. **用户令牌委托：**BFF 保存 auth-service access/refresh，会按用户 scopes 调后端。安全语义最接近当前链，但需服务器端会话存储和 token rotation。
2. **BFF 服务身份 + 显式 actor：**BFF 持平台级服务凭证，再把用户 actor 传给后端。当前 `TenantContext`/JWT 没有安全的 impersonation/actor 模型，直接做会绕过现有授权，因此本计划不推荐。

若选 D，只能先采用用户令牌委托。

## 4. 核心流程

- 登录：浏览器 POST BFF；BFF 调 `/auth/login`，把 refresh/access 只存服务端 session，浏览器只收随机 session cookie。
- API：浏览器带 session cookie + CSRF header；BFF读取用户令牌调用后端，401 时服务端单飞 refresh。
- 管理：BFF聚合 users/roles/scopes/运行时 flags，为 UI 提供稳定的 `/console/api/v1`。
- 共享 RAG：BFF统一转换 visibility 字段并可隐藏 `__public__` 实现细节。

## 5. 改动范围

- 计划新增 Maven 模块（名称需实施前确认，例如 `console-service`，不能当作现有模块）：Spring Web、session store、auth/knowledge clients、CSRF、observability、tests。
- 前端所有 API 改走 BFF，现有 `authorizedFetch/sessionStore` 被大幅重构。
- edge/deploy/Helm 新增路由、service、Redis session、secret、健康检查和容量配置。
- auth/knowledge 原接口仍需正确，但分页/乐观锁可由 BFF合同封装，不能由 BFF替代底层事务正确性。

## 6. 扩展性与实施成本

- 最适合长期企业控制面：集中审计、字段裁剪、CSRF、运行时配置和跨服务聚合。
- 也是最大改造：新增有状态服务、运维依赖和故障点；预计 8–12 个开发阶段。
- 当前 showcase 的“纯静态、零额外运行时依赖”优势会部分丢失。

## 7. 风险评审

### 兼容性

- 可通过保留 direct mode 让现有 capability runner 继续直调，管理域走 BFF；但两套请求栈增加认知成本。
- 完全切 BFF 会改变部署、CORS、cookie、故障处理和本地开发方式。

### 事务、并发、幂等

- BFF 可统一 ETag/Idempotency-Key，但底层 auth 多表事务仍必须修。
- BFF 对网络超时的 mutation 不应盲重试；可在服务端记录幂等 key，但这又需要新表/Redis策略。
- 服务端 refresh single-flight 要按 session/token family 协调多实例，不能只用 JVM 内锁。

### 性能

- 每次多一跳；用户/角色聚合可缓存，但权限数据缓存失效困难。
- BFF 可减少浏览器往返和 payload；收益取决于实际数据量。
- Redis session 不可用会使整个控制台不可用，而当前静态能力页仍可用 API Key。

### 安全

- 浏览器不见 access token，XSS 令牌窃取风险下降。
- cookie session 让 CSRF 成为必须处理的问题：SameSite、CSRF token、Origin/Referer 校验缺一不可。
- BFF 持用户 refresh token/会话成为高价值目标；需加密、最小日志和密钥轮转。
- 绝不能使用平台服务 Key替用户授权，否则 role-admin/tenant 边界会被 BFF逻辑取代。

### 数据迁移

- auth VERSION 迁移同 B。
- 另需 console session/idempotency 存储的数据生命周期、TTL、清理和回滚方案。
- 从浏览器 refresh cookie 切到 BFF session 需要强制重新登录或双会话过渡。

### 灰度与回滚

- 可先仅 `/admin/**` 走 BFF，capability direct mode 不动。
- 双写/双会话阶段最复杂；回滚必须保留旧 `/auth` 反代和 Vite request stack。
- BFF故障时前端需明确“管理中心不可用”，不能降级为使用高权限 API Key。

## 8. 失败场景

- BFF session 存在但 auth refresh 已撤销，UI循环 401。
- BFF多副本同时刷新同一 token，轮转竞争导致会话失效。
- CSRF配置错误导致合法管理写被拒，或更严重地允许跨站写。
- 聚合缓存未失效，管理员看到旧 roles/effective scopes 并基于旧版本编辑。
- BFF 服务身份配置过权，代码缺陷造成跨租户/越权调用。

## 9. 已知弱点与适用结论

D 的浏览器令牌安全和聚合能力最强，但与当前静态 SPA/edge 双模架构差异最大。除非产品明确要求控制面独立安全边界、服务端会话和审计聚合，否则本期采用会过度扩张范围。可把 B 的 API/模块边界设计成未来能迁移到 BFF。
