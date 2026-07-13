# 方案 B：同一 SPA 的领域化管理中心 + 契约加固

## 1. 定位

在现有 Vue SPA 中建设独立、懒加载的管理域和权限基础设施，同时以加法方式补齐 auth/knowledge 的管理契约。普通能力控制台、RBAC 管理和共享知识库仍共享登录会话、设计 tokens 与部署，但代码边界清楚。这是推荐骨架。

## 2. 架构与职责

```text
capability-showcase-frontend
  ├─ Core: auth/session/credential/authorizedFetch
  ├─ Capability domain: catalog + runners
  ├─ Knowledge domain: tenant/shared document workspace
  └─ Admin domain (lazy): users + roles + scopes + conflict UI
          │ Bearer only for admin
          ▼
edge-gateway
  ├─ auth-service: hardened admin REST + public auth config
  └─ knowledge-service: visibility-aware document/query REST
```

职责边界：

- `authStore` 只管理会话；新增 permission composable 解释 credential mode 与 scopes。
- `adminUsersStore/adminRolesStore` 分别负责分页查询、草稿快照、ETag/version、错误和失效。
- `api/admin.ts` 只用 Bearer，不读取 API Key。
- 管理路由使用 `meta.requiredScopes=['role-admin']`；守卫只做体验裁决，server 仍授权。
- knowledge API 返回显式 visibility；RAG 页面不猜来源。
- 后端管理写加入乐观锁、事务、引用保护、session revoke 和明确错误码。

## 3. 建议接口加固

保留当前接口兼容，同时新增或加法扩展：

- `GET /auth/public-config`：registrationEnabled、password min/max；不泄露规则和 tenant 映射。
- `GET /auth/admin/users?offset&limit&q&tenant&role&enabled`：body 仍为数组，`X-Total-Count` 提供总数，兼容无查询调用。
- `GET /auth/admin/users/{username}`：返回 directScopes、effectiveScopes、roles、version。
- `PATCH /auth/admin/users/{username}`：部分更新；新 UI 必须带详情响应返回的 ETag版本作为 `If-Match`。
- `PUT /auth/admin/users/{username}/roles`：带 `If-Match` 的幂等全量替换；当前WIP的旧POST在首次发布前替换，不保留绕过入口。
- `POST /auth/admin/roles`：仅创建；`PUT /auth/admin/roles/{name}`：带 `If-Match` 的明确更新；角色详情含 assignedUserCount/version。
- `GET /rag/documents?visibility=tenant|public`，详情/删除同样带 visibility。
- `KnowledgeQueryService.Hit` 加 `visibility=tenant|public`。

数据库计划新增 `USERS.VERSION`、`ROLES.VERSION`，从 0 开始；更新使用 `WHERE ... AND VERSION=?`，陈旧版本统一映射 HTTP 412 `precondition_failed`。关系写和版本递增在同一 `RbacMutationExecutor` 原子单元内。当前管理 mutation 仍属本分支未发布 WIP，因此首次正式发布前直接规范为上述安全合同，不保留可绕过 `If-Match` 的无版本写别名；现有 GET/list 响应保持兼容。

## 4. 核心流程

### 页面启动与路由

1. `main.ts` 先恢复会话。
2. 获取 auth public config（可独立超时/失败降级）。
3. 能力路由加载 catalog；管理路由不等待 catalog。
4. 路由守卫根据 Bearer effective scopes 给出 Forbidden 页面；API Key 不可用于管理路由。

### 用户编辑

1. 列表按服务端筛选分页，行点击请求详情与 ETag/version。
2. 表单保存 `PATCH`，空密码字段不发送。
3. 412/409 打开冲突对话框，显示服务端新版本和本地草稿的字段差异。
4. 用户选择“放弃本地并刷新”或基于新版本重做；不提供无脑覆盖。
5. 成功后只失效当前页/相关角色计数，不整页 reload。

### 角色编辑

1. scope 选择器按业务域分组；真实角色中未知 scope 原样展示并保留。
2. 修改前展示 assignedUserCount 和会话生效延迟。
3. 删除被引用角色后端返回 `role_in_use`；UI 链接到按该角色筛选用户。
4. 自己权限被改时退出管理区并重新校验会话。

