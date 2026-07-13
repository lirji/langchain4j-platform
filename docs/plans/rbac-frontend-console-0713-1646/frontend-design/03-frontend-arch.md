# 03 · 前端架构与实现方案（RBAC 配套控制台）

> 状态：**只读规划，本文件不改任何代码**。对齐 `feat/rbac-shared-kb` 真实工作树。所有"计划新增/修改"的文件、函数、类型当前尚不存在或需改动；引用到的既有名称均已从仓库逐一核对。实施顺序、契约与 §8.1/§8.2/§7（IA）以 `../FINAL_PLAN.md` 为纲，本文补齐"落到文件级、可据以直接实现"的前端细节。

---

## 0. 事实基线与对既有约定的复用

### 0.1 已核对的后端契约（据此定 api/types，未臆造）

逐文件核对结果（部分已在当前 WIP 落地，比 FINAL_PLAN §0 描述更完整）：

| 契约 | 事实来源 | 关键结论 |
|---|---|---|
| 登录/刷新用户视图 | `dto/UserView.java` = `record UserView(String username, String tenant, List<String> scopes)` | **无 userId、无 roles**；`scopes` 即有效 scopes。前端 `AuthUser` 保持三字段。 |
| 公开配置 | `dto/AuthPublicConfig.java` = `(boolean registrationEnabled, int passwordMinLength, int passwordMaxLength)` | DTO 已存在；`GET /auth/public-config` 为 open 路径。`registrationEnabled = rbac.enabled && registration.enabled`。 |
| 注册 | `dto/RegisterRequest.java` = `(String username, String password)` | 成功即登录（返回同 login 的 session）；租户/角色由后端规则引擎按用户名域名推导，**前端不选**。 |
| 用户视图 | `AdminDtos.UserAdminView(username, userId, tenant, Set<String> directScopes, Set<String> roles, Set<String> effectiveScopes, boolean enabled, long version)` | 字段与任务书一致；JSON 里 Set→数组。 |
| 角色视图 | `AdminDtos.RoleView(name, Set<String> scopes, description, long version, int assignedUserCount)` | 一致。 |
| 用户写请求 | `CreateUserRequest(username,password,tenant,roles,directScopes,enabled)`；`UpdateUserRequest(tenant,password,directScopes,enabled)`（**PATCH 语义：null=不改，`directScopes:[]`=清空，不含 roles**）；`ReplaceRolesRequest(roles)` | roles 仅经 `PUT /users/{u}/roles` 全量替换。 |
| 角色写请求 | `CreateRoleRequest(name,scopes,description)`；`UpdateRoleRequest(scopes,description)`（name 取路径） | — |
| Admin 路由 | `AdminController @RequestMapping("/auth/admin")`：`GET/POST /users`（`offset`/`limit`，响应头 `X-Total-Count`，默认 `limit=50`）、`GET/PATCH/DELETE /users/{username}`、`PUT /users/{username}/roles`、`GET/POST /roles`、`GET/PUT/DELETE /roles/{name}` | 写接口的 `If-Match` **可选**（`required=false`），值为**裸版本号**（非 ETag 引号语义，格式非法→400 `invalid_if_match`）。**删除当前无 If-Match**。 |
| **错误合同（当前实现）** | `AuthExceptionHandler` 统一 `{error,message}`；`AdminService`/`AdminController` 抛点 | **所有冲突均为 409 + `error` 判别码**：`version_conflict`/`username_taken`/`role_exists`/`role_in_use`/`last_admin`；另 403 `forbidden`、503 `rbac_writes_disabled`、400 `invalid_if_match`。`message` 已是**中文人话**。 |
| 知识运行时配置 | `platform-protocol/.../KnowledgeRuntimeView(int contractVersion, boolean publicEnabled, boolean sharedImagesSupported)`；`GET /rag/config`（`KnowledgeQueryController`，`contractVersion=1`，`sharedImagesSupported=false`） | 已存在。 |
| 文档 | `DocumentInfo(docId, tenantId, displayName, contentType, long sizeBytes, int segmentCount, int version, Instant uploadedAt, category)`；`GET/DELETE /rag/documents[/{docId}]?visibility=` | 已支持 `visibility`（`public`/`shared`=共享，缺省=当前租户）。**共享文档以 `tenantId=="__public__"` 标识**（`PublicKb.TENANT_ID`）。删共享需 `public-ingest`。 |
| 检索命中 | `platform-protocol/.../KnowledgeHit(..., String visibility)` | `visibility` 已是末尾加法字段，由服务端权威给出（前端不靠 docId/名称推断）。 |

> **与 FINAL_PLAN §9.2 的差异（务必按当前实现落地）：** FINAL_PLAN 提议把陈旧写升级为 **412 `precondition_failed` / 428 `precondition_required`**，并给删除加 If-Match。**当前工作树尚未如此**——版本冲突是 **409 `version_conflict`**、删除无 If-Match、`listUsers` 仅 `offset/limit`（无 `q/tenant/role/enabled` 服务端筛选）、`GET /auth/admin/config` 不存在。前端**按当前 409 判别码合同实现**，并对"未来 412/428、服务端筛选、admin/config 写开关探测"预留**加法兼容点**（见 §3.6、§7 风险表）。**这是本文与 FINAL_PLAN 唯一需要显式对齐的分歧点。**

### 0.2 scope 事实（决定 `hasScope` 的实现）

`SeedRoles.java`/`RoleService.java` 核对：**scope 是显式 token，无通配符**。`admin` 角色展开为显式集合，含 `role-admin`、`public-ingest`。因此 `role-admin` 就是 `effectiveScopes` 里的一个普通 token，`hasScope('role-admin')` 精确匹配即可，**无需通配逻辑**（若后端未来引入 `*`，再扩展 `hasScope`——见 §7 假设）。

### 0.3 必须严格复用的既有前端约定

- **目录结构**：`src/{api,stores,composables,components,modules,router,utils,config,types,styles}`；新增文件落对应目录。
- **Store 写法**：Pinia **setup store**（`defineStore('x', () => {...})`），`ref/computed` + 返回对象；getter 内**延迟** `useAuthStore()` 以避免循环依赖时序（`stores/session.ts` 已有范例）。
- **凭证硬约束**：accessToken / apiKey / password **仅内存**，绝不写 `localStorage`/`sessionStorage`/URL/日志（`stores/auth.ts`、`stores/session.ts` 注释即约束）。新 admin store 同样**不得持久化**。
- **纯函数优先 + 同目录 Vitest**：`*.test.ts` 与被测同目录（`router/guard.test.ts`、`stores/*.test.ts`、`api/*.test.ts`、`utils/gate.test.ts`）。裁决逻辑一律抽成纯函数便于单测。
- **凭证单一注入路径**：业务能力经 `sessionStore.runContext()` → `client.assembleRequest`（`apiKey ? X-Api-Key : Bearer`）。**管理域是唯一例外：Bearer-only，绕开 runContext**（见 §3.3）。
- **401 续期**：`authorizedFetch` 仅在 `Authorization: Bearer` 且 401 时触发 `auth.refresh()`（store 内**单飞**），重试一次；api-key 的 401 不续期。
- **样式**：全用 `styles/tokens.css` 变量（`--space-*`、`--radius`、`--primary`、`--surface`、`--danger`、`--success` 等）；中文注释；`@media (prefers-reduced-motion)` 守卫动画。
- **可复用展示件**：`_shared/{WorkbenchSection,InfoNote,ResultTable}`、`common/{EmptyState,StatCard,CopyButton,FavoriteStar}`、`capability/badges/ScopeBadge`、`composables/{useAbortable,useFocusTrap}`。**优先复用，不引第三方 UI 库**（`package.json` 只有 vue/pinia/vue-router/marked/dompurify）。

---

## 1. 路由与守卫

### 1.1 路由表增改（`src/router/index.ts`）

新增 **register / admin（含子路由）/ forbidden**，全部**懒加载**；admin 域受构建开关 `RBAC_CONSOLE_ENABLED` 控制**是否注册**（关时连路由都不存在，命中通配 `→ '/'`，即最硬的 kill switch）。用 `meta` 承载两类新语义：`requiredScopes`（scope 门禁）与 `bypassCatalog`（脱离 catalog 门禁）。

```ts
// RouteMeta 增补（模块增强，放 router/index.ts 顶部）
declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean          // 既有：公开全屏页（登录/注册）
    requiredScopes?: string[] // 新增：进入该路由所需的 Bearer 有效 scopes（管理域 = ['role-admin']）
    bypassCatalog?: boolean   // 新增：该路由不依赖能力目录加载（admin/register/forbidden 用）
  }
}

// 路由表新增片段（其余保持不变）
{ path: '/register', name: 'register',
  component: () => import('../modules/auth/RegisterView.vue'),
  meta: { public: true, bypassCatalog: true } },
{ path: '/forbidden', name: 'forbidden',
  component: () => import('../modules/admin/ForbiddenView.vue'),
  meta: { bypassCatalog: true } },
...(RBAC_CONSOLE_ENABLED ? [{
  path: '/admin',
  component: () => import('../modules/admin/AdminLayout.vue'),
  meta: { requiredScopes: ['role-admin'], bypassCatalog: true },
  children: [
    { path: '', redirect: '/admin/users' },
    { path: 'users', name: 'admin-users', component: () => import('../modules/admin/UsersView.vue') },
    { path: 'users/:username', name: 'admin-user', props: true,
      component: () => import('../modules/admin/UserEditor.vue') },
    { path: 'roles', name: 'admin-roles', component: () => import('../modules/admin/RolesView.vue') },
    { path: 'roles/:name', name: 'admin-role', props: true,
      component: () => import('../modules/admin/RoleEditor.vue') },
  ],
}] : []),
// 既有通配保持在最后：{ path: '/:pathMatch(.*)*', redirect: '/' }
```

