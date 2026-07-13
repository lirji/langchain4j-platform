# RBAC 前端控制台测试方案与验收标准

> 视角：test-designer。测试覆盖四个候选方案的差异，并以最终组合方案为主。所有文件名标为“计划新增”的均不是当前仓库既有内容。

## 1. 测试原则

- 权限测试必须同时验证 UI 状态和真实 HTTP 结果；不能只断言菜单隐藏。
- tenant 私有数据不可见是负向安全属性，必须用至少两个真实 tenant 验证。
- 并发、幂等、事务和迁移优先在后端自动化测试；前端测试负责冲突交互和不重放策略。
- 所有 fetch mock 都断言 Authorization/X-Api-Key 选择，防止管理域误用 API Key。
- 不在快照里保存 token/password/cookie；日志和失败截图必须脱敏。

## 2. 候选方案测试差异

### A

- 重点：现有 API DTO 解析、全量列表、本地筛选、scope 显隐和 visibility 上传。
- 明确不通过的架构验收：跨管理员并发覆盖、共享列表/删除、查询来源 badge、万级用户分页。
- 只可作为 demo release gate，不能满足最终生产验收。

### B

- 完整执行本文件所有测试：分页、ETag冲突、引用保护、动态 config、共享管理、visibility、灰度。
- 最终验收以 B 为主。

### C

- 在 B 基础上增加两 SPA cookie path/domain、跨站 CORS、两应用同时 bootstrap/refresh、独立部署回滚测试。
- 用两个 browser context/tab 模拟 refresh token 轮转竞争。

### D

- 在 B 基础上增加 CSRF、server session fixation、session store 故障、多实例 refresh single-flight、BFF下游超时/熔断和 actor 委托测试。
- 必须证明 BFF不使用高权限服务身份替代用户授权。

## 3. 前端单元测试

### 3.1 permission/gate

计划扩充 `src/utils/gate.test.ts` 或新增 `usePermission.test.ts`：

- Bearer + scopes满足 requiredScopes -> allowed。
- Bearer 缺任一 required scope -> disabled，列出精确缺项。
- API Key mode -> permission unknown，不做错误前置禁用。
- 无凭证 -> disabled。
- `role-admin` 路由：Bearer admin允许、Bearer普通用户 Forbidden、API Key mode不允许管理。
- feature flag off 优先于 scope满足；危险二次确认优先级保持。
- scope 空数组能力不被误拦。

### 3.2 auth store/API

扩充 `src/stores/auth.test.ts`、`src/api/authorizedFetch.test.ts`：

- 登录、refresh、logout 均不写 storage。
- public-config 超时/500时注册入口 fail-closed，但登录仍可用。
- 并发 401 只 refresh一次；每个原请求最多重试一次。
- 403 不触发 refresh。
- 登录用户 scopes 刷新后替换而非并集，确保降权可反映。
- 管理请求只带 Bearer，即使 session store 有 API Key。
- 登出/refresh cookie 请求始终 `credentials=include`。

### 3.3 admin API/stores

计划新增 `api/admin.test.ts`、`stores/adminUsers.test.ts`、`adminRoles.test.ts`：

- 查询参数编码、AbortController 取消、防抖后只发最后请求。
- `X-Total-Count`/ETag/version 解析；缺 header 的兼容降级。
- PATCH 不发送未修改字段；空 password 不进入 JSON。
- 业务409进入业务冲突状态；版本陈旧412进入version conflict状态，不覆盖本地草稿、不自动重试。
- 同名 create 409 后 GET 对账；含密码请求不会自动重放。
- 保存成功只更新匹配对象并失效相关查询。
- roles 请求失败时不把空数组当成功数据。

### 3.4 knowledge API/解析

- `DocumentInfo` 全字段解析并使用 `docId+visibility` 作为 key。
- visibility 只接受 server contract 值；未知值显示 unknown，不猜租户。
- 共享图片被前端阻止；租户图片仍沿用现有能力。
- 上传 JSON/multipart 的 visibility 放置与后端签名一致。
- 查询 hit badge 完全来自响应 visibility。

## 4. Vue 组件测试

使用现有 Vitest + Vue Test Utils：