### 共享知识库

1. 读取运行时 public-enabled 能力（新增只读 endpoint 或 knowledge 响应能力字段）。
2. 双 tab 分别调用 tenant/public list。
3. 上传请求显式 visibility；共享文本入库二次确认，图片禁用。
4. 查询 hit 使用服务端 visibility badge；共享删除要求 `public-ingest` 且带 visibility。

## 5. 改动范围

- 前端约 30–40 个修改/新增文件，包含两个管理 stores、强类型 API、6 个页面/编辑器组件、权限 composable 和 E2E。
- auth-service 需要 AdminController/AdminService/DTO/store/schema 加固。
- knowledge-service 需要共享文档管理和查询 visibility。
- edge CORS 增加 `If-Match`，暴露 `ETag/X-Total-Count`；open path 精确增加 public-config。
- deploy 增前端 kill switches 和后端灰度开关。

## 6. 扩展性与实施成本

- 支持中等规模平台用户；服务端分页可扩至十万级，具体 DB 索引需压测决定。
- 管理域可继续增加审计页、注册规则只读页，而不污染 capability catalog。
- 预计 4–6 个开发阶段；跨前后端合同测试是主要成本。

## 7. 风险评审

### 兼容性

- GET/list与响应加法字段保持兼容；当前 mutation 是未发布WIP，首次正式发布前统一规范为需要If-Match的安全合同，分支内curl/脚本同批更新。
- DTO 新字段不会破坏 JSON 宽松客户端；若改原字段语义则必须避免，建议保留 `scopes` 过渡并新增命名明确字段。
- Query hit 增 visibility 是加法响应变化。

### 事务、并发、幂等

- version/ETag 解决跨管理员陈旧覆盖。
- 角色关系替换、用户资料与 VERSION 递增必须在同一事务/临界区。
- 创建仍依赖 username/role 主键实现自然幂等；网络不明时 GET 对账，不自动重发密码。
- 文档多 sink 仍非原子；UI 显示最终后端结果，监控补偿由后端承担。

### 性能

- 懒加载管理 chunk、服务端分页、取消旧请求、细粒度 cache invalidation。
- `findByRole` 当前 JDBC 逐用户 `findByUsername` 有 N+1，服务端分页实现前需改为 join/batch。
- 角色列表通常小，可整表缓存短 TTL；用户列表不可整表缓存。

### 安全

- 管理 API Bearer-only，避免 API Key 覆盖身份混淆。
- server 仍逐请求校验 role-admin；UI 路由守卫不是边界。
- Access token 保持内存；不持久化管理数据中的敏感字段。
- public-config 只暴露开关和密码长度，不暴露注册映射规则。

### 数据迁移

- USERS/ROLES 加 VERSION 为加法式迁移；旧行置 0。
- H2/MySQL 都要覆盖重复启动幂等；迁移前备份并对账 USER_ROLE/ROLE_SCOPE。
- 回滚旧代码可忽略 VERSION 列；新前端必须先于/同后端灰度控制启用。

### 灰度与回滚

- 前端 `VITE_RBAC_CONSOLE_ENABLED` 与 `VITE_SHARED_KB_UI_ENABLED` 是 UI kill switch。
- 后端先只读 admin API，再开写；前端检测 writesEnabled 后禁用表单。
- public read/write 分开观察：先显示共享 tab只读，再开 public upload/delete。
- 回滚前端不会损坏已迁移 schema；关闭新接口路由前先关 UI flag。

## 8. 已知弱点

- 同一 SPA 的依赖与发布仍耦合：管理页改动要重新发布整个控制台。
- 跨三个后端模块的合同加固增加协调成本。
- VERSION 只能保护 auth DB 对象；不能让已签 access token 即时失效。
- scope catalog 仍是前端静态“已知说明 + 后端未知值保留”，不是中央权限注册中心。
- 共享文档多 sink 原子性不因前端改善。

## 9. 适用结论

在不引入新运行时服务的前提下，这是正确性、体验、扩展性和运维成本最均衡的方案。