### 1.2 守卫分层：`resolveAuthNavigation` 之上叠加 `resolveRouteAccess`（纯函数）

**关键设计决定：不改 `resolveAuthNavigation`**（它的 8 个既有单测必须保持绿），而是新增一个**组合纯函数** `resolveRouteAccess`，先复用登录层裁决（保留 redirect 清洗、已登录跳离 `/login`、回滚短路），再叠加 register 门禁与 admin scope 门禁。

```ts
export interface RouteAccessContext {
  isAuthenticated: boolean            // Bearer 登录态 = auth.isAuthenticated
  requireLogin: boolean               // REQUIRE_LOGIN
  effectiveScopes: string[]           // auth.user?.scopes ?? []（Bearer 身份 scopes；api-key 不参与）
  registrationEnabled: boolean | null // auth.publicConfig?.registrationEnabled ?? null（null=未探测→fail-closed）
  consoleEnabled: boolean             // RBAC_CONSOLE_ENABLED
}

/** 读取路由 meta.requiredScopes（缺省空数组）。 */
function requiredScopesOf(meta: RouteLocationNormalized['meta']): string[] {
  const s = (meta as { requiredScopes?: unknown }).requiredScopes
  return Array.isArray(s) ? (s as string[]) : []
}

/**
 * 组合守卫裁决（纯函数，便于单测）：
 * 1) 先跑登录层（复用 resolveAuthNavigation）——非 true 即重定向，直接返回（保留 redirect 清洗）。
 * 2) /register：已登录→'/'；registrationEnabled!==true（关闭或未探测）→回 /login（fail-closed）。
 * 3) requiredScopes 路由（管理域，Bearer-only）：
 *    - consoleEnabled=false → '/'（双保险，正常也不注册路由）。
 *    - 未登录（仅 requireLogin=false 时可达此处）→ /login 带 redirect。
 *    - 缺任一所需 scope → { name:'forbidden' }（深链落点，不带原 path，避免回环/泄露）。
 * 4) 其余放行。
 * 注意：本函数不读 sessionStore —— API Key 覆盖与否，均不改变管理域裁决（Bearer-only）。
 */
export function resolveRouteAccess(
  to: Pick<RouteLocationNormalized, 'name' | 'fullPath' | 'meta' | 'query'>,
  ctx: RouteAccessContext,
): true | RouteLocationRaw {
  const authDecision = resolveAuthNavigation(to, ctx) // 复用既有
  if (authDecision !== true) return authDecision

  if (to.name === 'register') {
    if (ctx.isAuthenticated) return { path: '/' }
    if (ctx.registrationEnabled !== true) return { name: 'login' }
    return true
  }

  const need = requiredScopesOf(to.meta)
  if (need.length) {
    if (!ctx.consoleEnabled) return { path: '/' }
    if (!ctx.isAuthenticated) return { name: 'login', query: { redirect: to.fullPath } }
    const ok = need.every((s) => ctx.effectiveScopes.includes(s))
    if (!ok) return { name: 'forbidden' }
  }
  return true
}
```

`beforeEach` 改为调 `resolveRouteAccess`：

```ts
router.beforeEach((to) => {
  const auth = useAuthStore()
  return resolveRouteAccess(to, {
    isAuthenticated: auth.isAuthenticated,
    requireLogin: REQUIRE_LOGIN,
    effectiveScopes: auth.user?.scopes ?? [],
    registrationEnabled: auth.publicConfig?.registrationEnabled ?? null,
    consoleEnabled: RBAC_CONSOLE_ENABLED,
  })
})
```

### 1.3 API Key 覆盖时，管理入口如何判定（业务规则 7）

**结论：guard 与管理入口一律以 Bearer 身份的 `effectiveScopes` 裁决，完全忽略 api-key。**

- 守卫 `resolveRouteAccess` 不读 `sessionStore`，`effectiveScopes` 取自 `auth.user.scopes`（Bearer）。即使顶栏填了 api-key（`session.credentialMode==='api-key'`），只要登录用户缺 `role-admin`，深链 `/admin/**` → `{name:'forbidden'}`；直接 HTTP 仍由后端 403 兜底。
- 管理入口的**可见性**（Header/SideNav/命令面板）由 `auth.isAdmin`（= `hasScope('role-admin')`）驱动，**不**由 `session.hasCredential` 驱动——避免"填了 api-key 就冒出管理菜单"的身份混淆。
- 管理域**内**所有请求走 `api/admin.ts` 的 Bearer-only 通道（§3.3），即便 `session.apiKey` 非空也**不**注入 `X-Api-Key`。

### 1.4 admin 懒加载 + 与 catalog 门禁解耦（`src/App.vue`）

现状：`App.vue` 把所有**非 public** 路由卡在 `catalog.status`（loading/error 时只显示 `EmptyState`，不渲染 `RouterView`）。admin 不依赖能力目录，必须脱离该门禁，否则 catalog 拉取失败会连带打不开管理页。

改动（最小面）：新增 `needsCatalog` 计算，`bypassCatalog` 路由直接渲染 `RouterView`：

```ts
const isAuthRoute = computed(() => route.meta.public === true)          // 既有：全屏公开页
const needsCatalog = computed(() => !isAuthRoute.value && route.meta.bypassCatalog !== true)
```

模板里 `<main>` 内的三分支改为：仅当 `needsCatalog` 为真时才对 `status==='loading'/'error'` 显示 `EmptyState`；否则（bypassCatalog）直接渲染 `RouterView`。register 走 `isAuthRoute` 全屏分支，天然不受 catalog 影响；admin/forbidden 走壳内分支但跳过 catalog 门禁。admin 代码经 `() => import()` 懒加载，普通能力路径首屏 bundle 不含管理域（性能预算）。

### 1.5 register 受 public-config 运行时门禁（业务规则 10）

- `main.ts` bootstrap 在 auth 静默续期后**追加**一次 `auth.loadPublicConfig()`（best-effort，失败不阻断登录，见 §2.1/§3.5）。
- 守卫层：`resolveRouteAccess` 对 `/register` 在 `registrationEnabled !== true`（含 `null` 未探测）时 fail-closed 回 `/login`。
- 视图层：`RegisterView` 用 `publicConfig.passwordMin/MaxLength` 动态渲染密码规则；`LoginView` 仅在 `registrationEnabled===true` 时显示"去注册"入口。

### 1.6 redirect 清洗保留 + 回滚开关

- `sanitizeRedirect` **不动**；`resolveRouteAccess` 通过复用 `resolveAuthNavigation` 天然保留开放重定向防护（`//evil`、`http://` 被挡）。admin `forbidden` 落点**不**带原 `fullPath`（避免把受限路径塞进 query 造成回环）。
- 回滚开关双层：`REQUIRE_LOGIN=false`（既有，守卫短路回纯 api-key 老流程）；`RBAC_CONSOLE_ENABLED=false`（新增，admin 路由不注册）。二者独立，任一关闭都能安全降级到"能力试用台"形态。

### 1.7 守卫单测点（`src/router/guard.test.ts` 追加，纯函数）

`resolveRouteAccess` 矩阵（`resolveAuthNavigation` 既有 8 例保持不变作回归）：
1. 未登录 + `requiredScopes` + `requireLogin=true` → 登录层已先行 `{name:'login',redirect}`（复用既有）。
2. `requireLogin=false` + `requiredScopes` + 未登录 → `{name:'login',query:{redirect}}`（scope 门禁强制登录，即便回滚模式）。
3. 已登录 + 有 `role-admin` → `true`。
4. 已登录 + 缺 `role-admin` → `{name:'forbidden'}`。
5. **api-key 存在但登录用户缺 scope** → 仍 `{name:'forbidden'}`（断言 guard 不受 api-key 影响：ctx 不含 api-key 字段即证明）。
6. `consoleEnabled=false` + admin 路由 → `{path:'/'}`。
7. `/register`：`registrationEnabled=true` 未登录 → `true`；`=false`/`=null` → `{name:'login'}`；已登录 → `{path:'/'}`。
8. `requiredScopes` 多值：缺其一即 forbidden，全含才放行。
9. `requiredScopesOf` 对非数组 `meta` 返回 `[]`（健壮性）。

---

## 2. 状态层

### 2.1 `stores/auth.ts` 增量

在既有 setup store 上**加法**（不动 accessToken/user/refresh 单飞/bootstrap）：