- `LoginView`：失败消息、busy、深链；生产配置不显示一键 demo 密码。
- `RegisterView`：关闭时不可达；两次密码不一致不请求；成功直接建立会话；409/429/密码策略错误可读。
- `AuthControl`：username、tenant、credential mode；API Key override 显著提示并可一键清除。
- `SideNav/CommandPalette`：管理入口只对 role-admin Bearer显示；能力缺权仍可发现但不可执行。
- `UsersView`：loading/skeleton/empty/error/分页/筛选/键盘；enabled 与 tenant 可读。
- `UserEditor`：创建、编辑、密码不回显、角色加载失败禁提交、自改权限确认。
- `RolesView/RoleEditor`：未知 scopes 保留、绑定数、删除在用角色错误、影响预览。
- `VersionConflictDialog`：展示 server/local差异，刷新后焦点正确。
- `RagWorkspaceView`：tenant/public tabs、共享确认、public-ingest gating、visibility badge、public feature off。
- `ForbiddenView`：直接深链无权限无循环导航，并提供回首页/重新登录。

可访问性断言：label/aria、dialog focus trap、Esc关闭非强制弹窗、Tab顺序、状态非纯颜色表达。

## 5. 后端单元与集成测试

### 5.1 auth-service

先修复当前构造器签名不一致，再扩展：

- `AuthServiceTest`：RBAC off direct-only；on展开；refresh读取最新角色；禁用账号 refresh失败。
- `AdminServiceTest`（计划新增）：所有写都在 mutation executor；password policy统一；unknown role、reserved tenant、last admin保护。
- `AdminControllerTest`：无认证由 edge 401（网关集成）、无 role-admin 403；writes disabled GET可读、mutation明确503；安全 DTO 无 hash/token。
- 版本测试：同version仅一个并发写成功，另一个固定返回412 `precondition_failed`；version递增一次。
- 角色删除/绑定竞态：不能产生孤儿 USER_ROLE；被引用删除409。
- 用户禁用/删权/删除、角色移除 scope后 refresh sessions按规则撤销。
- public-config 不暴露 registration rules、default tenant 或密钥。

### 5.2 JDBC迁移/事务

扩展 `JdbcRbacMigrationTest`：

- main baseline 无 ROLES/VERSION -> 加法升级，旧用户 direct scopes不变，VERSION=0。
- 早期 CSV -> USER_ROLE/ROLE_SCOPE 幂等回填；重复启动计数不增长。
- 当前关系表 -> 只加 VERSION，不丢角色/描述/会话。
- DDL非重复列错误 fail-fast。
- 更新用户资料后关系替换失败 -> 资料/version/关系全部回滚。
- role scope替换中途失败 -> 旧 scope与version保持。
- 两实例并发 seed/migrate（H2能覆盖基本竞态；MySQL staging再验证）。

### 5.3 knowledge-service

- `DocumentControllerPublicTest`：public list/get策略（待业务确认）、public delete要求 public-ingest、普通 ingest不能删共享。
- `DocumentService`：tenant/public overload指向正确 registry/store；同名不同visibility不碰撞。
- `PublicKbQueryTest`：tenant A/B都能命中 public；B不能命中A私有；public off不命中；category仍生效。
- visibility贯穿 vector/keyword/ES融合；同 chunk hybrid后不丢 visibility。
- public图谱当前不并入的行为要有明确测试和文档，避免前端宣称全源共享。
- 共享图片返回稳定错误码/结构，不只依赖 `X-Error` 文本（若合同加固）。

### 5.4 edge-gateway

- `/auth/public-config` 精确 open；`/auth/admin/**` 不 open。
- Bearer scopes原样换发内部 JWT；API Key路径不经过角色。
- CORS允许 `If-Match`，暴露 `ETag/X-Total-Count/X-Trace-Id`，allowedOrigins不为 `*`。
- 无效 Bearer + 无 API Key=401；管理前端不会静默 fallback到 Key。

## 6. 合同测试

为 `AuthSession`、Admin DTO、DocumentInfo、QueryResult 建 JSON fixture：

