# 方案 A：现有 SPA 最小增量直连

## 1. 定位

只扩展 `capability-showcase-frontend`，直接消费当前 `/auth/admin/**` 和 `/rag/documents` 契约；后端只修到可编译、可运行，不为前端增加分页、版本或共享管理 API。这是最快形成 demo 闭环的方案。

## 2. 架构与职责

```text
Vue SPA
  ├─ 现有 auth/session/catalog stores
  ├─ 新增 AdminView（用户/角色两个 tab）
  └─ 扩展 RagWorkspace visibility 表单
        │ Bearer / X-Api-Key
        ▼
edge-gateway
  ├─ /auth/admin/** -> auth-service
  └─ /rag/**        -> knowledge-service
```

- 登录态沿用 `authStore`；用 `auth.user.scopes.includes('role-admin')` 显示管理入口。
- 一个 `api/admin.ts` 包装当前 8 个管理端点，不新增复杂 stores。
- 用户/角色一次性全量拉取，在浏览器筛选。
- RAG 只给现有 upload capability 增加 visibility 字段；共享列表仍不做。
- 自助注册入口由 `VITE_REGISTRATION_ENABLED` 构建变量控制。

## 3. 核心流程

### 管理

1. 登录后读取 `AuthUser.scopes`。
2. 有 `role-admin` 时显示 `/admin`。
3. 进入页面并行 `GET /auth/admin/users`、`GET /auth/admin/roles`。
4. 编辑用户提交当前后端的全量 `PUT`；角色替换提交 `POST /users/{username}/roles`。
5. 请求成功后重新拉取两张列表。

### 共享入库

1. `capabilities.yml` 给 file multipart 加 query/form-data visibility，给 JSON 加 body visibility。
2. 登录账号有 `public-ingest` 时显示“共享”；无该 scope 时只显示租户。
3. API Key 模式显示选项但提示权限未知。
4. 共享检索仍透明合并，不显示来源 badge。

## 4. 改动范围

主要只改前端：router、App shell、AuthControl、LoginView、gate、RagWorkspace、capabilities.yml，并新增一个 AdminView、admin API 和测试。部署增加少量 Vite flags。

后端只要求：修复当前构造器/测试不一致；真正接入 `adminWritesEnabled`；保证当前 API 可用。

## 5. 扩展性与实施成本

- 预计 1–2 个开发阶段，前端改动约 12–18 个文件。
- 对几十个用户/少量角色足够；全量列表会随数据量线性恶化。
- 后续若补分页/ETag/共享列表，现有单页和 DTO 会被重写。

## 6. 风险评审

### 兼容性

- 优点：消费当前响应，后端改动最小；API Key 老流程不变。
- 风险：当前 `UserAdminView.scopes` 是 direct scopes，容易被 UI 误标；必须明确标“直配权限”。

### 事务、并发、幂等

- 当前全量 PUT/POST 没有 version，两位管理员会静默覆盖。
- 按钮防双击只能处理单浏览器重复提交，不能处理跨管理员并发。
- 同名创建 409 可视为幂等保护，但网络结果不明仍需手动刷新对账。
- AdminService 当前没有统一使用 `RbacMutationExecutor`，前端无法补救多表原子性。

### 性能

- 用户/角色全量下载、前端过滤；数据超过约 1,000 用户后体验与内存占用不可控。
- 管理页若仍受 catalog 门禁影响，会被无关静态目录请求拖慢。

### 安全

- 仅隐藏路由不是授权，仍依赖现有 server 403。
- 如果复用 `session.runContext()`，API Key 会覆盖登录 Bearer，管理员可能在不知情时用服务 Key 操作；必须为 admin API 强制 Bearer。
- 硬编码 demo 密码问题仍要独立修复。

### 数据迁移

- 无前端专用 DB 变更。
- 没有乐观锁列，也没有迁移成本；代价是保留并发缺陷。

### 灰度与回滚

- `VITE_RBAC_CONSOLE_ENABLED=false` 可隐藏整个管理路由；静态变量需要重建前端。
- `VITE_SHARED_KB_UI_ENABLED=false` 可回退 visibility UI；后端共享开关独立关闭。
- 回滚简单，删除/隐藏前端路由即可；后端已有数据不受影响。

## 7. 失败场景

- 管理员 A 打开 bob，管理员 B 改为 editor，A 随后提交 tenant 修改会把角色恢复为旧值。
- 角色被删除后用户关系仍存在，列表仍显示角色名但登录展开为空。
- 共享入库成功后，管理员在 UI 中看不到共享文档，也无法删除，只能用 curl/脚本。
- 查询结果无法说明是租户还是共享命中，用户会误判数据来源。
- 注册开关运行时变化后，静态 SPA 仍显示旧入口，直到重新构建。

## 8. 适用结论

适合短期演示，不适合作为“架构改造级、可直接长期运维”的最终控制台。它的主要价值是提供最低成本基线和回滚落点。