```ts
import { fetchPublicAuthConfig, registerRequest, type AuthPublicConfig } from '../api/auth'

const publicConfig = ref<AuthPublicConfig | null>(null)

// scope 集合：user 整体替换即自动重算（保持反应性；切勿原地 mutate user）
const scopeSet = computed(() => new Set(user.value?.scopes ?? []))
function hasScope(scope: string): boolean { return scopeSet.value.has(scope) }
function hasAllScopes(scopes: string[]): boolean { return scopes.every((s) => scopeSet.value.has(s)) }
const isAdmin = computed(() => scopeSet.value.has('role-admin'))

/** 自助注册（成功即登录，与 login 同构）。失败抛 ApiError 交上层。 */
async function register(username: string, password: string): Promise<void> {
  const session = await registerRequest(username.trim(), password)
  setSession(session.accessToken, session.user)   // 复用既有 setSession（整体替换 user）
}

/** best-effort 拉取公开配置；失败静默（注册入口 fail-closed，绝不阻断登录）。 */
async function loadPublicConfig(): Promise<void> {
  try { publicConfig.value = await fetchPublicAuthConfig() } catch { /* 保持 null */ }
}
```

`return` 追加 `publicConfig, hasScope, hasAllScopes, isAdmin, register, loadPublicConfig`。**反应性要点**：`refresh()`/`setSession` 已是**整体替换** `user.value`，`scopeSet`/`hasScope`/`isAdmin` 随之重算——严格满足"refresh 后替换 user/scopes"，无需额外处理。

`main.ts` bootstrap 追加（非阻塞）：`if (REQUIRE_LOGIN) { await useAuthStore(pinia).bootstrap(); void useAuthStore(pinia).loadPublicConfig() }`（config 拉取不 `await`，不拖慢首屏；未登录也应能拉 public-config 以决定注册入口，故放 bootstrap 之外亦可——实现二选一，注释说明）。

### 2.2 `stores/session.ts` 增量

```ts
import { useAuthStore } from './auth'
export type CredentialMode = 'bearer' | 'api-key' | 'none'

// api-key 显式覆盖优先，其次 Bearer 登录，最后无凭证
const credentialMode = computed<CredentialMode>(() =>
  hasApiKey.value ? 'api-key' : (useAuthStore().isAuthenticated ? 'bearer' : 'none'))

/** 两种凭证同时存在：api-key 覆盖了登录会话（顶栏据此高对比警告）。 */
const apiKeyOverridesBearer = computed(() => hasApiKey.value && useAuthStore().isAuthenticated)

/**
 * 统一 permission 上下文（供 gate/usePermission，避免通用 runner 与专用页面裁决漂移）。
 * effectiveScopes 仅在 Bearer 模式给出；api-key 模式为空（权限不透明，反应式鉴权）。
 */
function permissionContext(): { hasApiKey: boolean; credentialMode: CredentialMode; effectiveScopes: string[] } {
  const auth = useAuthStore()
  return {
    hasApiKey: hasCredential.value,   // 字段名沿用 gate.GateContext 兼容语义=是否有任一可执行凭证
    credentialMode: credentialMode.value,
    effectiveScopes: credentialMode.value === 'bearer' ? (auth.user?.scopes ?? []) : [],
  }
}
```

`return` 追加 `credentialMode, apiKeyOverridesBearer, permissionContext`。**反应性要点**：沿用既有"getter 内延迟 `useAuthStore()`"写法，不在顶层解构 store。

### 2.3 `stores/adminUsers.ts`（新）——用户列表 + 详情快照 + 局部失效

**单一职责边界**：列表分页/防抖/中止/乱序保护**全部委托** `usePagedQuery`（§4.2，避免与其重复实现）；本 store 只叠加"跨视图详情快照 + 乐观锁版本 + 局部失效 + 写动作"。**不持久化**。

```ts
export const useAdminUsersStore = defineStore('adminUsers', () => {
  // ── 列表：复用 usePagedQuery 的乱序/中止/防抖保证 ──
  const list = usePagedQuery<UserAdminView>({
    pageSize: 50,
    debounceMs: 300,
    fetcher: ({ offset, limit, filters, signal }) =>
      fetchUsers({ offset, limit, ...toUserFilters(filters) }, signal), // 见 §3.3 契约依赖
  })

  // ── 详情快照：编辑草稿的服务端基线 + version（冲突时用于 diff） ──
  const selected = ref<UserAdminView | null>(null)
  const selectedStatus = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const selectedError = ref<string | null>(null)

  async function loadDetail(username: string): Promise<void> {
    selectedStatus.value = 'loading'; selectedError.value = null
    try { selected.value = await fetchUser(username); selectedStatus.value = 'ready' }
    catch (e) { selectedError.value = humanizeError(e); selectedStatus.value = 'error' }
  }

  // ── 局部失效：写成功后就地替换，不整表重拉 ──
  function applyLocal(next: UserAdminView): void {
    list.patchItem((u) => u.username === next.username, next)
    if (selected.value?.username === next.username) selected.value = next
  }
  function removeLocal(username: string): void {
    list.removeItem((u) => u.username === username)
    if (selected.value?.username === username) selected.value = null
  }

  // ── 写动作：成功局部失效；version_conflict 自动刷新 selected 基线后重抛（不吞） ──
  async function saveUserPatch(username: string, req: UpdateUserRequest, version: number): Promise<UserAdminView> {
    try { const v = await patchUser(username, req, version); applyLocal(v); return v }
    catch (e) { if (apiErrorCode(e) === 'version_conflict') await loadDetail(username); throw e }
  }
  async function saveUserRoles(username: string, roles: string[], version: number): Promise<UserAdminView> {
    try { const v = await replaceUserRoles(username, roles, version); applyLocal(v); return v }
    catch (e) { if (apiErrorCode(e) === 'version_conflict') await loadDetail(username); throw e }
  }
  async function createUserAction(req: CreateUserRequest): Promise<UserAdminView> {
    const v = await createUser(req); list.reload(); return v   // 计数变化 → 整页重拉
  }
  async function deleteUserAction(username: string): Promise<void> {
    await deleteUser(username); removeLocal(username); list.reload()
  }

  return {
    // 列表（透传 usePagedQuery 的响应式）
    items: list.items, total: list.total, offset: list.offset, pageSize: list.pageSize,
    filters: list.filters, status: list.status, error: list.error,
    setFilter: list.setFilter, nextPage: list.nextPage, prevPage: list.prevPage,
    hasNext: list.hasNext, hasPrev: list.hasPrev, reload: list.reload, load: list.load,
    // 详情 + 写
    selected, selectedStatus, selectedError, loadDetail, applyLocal, removeLocal,
    saveUserPatch, saveUserRoles, createUserAction, deleteUserAction,
  }
})
```

- **筛选防抖**：`setFilter(key, value)` 内置 300ms 防抖（在 `usePagedQuery`），且 `offset` 归零。
- **乱序响应保护**：`usePagedQuery` 用单调 `seq` 只接受最新一次请求结果 + `AbortController` 中止在途（见 §4.2）。
- **version**：`selected.version` 即写回 `If-Match` 用的版本号；冲突时先 `loadDetail` 刷新基线，视图保留本地草稿做 diff。

### 2.4 `stores/adminRoles.ts`（新）——角色列表 + 绑定计数 + scope 保留

角色无分页（数量少，`GET /roles` 一次拉全），故**不**用 `usePagedQuery`，直接持列表：

```ts
export const useAdminRolesStore = defineStore('adminRoles', () => {
  const roles = ref<RoleView[]>([])
  const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const error = ref<string | null>(null)
  const selected = ref<RoleView | null>(null)

  const roleNames = computed(() => roles.value.map((r) => r.name))                // 供 RolePicker options
  const inUse = computed(() => new Set(roles.value.filter((r) => r.assignedUserCount > 0).map((r) => r.name)))

  async function load(): Promise<void> { /* fetchRoles → roles；status/error 机 */ }
  async function loadDetail(name: string): Promise<void> { /* fetchRole → selected */ }
  function applyLocal(next: RoleView): void { /* 就地替换 roles 中同名行 + selected */ }
  function removeLocal(name: string): void { /* 从 roles 移除 + 清 selected */ }

  async function saveRole(name: string, req: UpdateRoleRequest, version: number): Promise<RoleView> {
    try { const v = await updateRole(name, req, version); applyLocal(v); return v }
    catch (e) { if (apiErrorCode(e) === 'version_conflict') await loadDetail(name); throw e }
  }
  async function createRoleAction(req: CreateRoleRequest): Promise<RoleView> { const v = await createRole(req); await load(); return v }
  async function deleteRoleAction(name: string): Promise<void> { await deleteRole(name); removeLocal(name) }
  // 注：role_in_use 由后端 409 保护；视图捕获后引导到"该角色的绑定用户"筛选（§5.2 RoleEditor）

  return { roles, status, error, selected, roleNames, inUse, load, loadDetail, applyLocal, removeLocal, saveRole, createRoleAction, deleteRoleAction }
})
```

- **scope 保留**：编辑角色时 `ScopePicker` 对**未知 scope**（不在 `scopeCatalog`）也原样显示与回写，绝不丢弃（业务规则）。
- **局部失效**：写成功用返回视图就地替换，`assignedUserCount` 随服务端刷新。

### 2.5 反应性与乱序保护统一约定

- Store 一律返回 `ref/computed`；视图用 `storeToRefs` 或 `store.x` 访问，**禁止解构**基本类型（失反应）。
- 需要"最新赢"的异步读（列表页、详情、RAG 文档列表）**必须**走 `usePagedQuery` 或 `useAbortable`+`seq` 模式；组件卸载 `onScopeDispose` 中止在途。