- Java controller测试序列化真实响应 fixture。
- 前端 `types`/parser测试读取同一 fixture（可放 `capability-showcase-frontend/src/test/contracts/`；复制时需版本注释）。
- 必填字段删除、字段类型变化让 CI失败；加法字段允许。
- 错误合同统一至少 `{error,message}`，冲突额外含 `currentVersion` 或当前资源。

## 7. E2E与回归矩阵

计划引入 Playwright，使用真实 edge + auth + knowledge 的测试环境：

| 身份 | tenant | scopes关键项 | 预期 |
|---|---|---|---|
| bob | globex | chat | 无管理入口、不可入库、可查共享 |
| editor测试用户 | tenantA | chat,ingest | 可管理自己的租户文档，不可共享入库 |
| alice/admin | acme | role-admin,public-ingest等 | 可管理RBAC、可共享入库/删除 |
| API Key模式 | binding决定 | 前端未知 | 能力反应式鉴权，不能进管理中心 |

主流程：

1. 登录 -> 深链返回 -> refresh恢复 -> 登出。
2. admin创建角色 -> 创建用户 -> 分配角色 -> 新用户登录 effective scopes正确。
3. 两浏览器并发编辑同一用户 -> 一个成功、一个冲突且不丢草稿。
4. 禁用用户 -> refresh失败；旧 access token行为按已知 TTL记录。
5. tenant A上传私有、admin上传共享 -> A/B查询：共享都见、A私有B不可见。
6. 非 public-ingest 直接构造共享上传 -> server 403。
7. public flag关闭 -> 共享 tab/入库入口关闭，私有RAG回归不受影响。
8. RBAC UI flag关闭 -> 能力控制台和登录仍正常。

## 8. 异常、性能与安全测试

### 异常注入

- auth/knowledge 404/409/412/429/500/503、超时、无 JSON body、重复字段。
- catalog失败但 admin页可用；admin失败但 capability页可用。
- refresh失败、cookie缺失、过期 access、网络离线恢复。
- 角色列表部分失败、分页中途删除导致空尾页、筛选快速切换乱序响应。

### 性能

- 10k用户/100角色数据下：用户列表始终只取50行；DB查询 p95目标300ms（staging）。
- 前端管理 chunk gzip建议不超过150KB增量；超出需 bundle报告解释。
- 快速输入10个字符只发送最终稳定查询；旧请求被abort。
- 50行表格服务端响应后200ms内稳定渲染。

### 安全

- XSS payload放入 username/tenant/role description/error message，页面只以文本呈现。
- localStorage/sessionStorage/URL/history/console中无 token、password、API Key。
- open redirect、CSRF（当前Bearer mutation）、CORS origin、cookie SameSite/Secure回归。
- 管理请求在有 API Key override时仍只带 Bearer。
- 非管理员直接 curl管理 API始终403；跨tenant私有知识永不出现在响应。

## 9. 灰度与回滚验证

- 前端四组合：RBAC UI on/off × shared UI on/off，均能启动。
- 后端：rbac on/off、admin writes on/off、registration on/off、public on/off 的关键组合测试。
- 新前端 + 旧兼容后端：管理写自动禁用并显示版本不支持，不发危险请求。
- 旧前端 + 新后端：登录、能力执行、私有RAG不回归。
- VERSION列存在时回滚旧 auth代码可启动并忽略列。
- 关闭 public后不删除 `__public__` 数据；重新开启可恢复，不需重灌。

## 10. 最终可验证验收标准

- 前端 `npm test`、`npm run type-check`、`npm run build` 全绿；Playwright关键流程全绿。
- `mvn -pl auth-service -am test`、`mvn -pl knowledge-service -am test`、`mvn -pl edge-gateway -am test` 全绿。
- 无 role-admin 的 Bearer和任何UI绕行都不能成功写 admin API。
- 两管理员并发写不会静默覆盖。
- tenant A/B共享可见、私有隔离的正负断言同时通过。
- 生产构建静态资源扫描不到 `demo12345`、access token、API Key。
- 灰度开关组合和回滚演练通过，数据库对账无 USER_ROLE/ROLE_SCOPE 漂移。
- 文档、接口示例和最终运行配置与测试环境一致。