### 2.6 状态层单测点

- `auth`：`hasScope` 命中/未命中；`isAdmin`；`register` 成功写 session、不落 storage；`loadPublicConfig` 成功/失败（失败保持 `null` 且不抛）；`refresh` 后 `scopeSet` 重算（登录 scope=['chat']→refresh 返回含 'role-admin'→`isAdmin` 由 false 变 true）。
- `session`：`credentialMode` 三态；`apiKeyOverridesBearer`；`permissionContext` 在 api-key 模式 `effectiveScopes=[]`、bearer 模式取 `user.scopes`。
- `adminUsers`：`applyLocal` 就地替换行 + selected；`removeLocal`；`saveUserPatch` 成功 `applyLocal`、`version_conflict` 时先 `loadDetail` 再重抛（用 mock `patchUser`/`fetchUser` 断言调用顺序）；`createUserAction`/`deleteUserAction` 触发 `reload`。
- `adminRoles`：`load` 填表；`inUse` 集合；`saveRole` 冲突刷新；`roleNames` getter。

---

## 3. API 与类型层

### 3.1 `types/admin.ts`（新，与后端 record 一一对应）

```ts
/** 用户视图（不含口令）。version 为乐观锁版本，写回经 If-Match 带回。 */
export interface UserAdminView {
  username: string; userId: string; tenant: string
  directScopes: string[]; roles: string[]; effectiveScopes: string[]
  enabled: boolean; version: number
}
export interface RoleView {
  name: string; scopes: string[]; description: string
  version: number; assignedUserCount: number
}
export interface CreateUserRequest {
  username: string; password: string; tenant: string
  roles: string[]; directScopes: string[]; enabled: boolean
}
/** PATCH 语义：undefined=不改；directScopes:[]=清空。不含 roles（专用 PUT 替换）。 */
export interface UpdateUserRequest {
  tenant?: string; password?: string; directScopes?: string[]; enabled?: boolean
}
export interface ReplaceRolesRequest { roles: string[] }
export interface CreateRoleRequest { name: string; scopes: string[]; description: string }
export interface UpdateRoleRequest { scopes: string[]; description: string }

/** 统一错误体 {error,message}（AuthExceptionHandler）。 */
export interface AuthErrorBody { error: string; message: string }
/** 409 家族判别码（当前实现，均 HTTP 409）。 */
export type AdminConflictCode =
  | 'version_conflict' | 'username_taken' | 'role_exists' | 'role_in_use' | 'last_admin'

export interface PagedResult<T> { items: T[]; total: number }
```

### 3.2 `types/knowledge.ts`（新）

```ts
export type Visibility = 'tenant' | 'public'
/** 后端 Instant → ISO-8601 字符串；category 可空。 */
export interface DocumentInfo {
  docId: string; tenantId: string; displayName: string; contentType: string
  sizeBytes: number; segmentCount: number; version: number
  uploadedAt: string; category: string | null
}
export interface KnowledgeRuntimeView {
  contractVersion: number; publicEnabled: boolean; sharedImagesSupported: boolean
}
/** 强类型化的检索命中（若前端选择结构化解析 rag.query 结果）。visibility 服务端权威。 */
export interface KnowledgeHitView {
  id: string; score: number; docId: string; displayName: string
  category: string | null; index: string; text: string; source: string
  visibility: Visibility
}

/** 共享库保留分区 tenantId（对齐后端 PublicKb.TENANT_ID）；用于把 DocumentInfo 归类为 visibility。 */
export const PUBLIC_TENANT_ID = '__public__'
export function docVisibility(info: DocumentInfo): Visibility {
  return info.tenantId === PUBLIC_TENANT_ID ? 'public' : 'tenant'
}
```

### 3.3 `api/admin.ts`（新，**Bearer-only**）

**硬约束**：绝不引用 `sessionStore`/api-key；Authorization 只来自 `authStore.accessToken`；走 `authorizedFetch` 复用 401 单飞续期；写请求带裸版本号 `If-Match`；解析 `X-Total-Count`。

```ts
import { AUTH_BASE_URL } from '../config'
import { useAuthStore } from '../stores/auth'
import { authorizedFetch } from './authorizedFetch'
import { ApiError } from './errors'
import { tryParseJson } from '../utils/json'
import type { /* 所有 admin DTO */ } from '../types/admin'

/** Bearer-only 头；ifMatch 为版本号（PATCH/PUT/updateRole 用）。 */
function adminInit(method: string, body?: unknown, ifMatch?: number, signal?: AbortSignal): RequestInit {
  const auth = useAuthStore()
  const headers: Record<string, string> = {}
  if (auth.accessToken) headers['Authorization'] = `Bearer ${auth.accessToken}` // 唯一凭证来源
  if (ifMatch != null) headers['If-Match'] = String(ifMatch)
  const init: RequestInit = { method, headers, credentials: 'include', signal }
  if (body !== undefined) { headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(body) }
  return init
}
async function adminJson<T>(path: string, init: RequestInit): Promise<{ data: T; res: Response }> {
  const res = await authorizedFetch(`${AUTH_BASE_URL}${path}`, init) // Bearer 触发单飞 refresh
  const text = await res.text().catch(() => '')
  const data = text ? (tryParseJson(text) ?? text) : null
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status}`.trim(), data)
  return { data: data as T, res }
}

// —— 用户 ——
export async function fetchUsers(
  params: { offset: number; limit: number; q?: string; tenant?: string; role?: string; enabled?: boolean },
  signal?: AbortSignal,
): Promise<PagedResult<UserAdminView>> {
  const qs = new URLSearchParams({ offset: String(params.offset), limit: String(params.limit) })
  // 契约依赖：q/tenant/role/enabled 为 FINAL_PLAN §9.2 提议的服务端筛选，当前 listUsers 仅 offset/limit。
  // 后端就绪前，调用方（store）应关掉这些筛选或退化为当前页客户端筛选（见 §7 风险表）。
  if (params.q) qs.set('q', params.q)
  if (params.tenant) qs.set('tenant', params.tenant)
  if (params.role) qs.set('role', params.role)
  if (params.enabled != null) qs.set('enabled', String(params.enabled))
  const { data, res } = await adminJson<UserAdminView[]>(`/auth/admin/users?${qs}`, adminInit('GET', undefined, undefined, signal))
  const total = Number(res.headers.get('X-Total-Count') ?? data.length)
  return { items: data, total: Number.isFinite(total) ? total : data.length }
}
export const fetchUser = (u: string) => adminJson<UserAdminView>(`/auth/admin/users/${encodeURIComponent(u)}`, adminInit('GET')).then((r) => r.data)
export const createUser = (req: CreateUserRequest) => adminJson<UserAdminView>('/auth/admin/users', adminInit('POST', req)).then((r) => r.data)
export const patchUser = (u: string, req: UpdateUserRequest, version: number) =>
  adminJson<UserAdminView>(`/auth/admin/users/${encodeURIComponent(u)}`, adminInit('PATCH', req, version)).then((r) => r.data)
export const replaceUserRoles = (u: string, roles: string[], version: number) =>
  adminJson<UserAdminView>(`/auth/admin/users/${encodeURIComponent(u)}/roles`, adminInit('PUT', { roles }, version)).then((r) => r.data)
export const deleteUser = (u: string) => adminJson<void>(`/auth/admin/users/${encodeURIComponent(u)}`, adminInit('DELETE')).then(() => undefined)

// —— 角色 ——（GET /roles、GET /roles/{name}、POST /roles、PUT /roles/{name}+If-Match、DELETE /roles/{name}）
export const fetchRoles = () => adminJson<RoleView[]>('/auth/admin/roles', adminInit('GET')).then((r) => r.data)
export const fetchRole = (n: string) => adminJson<RoleView>(`/auth/admin/roles/${encodeURIComponent(n)}`, adminInit('GET')).then((r) => r.data)
export const createRole = (req: CreateRoleRequest) => adminJson<RoleView>('/auth/admin/roles', adminInit('POST', req)).then((r) => r.data)
export const updateRole = (n: string, req: UpdateRoleRequest, version: number) =>
  adminJson<RoleView>(`/auth/admin/roles/${encodeURIComponent(n)}`, adminInit('PUT', req, version)).then((r) => r.data)
export const deleteRole = (n: string) => adminJson<void>(`/auth/admin/roles/${encodeURIComponent(n)}`, adminInit('DELETE')).then(() => undefined)
```

> **admin/config 写开关探测**：`GET /auth/admin/config`（写开关/合同版本）**当前不存在**。前端"写是否开启"退化为**反应式**：发起写 → 若 503 `rbac_writes_disabled` 则提示灰度未开并禁用后续写按钮；不预先猜测。待后端补该接口后再改为主动能力协商（`fetchAdminConfig()` 加法引入）。

### 3.4 `api/knowledge.ts`（新，经边缘网关，**双模凭证**）

与 admin 不同：知识端点走 `EDGE_BASE_URL`，接受 api-key **或** Bearer（能力路径），故沿用 `runContext` 的凭证注入（api-key 覆盖 Bearer），走 `authorizedFetch`：

```ts
import type { RunContext } from '../types/api'
import { API_KEY_HEADER, AUTH_HEADER } from './client'
import { authorizedFetch } from './authorizedFetch'
import { ApiError } from './errors'

function credHeaders(ctx: RunContext): Record<string, string> {
  const h: Record<string, string> = {}
  if (ctx.apiKey) h[API_KEY_HEADER] = ctx.apiKey               // 复用既有互斥逻辑
  else if (ctx.accessToken) h[AUTH_HEADER] = `Bearer ${ctx.accessToken}`
  return h
}
async function edgeJson<T>(path: string, ctx: RunContext, init: RequestInit = {}): Promise<T> {
  const res = await authorizedFetch(`${ctx.edgeBaseUrl}${path}`, { ...init, headers: { ...credHeaders(ctx), ...init.headers }, signal: ctx.signal })
  const text = await res.text().catch(() => '')
  const data = text ? JSON.parse(text) : null
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status}`.trim(), data)
  return data as T
}

export const fetchRagConfig = (ctx: RunContext) => edgeJson<KnowledgeRuntimeView>('/rag/config', ctx)
export function listDocuments(visibility: Visibility, ctx: RunContext): Promise<DocumentInfo[]> {
  const q = visibility === 'public' ? '?visibility=public' : ''    // 缺省=当前租户（向后兼容）
  return edgeJson<DocumentInfo[]>(`/rag/documents${q}`, ctx)
}
export function getDocument(docId: string, visibility: Visibility, ctx: RunContext): Promise<DocumentInfo> {
  const q = visibility === 'public' ? '?visibility=public' : ''
  return edgeJson<DocumentInfo>(`/rag/documents/${encodeURIComponent(docId)}${q}`, ctx)
}
export function deleteDocument(docId: string, visibility: Visibility, ctx: RunContext): Promise<void> {
  const q = visibility === 'public' ? '?visibility=public' : ''   // 删共享需 public-ingest（后端 403 兜底）
  return edgeJson<void>(`/rag/documents/${encodeURIComponent(docId)}${q}`, ctx, { method: 'DELETE' }).then(() => undefined)
}
```

> **列表 visibility 归属**：list 是**按 tab 请求**的（tenant 或 public），前端以**请求的 visibility 为权威**标注结果，不靠 `tenantId` 猜（`docVisibility()` 仅作兜底/校验）。query 命中用服务端 `KnowledgeHit.visibility`。

### 3.5 `api/auth.ts` 增量

```ts
export interface AuthPublicConfig { registrationEnabled: boolean; passwordMinLength: number; passwordMaxLength: number }

/** GET /auth/public-config（边缘 open 路径，无需凭证）。失败抛 ApiError（调用方静默）。 */
export async function fetchPublicAuthConfig(): Promise<AuthPublicConfig> {
  const res = await fetch(authUrl('/auth/public-config'), { credentials: 'include' })
  const data = await readJson(res)
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status}`.trim(), data)
  return data as AuthPublicConfig
}
/** POST /auth/register（成功即登录，返回同 login 的 AuthSession）。复用 postAuth。 */
export async function registerRequest(username: string, password: string): Promise<AuthSession> {
  const res = await postAuth('/auth/register', { username, password })
  const data = await readJson(res)
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status} ${res.statusText}`.trim(), data)
  return data as AuthSession
}
```

`AuthUser`/`AuthSession` **不变**（三字段 user）。

### 3.6 `api/errors.ts` 增量（409 家族按 `error` 码区分；credential 感知）

现状：`humanizeError` 对 409 只给"任务/租约"通用文案，会误导 admin。改法（**加法，不破坏既有分支**）：

1. 新增判别码提取器（供 store/视图分流冲突弹窗）：
```ts
export function apiErrorCode(err: unknown): string | null {
  if (err instanceof ApiError && err.body && typeof err.body === 'object') {
    const c = (err.body as Record<string, unknown>).error
    return typeof c === 'string' ? c : null
  }
  return null
}
```
2. `humanizeError(err, cap?, opts?)` 增可选 `opts?: { credentialMode?: CredentialMode }`：
   - **优先展示服务端 `message`**（后端 admin 错误已是中文人话，`extractServerMessage` 已能取 `message`）——对 `version_conflict`/`last_admin`/`role_in_use`/`username_taken`/`role_exists`/`rbac_writes_disabled` 直接透传后端句子，仅在无 message 时给内置兜底。
   - 401：`credentialMode==='api-key'` → 保持"API Key 无效"；`==='bearer'` → "登录已过期，请重新登录"（此时 `authorizedFetch` 已尝试续期失败）。
   - 403：`bearer` 且有 `cap.requiredScopes` → "当前账号缺少 scope（角色未包含），需管理员授予"；`api-key` → 保持既有"更换具备该 scope 的 Key"。
   - 503 `rbac_writes_disabled` → "RBAC 管理写入未开启（灰度），暂不可提交"。
   - **前向兼容**（当前不会触发，但预留）：412 `precondition_failed` / 428 `precondition_required` → 映射为"版本冲突/缺前置版本"，与 409 `version_conflict` 同语义分流到冲突弹窗。

### 3.7 API/类型层单测点

- `api/admin`：`adminInit` 只出 `Authorization: Bearer`（即便 `sessionStore.apiKey` 非空也无 `X-Api-Key`——用 stub 断言 header 键集合）；`fetchUsers` 解析 `X-Total-Count`（缺失时回退 `items.length`）；`patchUser`/`replaceUserRoles`/`updateRole` 发 `If-Match=<version>`；401 经 `authorizedFetch` 触发一次 `refresh` 后重试（mock refresh 返回新 token，断言二次 fetch 带新 Bearer）。
- `api/knowledge`：`credHeaders` api-key 覆盖 Bearer；`listDocuments('public')` 带 `?visibility=public`、`'tenant'` 不带；`deleteDocument` 走 DELETE。
- `api/auth`：`fetchPublicAuthConfig` 解析三字段、失败抛 ApiError；`registerRequest` 成功返回 AuthSession、409 `username_taken` 抛错。
- `api/errors`：`apiErrorCode` 提取 `body.error`（非对象体返回 null）；`humanizeError` 对 `version_conflict` 透传后端 message；401 在 bearer/api-key 两种 `credentialMode` 文案不同；503 `rbac_writes_disabled` 文案。

---

## 4. Composables

### 4.1 `composables/usePermission.ts`（scope/credential 裁决 + 缺权原因）

单一裁决点，供 gate、管理入口可见性、能力缺权说明共用，避免"通用 runner 与专用页面裁决漂移"。

```ts
export type PermissionReason = 'ok' | 'need-login' | 'missing-scope' | 'unknown-apikey'
export interface PermissionVerdict {
  allowed: boolean
  reason: PermissionReason
  missingScopes: string[]   // missing-scope 时非空
  message: string           // 直接可渲染的人话
}

export function usePermission() {
  const auth = useAuthStore()
  const session = useSessionStore()

  /** 对一组 requiredScopes 裁决（Bearer 预判、api-key unknown、无凭证 need-login）。 */
  function evaluate(requiredScopes: string[] = []): PermissionVerdict {
    const mode = session.credentialMode
    if (mode === 'none') return { allowed: false, reason: 'need-login', missingScopes: requiredScopes, message: '请先登录，或在顶栏「高级」填写 API Key。' }
    if (mode === 'api-key') return { allowed: true, reason: 'unknown-apikey', missingScopes: [], message: 'API Key 权限不透明，将由后端反应式鉴权（可能 403）。' }
    // bearer：可精确预判
    const missing = requiredScopes.filter((s) => !auth.hasScope(s))
    return missing.length
      ? { allowed: false, reason: 'missing-scope', missingScopes: missing, message: `当前账号缺少 scope：${missing.join(' / ')}（由角色授予）。` }
      : { allowed: true, reason: 'ok', missingScopes: [], message: '' }
  }

  const credentialMode = computed(() => session.credentialMode)
  /** 管理入口可见性：Bearer role-admin（api-key 不参与，见 §1.3）。 */
  const canAdmin = computed(() => auth.isAdmin && RBAC_CONSOLE_ENABLED)
  return { evaluate, hasScope: auth.hasScope, credentialMode, canAdmin, apiKeyOverridesBearer: computed(() => session.apiKeyOverridesBearer) }
}
```

`utils/gate.ts` 扩展与之衔接（`GateContext` 增 `credentialMode?`、`effectiveScopes?`；`scope-required` 分支：bearer 缺 scope→`allowed:false`+精确 reason，命中→放行，api-key→`allowed:true`+unknown hint；**不传新字段时回落既有 hint 逻辑，保 `gate.test.ts` 绿**）。各 `executionGate(...)` 调用点（chat/agent/analytics/workflow/tasks/channel/interop/RAG + `useCapabilityRun`）统一改传 `session.permissionContext()`。

### 4.2 `composables/usePagedQuery.ts`（debounce / abort / 乱序保护）

通用分页查询，封装三件事：筛选防抖、请求中止、**乱序响应保护**（单调序号只认最新）。被 `adminUsers` store 复用，也可被 RAG 文档列表等视图直接用。

```ts
export interface PagedQueryOptions<T> {
  fetcher: (p: { offset: number; limit: number; filters: Record<string, string>; signal: AbortSignal }) => Promise<{ items: T[]; total: number }>
  pageSize?: number     // 默认 50
  debounceMs?: number   // 默认 300（筛选输入）
}
export function usePagedQuery<T>(opts: PagedQueryOptions<T>) {
  const pageSize = ref(opts.pageSize ?? 50)
  const items = ref<T[]>([]) as Ref<T[]>
  const total = ref(0)
  const offset = ref(0)
  const filters = reactive<Record<string, string>>({})
  const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const error = ref<string | null>(null)

  let seq = 0                       // 单调序号：只接受最新一次
  let controller: AbortController | null = null
  let debTimer: ReturnType<typeof setTimeout> | null = null

  async function load(): Promise<void> {
    const my = ++seq
    controller?.abort(); controller = new AbortController()
    status.value = 'loading'; error.value = null
    try {
      const r = await opts.fetcher({ offset: offset.value, limit: pageSize.value, filters: { ...filters }, signal: controller.signal })
      if (my !== seq) return        // 已被更新的请求取代 → 丢弃陈旧结果
      items.value = r.items; total.value = r.total; status.value = 'ready'
    } catch (e) {
      if (my !== seq || isAbortError(e)) return
      error.value = humanizeError(e); status.value = 'error'
    }
  }
  function scheduleLoad(): void { if (debTimer) clearTimeout(debTimer); debTimer = setTimeout(load, opts.debounceMs ?? 300) }
  function setFilter(key: string, value: string): void { if (value) filters[key] = value; else delete filters[key]; offset.value = 0; scheduleLoad() }
  function nextPage(): void { if (hasNext.value) { offset.value += pageSize.value; void load() } }
  function prevPage(): void { if (hasPrev.value) { offset.value = Math.max(0, offset.value - pageSize.value); void load() } }
  function reload(): void { void load() }
  // 局部失效辅助（供 store）
  function patchItem(pred: (t: T) => boolean, next: T): void { const i = items.value.findIndex(pred); if (i >= 0) items.value.splice(i, 1, next) }
  function removeItem(pred: (t: T) => boolean): void { const i = items.value.findIndex(pred); if (i >= 0) items.value.splice(i, 1) }

  const hasNext = computed(() => offset.value + pageSize.value < total.value)
  const hasPrev = computed(() => offset.value > 0)
  onScopeDispose(() => { controller?.abort(); if (debTimer) clearTimeout(debTimer) })
  return { items, total, offset, pageSize, filters, status, error, load, reload, setFilter, nextPage, prevPage, hasNext, hasPrev, patchItem, removeItem }
}
```

### 4.3 Composable 单测点

- `usePagedQuery`（fake timers + 可控 promise）：
  - **乱序保护**：先发 A（慢）、后发 B（快），B 先 resolve、A 后 resolve → `items` 停留在 B 的结果（断言 A 被丢弃）。
  - **中止**：连续两次 `load`，第一次的 `signal.aborted===true`。
  - **防抖**：连续 3 次 `setFilter` 只触发 1 次 `fetcher`（推进计时器后）。
  - `total`/`hasNext`/`hasPrev` 边界；`patchItem`/`removeItem` 就地改。
  - `onScopeDispose` 后不再 setState（卸载安全）。
- `usePermission`：`evaluate` 三模式矩阵（none/api-key/bearer×命中/缺失）；`canAdmin` 受 `isAdmin` 与 `RBAC_CONSOLE_ENABLED` 双控。

---

## 5. 组件树

### 5.1 admin 域视图（`src/modules/admin/`）

| 组件 | props / emit | 职责 | 复用既有 |
|---|---|---|---|
| `AdminLayout.vue` | 无 | 管理域壳：顶部标题 + 子导航（用户/角色 `RouterLink`）+ `<RouterView/>`；防御性 `v-if="auth.isAdmin"`（守卫之外的兜底），否则内联 `ForbiddenView`。**不含 api-key 入口**。 | `InfoNote`（灰度/只读提示）、设计 token |
| `UsersView.vue` | 无 | 用户列表：`StatCard` 显示总数；筛选输入（username/tenant/role/enabled，`store.setFilter` 300ms 防抖）；分页（`prevPage/nextPage`+`X-Total-Count`）；行含 username/tenant/roles/effectiveScopes 摘要/enabled/version + `CopyButton`(userId)；行点击 → `admin-user`。空/错误态。**新建用户**入口（打开 `UserEditor` 空态 或跳 `admin-user` 的 new 语义）。 | `EmptyState`、`StatCard`、`InfoNote`、`CopyButton`、`ScopeBadge`；自绘表格（需行内操作，语义参照 `ResultTable`） |
| `UserEditor.vue` | `{ username: string }`（路由 param；`new` 表新建） | 详情/编辑闭环：`store.loadDetail`；本地草稿（tenant/enabled/password/directScopes 只读展示 + roles 可编辑）；保存拆两步（`saveUserPatch` + `saveUserRoles`，各带 version）；启停/删除经 `DangerConfirmDialog`；`version_conflict` → 打开 `VersionConflictDialog`（草稿 vs `store.selected`）。密码字段**只入不出**、留空=不改。 | `RolePicker`、`ScopePicker`(readonly 展示 directScopes)、`VersionConflictDialog`、`DangerConfirmDialog`、`InfoNote`、`EmptyState`、`CopyButton` |
| `RolesView.vue` | 无 | 角色列表：`store.load`；每行 name/scopes 概要/`assignedUserCount`/version；`assignedUserCount>0` 高亮"在用"；行点 → `admin-role`；新建角色入口。 | `EmptyState`、`StatCard`、`InfoNote`、`ScopeBadge` |
| `RoleEditor.vue` | `{ name: string }` | 角色 scope 编辑闭环：`ScopePicker`（按 `scopeCatalog` 分组，未知 scope 保留）；description；编辑前显示 `assignedUserCount`（影响预览）；`saveRole` 带 version；删除经 `DangerConfirmDialog`，`role_in_use`(409) → 提示并链接到"该角色绑定用户"（跳 `admin-users?role=<name>`）。 | `ScopePicker`、`VersionConflictDialog`、`DangerConfirmDialog`、`InfoNote`、`EmptyState` |
| `ForbiddenView.vue` | 无 | 权限不足/会话变化落点：说明缺 `role-admin` 或会话已变；「回首页」「重新登录」。 | `EmptyState`(variant=error)、`RouterLink` |

### 5.2 `src/components/admin/`（无障碍选择器与对话框）

| 组件 | props | emit | 契约 | 复用 |
|---|---|---|---|---|
| `ScopePicker.vue` | `{ modelValue: string[]; readonly?: boolean }` | `update:modelValue` | 按 `config/scopeCatalog.ts` 分组渲染复选（`fieldset/legend` 语义）；**未知 scope** 落"其它/未知"组并**原样保留**回写；`readonly` 时禁用输入、仅展示（用于 directScopes 只读）。 | `scopeCatalog`、`ScopeBadge`、token |
| `RolePicker.vue` | `{ modelValue: string[]; options: string[] }` | `update:modelValue` | 多选角色名（复选/标签）；hover 显示角色 description（从 `adminRoles` 取）；**未知角色**（不在 options）保留展示。 | `adminRoles.roleNames`、token |
| `VersionConflictDialog.vue` | `{ open: boolean; draft: Record<string,unknown>; current: Record<string,unknown>; fields: { key: string; label: string }[] }` | `reload`（放弃草稿、采服务端最新）、`close` | 逐字段展示 draft vs current 差异；**不提供"无脑覆盖"**（业务规则）；`role="dialog" aria-modal`。 | `useFocusTrap`、`InfoNote`、token |
| `DangerConfirmDialog.vue` | `{ open: boolean; title: string; message: string; confirmLabel?: string; requireText?: string }` | `confirm`、`cancel` | 删除/禁用/共享写二次确认；`requireText` 非空时需输入匹配文本才可确认（高危抬门槛）；焦点陷阱 + Esc 取消。 | `useFocusTrap`、token（复用 RagWorkspaceView 现有"确认删除"心智，抽成可复用件） |

### 5.3 顶栏/侧栏/命令面板/RAG/登录改造（既有文件）

| 文件 | 改动要点 | permission source |
|---|---|---|
| `components/layout/AuthControl.vue` | 身份 chip 增 tenant + `credentialMode`；`apiKeyOverridesBearer` 时高对比警告"能力请求将用 API Key，账号权限预判暂停"+清除按钮；`useRoute()` 检测 admin 路由（`meta.requiredScopes`）时**隐藏 api-key 入口**。 | `session.credentialMode` / `apiKeyOverridesBearer` |
| `components/layout/AppHeader.vue` | 增"管理中心"入口（`v-if="usePermission().canAdmin"`）；身份信息响应式收纳进 ⋯。 | `usePermission().canAdmin` |
| `components/layout/SideNav.vue` | 顶部增"管理中心"分区（用户/角色），`v-if` 同 `canAdmin`；能力/管理**同一 permission source**；缺权能力仍可发现（保持既有诚实呈现）。 | `usePermission().canAdmin` |
| `components/common/CommandPalette.vue` | 收录管理入口动作，受同一 `canAdmin` 裁决，避免旁路进入。 | `usePermission().canAdmin` |
| `components/capability/CapabilityRunner.vue` / `composables/useCapabilityRun.ts` | 传 `session.permissionContext()`（credentialMode + effectiveScopes）给 `executionGate`；缺权说明精确。 | `session.permissionContext()` |
| `modules/rag/RagWorkspaceView.vue` | "当前租户库 / 共享知识库" tabs（`SHARED_KB_UI_ENABLED` + `ragConfig.publicEnabled` 双控，关时只留租户 tab + 说明）；文档列表改用 `api/knowledge.listDocuments(visibility)` + `types/knowledge.DocumentInfo`（展示 visibility/uploadedAt/version/segmentCount/sizeBytes/category）；共享上传显式勾选 visibility + `DangerConfirmDialog` 确认全租户可检索、共享图片禁用（`sharedImagesSupported=false`）；query 命中用服务端 `hit.visibility` 打"租户/共享"badge。 | `session.runContext()`（双模） |
| `modules/auth/LoginView.vue` | **移除硬编码 `DEMO_PASSWORD='demo12345'` 自动提交**；demo 卡仅在 `DEMO_LOGIN_ENABLED` 为真时渲染，且**不内联口令**（只预填用户名，密码由用户输入 / 或非生产注入）；显示"去注册"入口（`registrationEnabled===true` 时）。 | `config.DEMO_LOGIN_ENABLED` / `auth.publicConfig` |

### 5.4 `modules/auth/RegisterView.vue`（新）

props 无。字段 username/password(+确认)；密码规则来自 `auth.publicConfig.passwordMin/MaxLength`；**无 tenant/role 选择**（业务规则 9）；提交 `auth.register` → `router.replace('/')`；`registrationEnabled=false` 时（守卫外的兜底）fail-closed 提示。复用 `LoginView` 的视觉体系（共享其 `.lp-*` 样式或抽出公共登录样式），保持一致高级感。

### 5.5 `config/scopeCatalog.ts`（新，人话说明字典）

```ts
export interface ScopeGroupDef { id: string; label: string; scopes: { scope: string; label: string; desc: string }[] }
export const SCOPE_GROUPS: ScopeGroupDef[] = [
  { id: 'conversation', label: '对话', scopes: [{ scope: 'chat', label: '对话', desc: '调用 /chat 等对话能力' }] },
  { id: 'knowledge', label: '知识', scopes: [
    { scope: 'ingest', label: '入库', desc: '写当前租户知识库' },
    { scope: 'public-ingest', label: '共享库写', desc: '写/删共享（公共）知识库' }] },
  { id: 'agent', label: '智能体', scopes: [{ scope: 'agent', label: '智能体', desc: 'ReAct / DAG 编排' }] },
  { id: 'approval', label: '审批', scopes: [{ scope: 'approve', label: '审批', desc: '退款审批工作流' }] },
  { id: 'analytics', label: '分析', scopes: [{ scope: 'analytics', label: '数据分析', desc: 'NL2SQL 等' }] },
  { id: 'channel', label: '通道', scopes: [{ scope: 'channel', label: '通道', desc: '出站投递/回调' }] },
  { id: 'multimodal', label: '多模态', scopes: [
    { scope: 'vision', label: '视觉', desc: '图像描述' }, { scope: 'voice', label: '语音', desc: '语音闭环' }] },
  { id: 'platform', label: '平台管理', scopes: [
    { scope: 'role-admin', label: '平台管理', desc: '管账号/角色（本控制台）' },
    { scope: 'eval', label: '评测', desc: '回归评测客户端' }] },
]
/** 已知 scope → 说明；未知返回 null（调用方仍保留原值）。 */
export function describeScope(scope: string): { label: string; group: string; desc: string } | null { /* 索引查找 */ }
/** 把任意 scope 列表按组归拢，未知 scope 落"其它"组且标 known=false（不丢弃）。 */
export function groupScopes(scopes: string[]): { group: string; label: string; items: { scope: string; known: boolean }[] }[] { /* ... */ }
```
（scope 取值以 §0.2 `SeedRoles` 为准；未知值一律保留展示。）

### 5.6 `src/config.ts` 增量（三个 kill switch）

```ts
/** RBAC 管理控制台构建开关。false 时不注册 admin 路由、不显示管理入口（回滚/灰度）。 */
export const RBAC_CONSOLE_ENABLED: boolean = env.VITE_RBAC_CONSOLE_ENABLED === 'true'
/** 共享知识库 UI 开关。仍需运行时 /rag/config.publicEnabled 才真正可用。 */
export const SHARED_KB_UI_ENABLED: boolean = env.VITE_SHARED_KB_UI_ENABLED === 'true'
/** demo 一键登录开关。生产默认 false；即便开启也不内联口令。 */
export const DEMO_LOGIN_ENABLED: boolean = env.VITE_DEMO_LOGIN_ENABLED === 'true'
```
（默认 `=== 'true'` 即**默认关**，与安全默认一致；`.env.example`、`Dockerfile` build args 同步文档化，生产 demo 恒 false。）

---

## 6. 文件级实现步骤（按依赖排序）+ 测试计划

### 6.1 实施步骤（叶子 → 根，每步自带同目录 `*.test.ts`）

1. **`src/config.ts`**：加 `RBAC_CONSOLE_ENABLED`/`SHARED_KB_UI_ENABLED`/`DEMO_LOGIN_ENABLED`（纯常量，无依赖）。
2. **`src/types/admin.ts`、`src/types/knowledge.ts`**：纯类型（无运行时依赖）。
3. **`src/config/scopeCatalog.ts`**：纯数据 + `describeScope`/`groupScopes`。
4. **`src/api/auth.ts`** 增 `fetchPublicAuthConfig`/`registerRequest`/`AuthPublicConfig`。
5. **`src/stores/auth.ts`** 增 `publicConfig`/`hasScope`/`isAdmin`/`register`/`loadPublicConfig`。
6. **`src/stores/session.ts`** 增 `credentialMode`/`apiKeyOverridesBearer`/`permissionContext`。
7. **`src/utils/gate.ts`** 扩 `GateContext`（credentialMode/effectiveScopes）；**`src/api/errors.ts`** 增 `apiErrorCode` + credential 感知 humanize。
8. **`src/composables/usePermission.ts`、`src/composables/usePagedQuery.ts`**。
9. **`src/api/admin.ts`**（Bearer-only）、**`src/api/knowledge.ts`**（双模）。
10. **`src/stores/adminUsers.ts`、`src/stores/adminRoles.ts`**（依赖 8、9）。
11. **`src/main.ts`**：bootstrap 追加 `loadPublicConfig`（非阻塞）。
12. **`src/router/index.ts`**：RouteMeta 增强 + 新路由（懒加载、`RBAC_CONSOLE_ENABLED` 条件注册）+ `resolveRouteAccess` + `beforeEach` 改写。
13. **`src/App.vue`**：`needsCatalog`/`bypassCatalog` 解耦 catalog 门禁。
14. **`src/components/admin/`**：`ScopePicker`/`RolePicker`/`VersionConflictDialog`/`DangerConfirmDialog`（依赖 3、`useFocusTrap`）。
15. **`src/modules/auth/RegisterView.vue`**（依赖 5）。
16. **`src/modules/admin/`**：`AdminLayout`/`UsersView`/`UserEditor`/`RolesView`/`RoleEditor`/`ForbiddenView`（依赖 10、14）。
17. **顶栏/侧栏/命令面板/CapabilityRunner/useCapabilityRun** 接入 `usePermission`/`permissionContext`（依赖 6、8）。
18. **`src/modules/rag/RagWorkspaceView.vue`** 双 visibility 化（依赖 9、`types/knowledge`、`SHARED_KB_UI_ENABLED`）。
19. **`src/modules/auth/LoginView.vue`**：移除内联 demo 口令，接 `DEMO_LOGIN_ENABLED` + 注册入口。
20. **`.env.example`/`Dockerfile`/`vite.config.ts`/`nginx.conf`**：文档化三开关、确认 `/rag/config` 走业务前缀、admin 深链 history 回退（多为部署侧，不改前端逻辑）。

> 依赖闭环校验：路由（12）依赖 stores（5/6/10）与 config（1）；视图（16/18）依赖 stores + components（14）+ api（9）；集成（17）依赖 composables（8）。步骤 1–11 全为可独立单测的纯/低耦合单元，先落地并跑绿，再进 12+ 的装配层。

### 6.2 测试计划（Vitest，同目录；关键组件用 `@vue/test-utils` mount）

**纯函数/守卫**
- `router/guard.test.ts`：`resolveRouteAccess` 矩阵（§1.7 九点）+ `resolveAuthNavigation` 既有 8 例回归。
- `utils/gate.test.ts`：追加 bearer 缺 scope→禁用、bearer 命中→放行、api-key scope-required→unknown hint；既有 6 例回归（不传新字段仍绿）。
- `config/scopeCatalog`：`describeScope` 命中/未知(null)；`groupScopes` 未知落"其它"且 `known=false`（不丢）。

**store**（`setActivePinia(createPinia())`）
- `stores/auth.test.ts` 追加：§2.6。
- `stores/session.test.ts`（新）：§2.6。
- `stores/adminUsers.test.ts`（新）：§2.6（mock `api/admin`）。
- `stores/adminRoles.test.ts`（新）：§2.6。

**api**（stub `fetch`/`authorizedFetch`）
- `api/admin.test.ts`：§3.7（重点断言 **Bearer-only、无 X-Api-Key**、If-Match、X-Total-Count、401 单飞重试）。
- `api/knowledge.test.ts`：§3.7。
- `api/auth.test.ts`（新或追加）：`fetchPublicAuthConfig`/`registerRequest`。
- `api/errors.test.ts` 追加：`apiErrorCode`、409 家族透传、401 credential 分文案、503。

**composable**（fake timers）
- `composables/usePagedQuery.test.ts`：乱序/中止/防抖/分页/patch/remove/卸载安全。
- `composables/usePermission.test.ts`：三模式矩阵、`canAdmin`。

**组件**（mount）
- `components/admin/ScopePicker.test.ts`：未知 scope 保留在 emit 值；readonly 禁用；分组渲染。
- `components/admin/RolePicker.test.ts`：toggle emit；未知角色保留。
- `components/admin/VersionConflictDialog.test.ts`：渲染字段差异；`reload` emit；无"覆盖"按钮。
- `components/admin/DangerConfirmDialog.test.ts`：`requireText` 匹配才允 confirm；Esc→cancel。
- `modules/admin/UsersView.test.ts`：从 store 渲染行；筛选输入防抖调 `setFilter`；空/错误态。
- `modules/admin/UserEditor.test.ts`：directScopes 只读；保存调 store；`version_conflict`→开冲突弹窗；空密码不发送 password 字段。
- `modules/admin/RoleEditor.test.ts`：`role_in_use` 删除被拦并给引导链接。
- `modules/auth/RegisterView.test.ts`：无 tenant/role 字段；密码长度按 publicConfig 校验；提交调 `auth.register`。
- `modules/auth/LoginView.test.ts` 追加：`DEMO_LOGIN_ENABLED=false` 时不渲染 demo 卡、组件源不含明文口令；注册入口随 `registrationEnabled` 显隐。
- `components/layout/AuthControl.test.ts`（新/追加）：显示 tenant + credentialMode；`apiKeyOverridesBearer` 警告；admin 路由隐藏 api-key 入口。

**构建期防泄露**（CI grep / 简单断言）
- 生产 `dist/` 产物不含字符串 `demo12345`（或任何内联口令）。

**执行命令**（对齐 FINAL_PLAN §11）：`npm test` / `npm run type-check` / `npm run build`。

---

## 7. 风险与坑（含缓解）

| # | 风险 | 缓解 |
|---|---|---|
| 1 | **Pinia 反应性丢失**：从 store 解构基本类型（`const { credentialMode } = useSessionStore()`）会断开响应。 | 视图用 `storeToRefs` 或 `store.x`；getter 内**延迟** `useAuthStore()`（沿用 `session.ts` 范式）；`hasScope` 依赖 `scopeSet` computed，`user` 必须**整体替换**（`setSession`/`refresh` 已如此），禁止原地 mutate；`usePagedQuery` 返回的 `Ref<T[]>` 在 store 中透传时注意解包类型。 |
| 2 | **single-flight refresh × 401**：管理/知识请求都经 `authorizedFetch`。 | admin 必须以 `Authorization: Bearer` 触发（`authorizedFetch` 只对 Bearer 续期）；api-key 的 401 不续期；并发 admin 401 只刷一次、各自带新 token 重试。**注意**：写请求 refresh 后重试会**重发 If-Match**——若两次间资源被改，重试得 409 `version_conflict`，属期望，**不得吞掉**（交冲突弹窗）。 |
| 3 | **SPA 深链回退**：新增 `/admin/**`、`/register`、`/forbidden` 静态托管需 history fallback。 | 确认 `nginx.conf` `try_files ... /index.html` 覆盖 admin 子路由；`vite base` 与 `createWebHistory(BASE_URL)` 一致；保留既有通配 `/:pathMatch(.*)*`→`/`，**不**让 forbidden 变 catch-all 吞未知路由。 |
| 4 | **生产包内联 demo 口令**：现 `LoginView` 硬编码 `demo12345` 自动提交。 | 移除内联口令与自动提交；demo 卡仅 `DEMO_LOGIN_ENABLED` 渲染且不内联口令（仅预填用户名）；加构建产物 grep 断言无 `demo12345`；`Dockerfile` 生产 demo build arg 恒 false。 |
| 5 | **凭证落盘**：新 store 误持久化身份。 | `adminUsers`/`adminRoles`/`auth`/`session` **一律仅内存**；`SideNav` 已持久化的仅"折叠态"（非敏感），admin 分组折叠态可同样持久化但**不含身份**。 |
| 6 | **API Key 身份混淆**：填 key 后冒出管理菜单/管理请求带 key。 | 管理域 Bearer-only（§3.3）；入口可见性只看 `auth.isAdmin`（§1.3）；顶栏 `credentialMode` chip 高对比标示 api-key 覆盖；admin 路由隐藏 api-key 入口；守卫不读 session。 |
| 7 | **契约漂移（与 FINAL_PLAN §9.2 分歧）**：当前 = 409 `version_conflict`、删除无 If-Match、`listUsers` 仅 offset/limit、无 `/auth/admin/config`。 | 前端按**当前 409 判别码**实现（`apiErrorCode` 分流）；写开关探测退化为反应式 503；服务端筛选未就绪时**隐藏/退化**筛选 UI 而非臆造 query；对 412/428 预留加法映射，后端升级后无缝切换；用 `/rag/config.contractVersion`（及未来 admin/config）做能力协商。 |
| 8 | **scope 预判越权错觉**：Bearer 预判仅改善体验。 | 预判缺 scope→禁用+说明，但直连 HTTP 仍以后端 403 为准；api-key 保持 unknown 反应式，**不臆造** scope；`role-admin` 精确匹配（无通配，§0.2）。 |
| 9 | **乱序/卸载竞态**：分页、详情、RAG 文档列表快速切换。 | 统一 `usePagedQuery`/`useAbortable`+`seq`（最新赢 + 中止在途）；`onScopeDispose` 卸载中止，杜绝 setState-after-unmount。 |
| 10 | **无障碍缺口**：新弹窗/选择器键盘不可达。 | 复用 `useFocusTrap`；对话框 `role="dialog" aria-modal` + Esc；`ScopePicker` 用 `fieldset/legend`；表格沿用 `ResultTable` 的 AA 语义（`scope="col"`、`role="region"` 可滚）；`prefers-reduced-motion` 守卫动画。 |
| 11 | **catalog 失败拖垮管理页**：现 App.vue 全局门禁。 | `bypassCatalog` 路由跳过 catalog 门禁（§1.4）；admin 懒加载不进首屏 bundle。 |
| 12 | **共享库能力误宣称**：graph 未并公共分区；共享图片不支持。 | RAG UI 以 `/rag/config`（`publicEnabled`/`sharedImagesSupported`）与 `SHARED_KB_UI_ENABLED` 双控；共享图片入口禁用并说明；GraphRAG 保持既有 flag-off 诚实锁定。 |

### 假设与待澄清（缺失需求不臆造）

- **[待澄清·业务规则13]** 普通登录用户能否列共享文档元数据？默认按 `/rag/config.publicEnabled` 显示只读元数据；若后端限制，则共享 tab 对非授权用户隐藏。**影响 RAG 共享 tab 可见性**，实施前确认。
- **[待澄清·契约]** `GET /auth/admin/users` 服务端筛选（q/tenant/role/enabled）是否落地？未落地则筛选 UI 退化为当前页客户端筛选或隐藏（`api/admin.fetchUsers` 已预留 query，但 store 需据能力开关决定是否启用）。
- **[待澄清·契约]** `GET /auth/admin/config`（写开关/合同版本）不存在；当前用 503 反应式探测写开关。后端补齐后改主动协商。
- **[假设]** `effectiveScopes` 无通配符（经 `SeedRoles`/`RoleService` 核对成立）；若未来引入 `*`，需扩展 `hasScope`（当前精确匹配）。
- **[假设]** 版本冲突沿用 409 `version_conflict`（当前实现），非 412/428；若后端按 FINAL_PLAN 升级，`humanizeError`/冲突分流已预留加法映射。

---

## 8. 与既有代码风格对齐清单（实施自检）

- [ ] 新文件落对应 `src/{api,stores,composables,components,modules,router,utils,config,types}` 目录；`*.test.ts` 同目录。
- [ ] Store 用 setup 写法；getter 内延迟 `useAuthStore()`；**不解构**、**不持久化**身份。
- [ ] 凭证仅内存；admin Bearer-only、knowledge 双模；business header 不覆盖平台凭证头。
- [ ] 全用 `tokens.css` 变量；中文注释/Javadoc 风格；`prefers-reduced-motion` 守卫。
- [ ] 复用 `EmptyState/InfoNote/WorkbenchSection/StatCard/CopyButton/ScopeBadge/ResultTable/useFocusTrap/useAbortable`，不引第三方 UI 库。
- [ ] 纯函数（守卫/gate/scopeCatalog/usePagedQuery 核心）优先，配套单测；`npm test`/`type-check`/`build` 全绿。
- [ ] 生产包无 demo 口令；三个 kill switch 默认关；深链 history 回退可用。
