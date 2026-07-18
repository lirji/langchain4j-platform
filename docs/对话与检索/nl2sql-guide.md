# NL2SQL / ChatBI 接入指南

本指南面向要把「自然语言问数据」接进平台的开发者。对应服务是 **`analytics-service`**（`:8083`），
端点 `POST /chat/sql`（别名 `POST /analytics/sql`），经边缘网关 `http://localhost:8080` 暴露。
**整套能力默认开启**（`NL2SQL_ENABLED=true`）——置 `NL2SQL_ENABLED=false` 关闭时 `analytics-service` 照常启动，只是不注册
NL2SQL 相关 bean 与端点（访问 `/chat/sql` 返回 404）。

把「自然语言 → SQL → 执行 → 自然语言解读」做成一条**受控**链路，是平台两条主线的直接延展：
**「`@Tool` 描述即模型决策依据」**（让模型知道何时该查库、传什么 SQL）+ **「grounding 防幻觉」**
（答案里的数字必须来自查询结果，不许编）。

> 端点约定：业务接口统一走 `edge-gateway`（`http://localhost:8080`，带 `X-Api-Key`）。
> `analytics-service` 自身监听 `:8083`，仅供服务间直连或本地调试。
> **本篇 curl 用 `dev-key-tenantA-admin`**（而非其它文档惯用的 `dev-key-acme`）：演示种子数据落在
> 租户 `tenantA` 下，且该 key 带 `analytics` scope；`dev-key-acme` 是 `acme` 租户、库里没有它的数据，
> L6 租户过滤后查询会全部返回 0 行。

---

## 1. 为什么做这个

企业最高频的内部刚需之一：业务/运营不会写 SQL，但天天要问「上月华东区退款 top10 客户」
「昨天各渠道下单量环比」。把它交给 LLM = 自助 BI。平台已有的能力让它几乎是纯组装：

| 复用现有能力 | 用在哪 |
| --- | --- |
| Tools / Function Calling（`@Tool` 自动发现） | 一个只读 `SqlQueryTool` 执行查询 |
| 声明式 `AiServices` + few-shot 范式 | SQL 生成的 prompt 工程（`SqlAssistant` 内嵌 schema + 3 例 few-shot） |
| grounding（确定性事后校验套路） | `NumberGrounding`：答案数字 ∈ 查询结果，否则追加核对提示 |
| 自修环（错误喂回、下一回合改写重试） | SQL 执行/护栏报错时返回错误文本而非抛异常 |
| 多租户 `TenantContext`（ThreadLocal） | 强制 `WHERE tenant_id = '<当前租户>'` 隔离 |

**真正净新增的只有两块**：`SchemaProvider`（把表结构喂进 prompt）+ 一层**硬核 SQL 安全护栏**（`SqlGuard`）。

---

## 2. 阶段决策（沿用单体设计，已在新服务落地）

| 决策点 | 决策 | 理由 |
| --- | --- | --- |
| **生成方式** | LLM function calling（`AiServices` + `@Tool`），不自己解析 NL | 复用现有装配链，模型自己决定调不调、传什么 SQL |
| **执行安全** | **独立只读账号 + 语句白名单 + 强制 LIMIT + 超时**，多层兜底 | SQL 注入 / 全表扫描是这个场景唯一的真风险，宁可层层冗余 |
| **dev 数据库** | **MySQL + demo 种子**（订单/客户/退款），`createDatabaseIfNotExist` 自动建库 | 接生产真实只读库只改 `NL2SQL_DB_*` + 把 seed-script 置空 |
| **表暴露范围** | **白名单**，不 dump 整库 | 控制 prompt 长度 + 缩小攻击面 + schema 精准 |
| **租户隔离** | prompt 注入 `tenant_id` + 护栏强制核对（缺则拒答） | 对齐现有 `TenantContext` 语义 |

---

## 3. 链路总图

```text
POST /chat/sql  {"question":"上月退款 top5 客户"}
   │  经网关过滤器链：ApiKey→内部 JWT→下游还原 TenantContext（有值）
   ▼
AnalyticsController.chatSql            (analytics-service :8083)
   ▼
NlToSqlService.ask(question)
   ├─ SqlExecutionContext.begin()      本轮 ThreadLocal 执行记录
   ├─ SchemaProvider.schemaText()      白名单表/列/注释/中文枚举 distinct → 紧凑 schema 文本
   ▼
SqlAssistant.answer(schema, tenantId, question)   （AiServices，仅挂 SqlQueryTool 一个工具）
   │  LLM 生成 SELECT 并调用 run_sql 工具
   ▼
SqlQueryTool.runSql(sql)
   ├─ 自修环上限：本轮 run_sql 次数 ≥ maxToolCalls → 直接终止
   ├─ SqlGuard.check()  ── L2 只读/单语句/关键字 · L3 表白名单 · L6 租户谓词 · L4 补 LIMIT
   ├─ 通过 → 只读 JdbcTemplate 执行（L1 只读账号 + L5 statement 超时）
   └─ 拒/失败 → 返回错误文本（不抛异常），模型下回合改写重试
   ▼
LLM 拿 rows（markdown 表）生成自然语言解读
   ▼
NlToSqlService：读 SqlExecutionContext.lastSuccessful() 取本轮 sql + rows
   ├─ NumberGrounding（默认开）：答案数字 ∉ rows/行数/问题 → 末尾追加 ⚠️ 核对提示
   └─ AuditLogger.record(nl2sql.query)
   ▼
返回 AnalyticsSqlReply {question, sql, rowCount, rows, answer, guardBlocked}
```

关键类都在 `analytics-service` 的 `com.lrj.platform.analytics.*` 下；跨服务 DTO
（`AnalyticsSqlRequest` / `AnalyticsSqlReply`）在 `platform-protocol` 的
`com.lrj.platform.protocol.analytics` 下。

---

## 4. 快速开始

### 4.1 起服务

**方式 A — 整套本地栈（推荐，自带 MySQL + LiteLLM）**

`deploy/docker-compose.yml` 里 `analytics-service` 已默认 `NL2SQL_ENABLED=true`，并把数据源指向栈内
`mysql`。起栈后种子脚本自动建 `nl2sql_demo` 库、三张表与只读账号：

```bash
docker compose -f deploy/docker-compose.yml up --build
# 冒烟：通过网关打两个别名端点并校验响应字段
bash deploy/smoke-nl2sql.sh
```

**方式 B — 单跑 analytics-service（需本机可达的 MySQL）**

先构建平台共享库（`platform-*` 必须先在本地仓库就绪），再带环境变量启动：

```bash
mvn -DskipTests -pl platform-security,platform-protocol,platform-audit,platform-gateway-client install
NL2SQL_ENABLED=true \
NL2SQL_DB_ADMIN_USER=root NL2SQL_DB_ADMIN_PASSWORD=root \
mvn -pl analytics-service spring-boot:run     # :8083
```

默认数据源指向 `jdbc:mysql://localhost:3306/nl2sql_demo?createDatabaseIfNotExist=true...`，
admin 账号首启会建库、跑 `db/nl2sql-demo.sql` 种子、创建只读账号 `nl2sql_ro`。
NL2SQL 走**函数调用**，因此 LiteLLM 背后 `chat-default` 映射的模型**必须支持 tool-calling**（见 §11 坑 1）。

> 单独起网关做端到端：`mvn -pl edge-gateway spring-boot:run`（:8080）。

### 4.2 第一条 curl

```bash
curl -s -X POST 'http://localhost:8080/chat/sql' \
  -H 'X-Api-Key: dev-key-tenantA-admin' \
  -H 'Content-Type: application/json' \
  -d '{"question":"2026 年 5 月 tenantA 一共退款了多少钱？"}'
```

`/analytics/sql` 是完全等价的别名（同一 handler），供不想用 `/chat/*` 前缀的调用方：

```bash
curl -s -X POST 'http://localhost:8080/analytics/sql' \
  -H 'X-Api-Key: dev-key-tenantA-admin' \
  -H 'Content-Type: application/json' \
  -d '{"question":"退款金额最高的 3 个客户是谁？"}'
```

响应（字段固定，数值随模型实际生成的 SQL 而定，此处示意）：

```json
{
  "question": "2026 年 5 月 tenantA 一共退款了多少钱？",
  "sql": "SELECT sum(amount) AS total FROM refunds WHERE tenant_id = 'tenantA' AND status = 'approved' AND created_at >= '2026-05-01' AND created_at < '2026-06-01' LIMIT 1000",
  "rowCount": 1,
  "rows": [ { "total": 6650.00 } ],
  "answer": "2026 年 5 月 tenantA 共退款 6650 元（已审批通过的退款）。",
  "guardBlocked": false
}
```

---

## 5. 请求 / 响应契约

请求 `AnalyticsSqlRequest`（`platform-protocol`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `question` | string | 自然语言业务问题。租户身份**不进 body**，随内部 JWT 传播。 |

裸 JSON `{"question":"..."}` 即可；请求体缺失或 `question` 为 null 时按空串处理（模型多半礼貌拒答）。

响应 `AnalyticsSqlReply`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `question` | string | 原样回显 |
| `sql` | string \| null | 本轮**最后一次成功执行**的 SQL（已经过护栏改写，如补了 `LIMIT`）；模型拒答/没查库时为 `null` |
| `rowCount` | int | 结果行数 |
| `rows` | array\<object> | 结果行（列名→值）；拒答时为 `[]` |
| `answer` | string | 自然语言解读；数字核对不通过时末尾会追加 `⚠️ 数字核对提示` |
| `guardBlocked` | boolean | 本轮是否**至少一次**被安全护栏拒（含被拒后又改写成功的情况） |

> **`sql` 一并回传是刻意的**：可审计 + 前端可「查看 / 复跑 SQL」 + debug 时一眼区分是生成错还是解读错。

---

## 6. SQL 安全护栏（本场景成败关键，6 层纵深防御）

任何一层都能独立拦住恶意输入，叠加是为了纵深防御——即便上层解析被绕过，DB 只读账号与超时仍兜底。
L2–L4 + L6 由纯逻辑类 `SqlGuard`（零外部依赖、可纯单测）完成，L1/L5 在执行层。

| 层 | 机制 | 实现位置 | 拦住什么 |
| --- | --- | --- | --- |
| **L1 只读账号** | 执行用 `nl2sql_ro`（`GRANT SELECT` only），HikariCP 连接再置 `readOnly=true` | `Nl2SqlConfig` / 种子脚本 | `DROP`/`DELETE`/`UPDATE` 在 DB 层直接被拒，绕过上层也无害 |
| **L2 单语句 / 只读** | 去尾 `;` 后不得再有 `;`；必须 `SELECT` 开头；禁写/DDL/越权关键字（`insert/update/delete/drop/alter/truncate/create/grant/...`，含 `union/into/set/use/exec/call` 等）；禁注释（`--` `/* */`） | `SqlGuard` | `'; DROP TABLE orders;--`、`UNION` 注入、注释绕过 |
| **L3 表白名单** | 抽取 `FROM`/`JOIN` 表名，必须全部 ∈ `app.nl2sql.allow-tables` | `SqlGuard` | 越权查 `users`/系统表/未暴露表 |
| **L4 强制 LIMIT** | 原 SQL 无 `LIMIT` 时自动追加 `LIMIT <max-rows>`（默 1000） | `SqlGuard` | 全表扫描拖垮库 |
| **L5 statement 超时** | JDBC `queryTimeout = <query-timeout-seconds>`（默 5s） | `Nl2SqlConfig` | 慢查询 / 笛卡尔积 |
| **L6 租户谓词** | 引用 `tenant-scoped-tables` 里的表时，SQL **必须**含 `tenant_id = '<当前租户>'`，否则拒 | `SqlGuard` | 跨租户数据泄露 |

**实现细节（与老单体的重要差异，务必看）：**

- **关键字/表名检测在「抹掉字符串字面量」的副本上做**：避免把 `WHERE note = 'shipped from warehouse'`
  里的 `from warehouse` 误判成表，或把值 `'请尽快 update'` 误判成写操作。关键字用 `\b` 词边界匹配，
  故 `created_at`/`update_time` 这类列名不会误伤。
- **L6 是「强制核对 + 拒答」，不是「自动注入」**：护栏**不会**替模型补 `WHERE tenant_id=...`；
  它只核对模型是否已写上正确的租户谓词（谓词由 `SqlAssistant` system prompt 注入 `{{tenantId}}` 要求模型写），
  缺失或写错就返回 `missing tenant filter` 让模型改写。**只有 L4 的 LIMIT 是护栏自动补写的。**
  这样设计避免护栏做脆弱的 SQL 改写，把「加租户」交给模型、把「核对」交给确定性代码。
- 起步用轻量正则（关键字黑名单 + `FROM/JOIN` 提取），够拦常见注入；要 AST 级严谨可后续引 JSqlParser
  ——但 **L1 只读账号 + L4/L5 兜底**是不引重依赖也安全的底气。

护栏被拒或执行报错时，`SqlQueryTool` **返回错误文本而非抛异常**（如
`Query rejected by safety guard: table not allowed: users. Rewrite the SQL and call run_sql again.`），
让模型在下一个工具回合自行改写——这就是自修环（§7）的地基。

---

## 7. 自修环 + 轮数上限

函数调用天然支持「错误喂回 → 改写重试」：工具返回的错误文本进入下一个工具回合，模型据此修正 SQL。
但坏 SQL 反复重试会烧 token，因此有硬上限：

- `app.nl2sql.max-tool-calls`（默 **5**）：本轮 `run_sql` 调用数（含被护栏拒/执行失败）达上限后，
  工具直接返回终止指令（`Maximum SQL attempts reached...`）、不再执行，防死循环。
- 计数基于本轮 `SqlExecutionContext`（ThreadLocal 执行记录）的大小。

---

## 8. 数字 grounding（确定性、零 LLM、warn 模式）

`NumberGrounding` 是纯函数，仿 RAG grounding 的确定性事后校验：核对**答案里的数字**是否有据可查。
默认开（`app.nl2sql.number-grounding=true`），仅在本轮有成功结果且非空时才跑。

- **判为「受支撑」的来源**：① 查询结果各 cell 的数值 ② 行数 `rowCount`（答案常说「共 N 笔」）
  ③ 用户问题里的数字。
- **豁免（压假阳性，不核对）**：绝对值 ≤ 10 的整数（序数/计数/「前 3 名」）；`[1900,2099]` 的四位整数（年份）。
- **归一**：去千分位逗号、去小数尾零（`5,400.00 → 5400`，`0.50 → 0.5`），让 SQL 返回的 `5400.00`
  与答案里的 `5,400` 能对上。
- **命中未支撑数字时**：不改答案本体，只在末尾追加
  `⚠️ 数字核对提示：答案中的 X 未在查询结果中找到，请以查询结果为准。`，同时 `warn` 日志留痕。

> 这是 warn 模式而非硬拦截：宁可提示也不误杀正确解读，把最终判断权留给使用者/前端。

---

## 9. 多租户隔离

租户身份来自内部 JWT 还原出的 `TenantContext`（ThreadLocal），`AnalyticsController` 完全不感知——
`NlToSqlService` 从 `TenantContext.current().tenantId()` 取值注入 prompt，`SqlGuard` L6 据此核对。

- `app.nl2sql.tenant-scoped-tables`（默 `orders, customers, refunds`）：这些表的查询**必须**带
  `tenant_id = '<当前租户>'`，否则 L6 拒答。
- `app.nl2sql.enforce-tenant-predicate`（默 `true`）：L6 总开关。demo 想省事可关，**生产别关**。
- 需要「全局只读表」（如字典表、无 `tenant_id` 列）时：把它放进 `allow-tables` 但**不**放进
  `tenant-scoped-tables` 即可显式放行。

**隔离演示**：种子给 `tenantA`（主数据集）和 `tenantB`（对照）各插了数据。用 `dev-key-tenantA-admin`
问「退款金额最高的客户」永远只返回 tenantA 的客户，**看不到 tenantB 的 9999 大额退款行**。
若要以 tenantB 身份验证另一侧，需在 `edge-gateway/application.yml` 的 `platform.security.api-keys`
里新增一个 `tenant: tenantB` 的 key（网关不按 scope 拦路由，只把 key→租户/scope 铸进 JWT）。

---

## 10. Schema 注入与中文枚举

`SchemaProvider` 用 JDBC `DatabaseMetaData.getColumns` 内省**白名单表**的列名/类型/注释（`REMARKS`），
拼成紧凑 schema 文本喂进 `SqlAssistant` 的 system prompt（`{{schema}}`）。只暴露 `allow-tables`
——与 L3 同一份白名单，既控 prompt token 成本又缩攻击面。schema 在启动时算一次并缓存。

**中文枚举（关键坑）**：业务库常有中文枚举（`status='已退款'`），不给模型看实际值它会猜英文。
`app.nl2sql.enum-columns` 标注的列会额外查 distinct 值带进 schema：

```yaml
app:
  nl2sql:
    enum-columns:
      orders: [status]     # 已支付 / 已发货 / 已取消 / 已退款
      refunds: [status]    # pending / approved / rejected
```

生成的 schema 片段形如：

```text
Table orders
  - id INT
  - tenant_id VARCHAR  -- 租户 id，所有查询必须按此过滤
  - amount DECIMAL  -- 订单金额，单位元
  - status VARCHAR  -- 订单状态（中文枚举）：已支付 / 已发货 / 已取消 / 已退款  (allowed values: 已支付, 已发货, 已取消, 已退款)
  ...
```

> MySQL 需在连接串带 `useInformationSchema=true`，`getColumns` 才会返回列 `COMMENT` 作为 `REMARKS`
> ——demo 的默认 URL 已带。

---

## 11. 端到端用例（对齐种子数据）

种子 `db/nl2sql-demo.sql` 建 `customers`/`orders`/`refunds` 三表（均带 `tenant_id`）。
下列用例用 `dev-key-tenantA-admin`，可直接复跑（结果值随模型生成的 SQL 略有出入）：

| 用例 | 问题 | 期望行为 |
| --- | --- | --- |
| 聚合 + join | 「退款金额最高的 3 个客户是谁？」 | 生成 `JOIN customers ... GROUP BY ... ORDER BY ... DESC LIMIT 3`，两表都带 `tenant_id='tenantA'`（L6），返回赵六 / 李四 / 张三 |
| 租户隔离 | 同上，但换 tenantB 身份 | SQL 自动改用 `tenant_id='tenantB'`，只返回 ACME-A 9999，**看不到 tenantA 数据** |
| 越界拒答 | 「各供应商的库存周转率是多少？」 | 不调工具，`sql=null`、`rowCount=0`，答「可查询的数据集里没有供应商或库存相关的表」 |
| 中文枚举 + 自动 LIMIT | 「状态是已退款的订单有哪些？」 | 模型用对 `status='已退款'`（来自枚举 distinct），无 `LIMIT` 被 L4 自动补 `LIMIT 1000` |
| 注入拦截 | 诱导 `'; DROP TABLE orders;--` | 护栏拒（L2 多语句/注释/关键字），`guardBlocked=true`，模型改写或安全回话 |

---

## 12. 接生产真实只读库

demo 种子只为本地跑通；接真实库时：

1. 把 `NL2SQL_SEED_SCRIPT` 置空（`NL2SQL_SEED_SCRIPT=`）——不建 demo 库、不跑种子。
2. `NL2SQL_DB_URL`（admin，用于内省 schema）与 `NL2SQL_DB_READONLY_URL`（执行）指向真库；
   只读 URL **别带** `createDatabaseIfNotExist`（只读账号无建库权限）。
3. `NL2SQL_DB_READONLY_USER/_PASSWORD` 用一个**只 `GRANT SELECT`** 的账号（L1 的根基）。
4. 通过 `app.nl2sql.allow-tables` / `tenant-scoped-tables` / `enum-columns` 圈定暴露范围与租户表
   （这些没有独立 `NL2SQL_*` 短名，用 config-server 或 `--app.nl2sql.allow-tables=...` 配，或 Spring
   relaxed-binding 环境变量 `APP_NL2SQL_ALLOW_TABLES` 等）。
5. **表暴露控制在核心几张表**，列注释精简——白名单表越多 prompt 越胀、token 越贵。

---

## 13. 审计

每次问答都写一条 `nl2sql.query` 审计事件（`AuditEventType.NL2SQL_QUERY`），字段：`question`、
最终 `sql`、`rowCount`、`guardBlocked`。合规与成本追踪都要，也便于事后排查「被护栏拒了什么」。

---

## 14. 与其他能力的关系

- **Agent 动作 `analytics_sql`**：`agent-service` 的 ReAct 深度 Agent 可自主选择把子问题委派给本端点
  （由 `ANALYTICS_BASE_URL` 指向 `:8083`、`AGENT_ANALYTICS_ENABLED` 控制，默认开）。即 NL2SQL 既是
  独立业务端点，也是 Agent 工具箱里的一件工具。详见 [`docs/Agent编排/agent-guide.md`](../Agent编排/agent-guide.md)。
- **接口速查**：[`docs/参考/api-reference.md`](../参考/api-reference.md) 的「Analytics（analytics-service）」节。
- **运行与环境变量**：[`docs/参考/operations.md`](../参考/operations.md) 的「Analytics / NL2SQL」节。
- **平台整体架构 / 两层网关**：[`docs/参考/架构文档.md`](../参考/架构文档.md)。
- **回归评测**：老单体曾把 NL2SQL 纳入 eval harness（`type:"sql"` 黄金集）。新平台 `eval-service`
  **尚未移植**该 dispatch，故本篇不提供对应 `/eval` 用例；把 NL2SQL 纳入回归是后续可选项。

> 顺带澄清「两个 SQL 相关能力」：本篇是 **NL2SQL（自然语言查库）**；`analytics_sql` 是 Agent 调用它的**动作名**，
> 不是另一套实现。

---

## 15. 常见坑

1. **模型必须支持 tool-calling**：NL2SQL 走函数调用，纯文本模型（如未配工具能力的 `llama3.1`）不行。
   在 LiteLLM（`deploy/litellm/config.yaml`）里把 `chat-default` 映射到一个支持 function calling 的模型
   （单体验证时用 `qwen3:14b`）。
2. **`analytics-service` 独立进程 = 天然隔离**：老单体里 NL2SQL 只读库要小心不污染 Flowable/主库
   （得靠 `@Qualifier` 区分两个 DataSource）。新平台 NL2SQL 独占 `analytics-service`，只有它自己的
   admin + 只读两个连接池（`Nl2SqlConfig` 里就地构建，只读池**不注册为 bean** 以免注入歧义），不存在串台问题。
3. **schema 的 token 成本**：白名单表多了 prompt 会胀。控制在核心几张表、列注释精简。大库未来可做
   「先让 LLM 选相关表再注入子集 schema」，当前不做。
4. **中文列名/枚举值**：给关键枚举列配 `enum-columns`，否则模型爱猜英文 WHERE 值（见 §10）。
5. **租户列不一致**：不是所有表都有 `tenant_id`。有租户列 → 进 `tenant-scoped-tables` 强制过滤；
   无租户列的字典类表 → 只进 `allow-tables` 不进 `tenant-scoped-tables`，作为「全局只读表」显式放行。
6. **`dev-key-acme` 查不到数据**：它是 `acme` 租户、库里没有它的行。demo 数据在 `tenantA`，用
   `dev-key-tenantA-admin`。

---

## 16. 开关速查

带独立环境变量短名的（`analytics-service` `application.yml` 里已用 `${...}` 占位）：

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `NL2SQL_ENABLED` | `true` | NL2SQL 总开关；关时端点/bean 不注册 |
| `NL2SQL_DB_URL` | `jdbc:mysql://localhost:3306/nl2sql_demo?createDatabaseIfNotExist=true&...` | admin 数据源（建库/种子/内省 schema） |
| `NL2SQL_DB_READONLY_URL` | 同库但不带 `createDatabaseIfNotExist` | 只读执行数据源；留空则与 admin 共用 |
| `NL2SQL_DB_ADMIN_USER` / `NL2SQL_DB_ADMIN_PASSWORD` | `root` / `root` | admin 账号 |
| `NL2SQL_DB_READONLY_USER` / `NL2SQL_DB_READONLY_PASSWORD` | `nl2sql_ro` / `nl2sql_ro` | 只读执行账号（L1） |
| `NL2SQL_SEED_SCRIPT` | `db/nl2sql-demo.sql`（classpath） | demo 种子脚本；**接真实库时置空** |

无独立短名、走 `app.nl2sql.*` 属性的（config-server / `--app.nl2sql.x=y` / relaxed-binding 环境变量如 `APP_NL2SQL_MAX_ROWS`）：

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `app.nl2sql.max-rows` | `1000` | L4 无 `LIMIT` 时追加的行数 |
| `app.nl2sql.query-timeout-seconds` | `5` | L5 statement 超时（秒） |
| `app.nl2sql.allow-tables` | `[orders, customers, refunds]` | L3 表白名单 = SchemaProvider 暴露范围 |
| `app.nl2sql.tenant-scoped-tables` | `[orders, customers, refunds]` | L6 必须带 `tenant_id` 过滤的表 |
| `app.nl2sql.enforce-tenant-predicate` | `true` | L6 总开关（生产别关） |
| `app.nl2sql.enum-columns` | `orders:[status], refunds:[status]` | 带进 schema 的枚举列 distinct 值（坑 4） |
| `app.nl2sql.max-tool-calls` | `5` | 自修环上限，单轮 `run_sql` 最多调几次 |
| `app.nl2sql.number-grounding` | `true` | 数字 grounding（warn 模式）开关 |

> 网关侧：`analytics` 路由前缀 `/chat/sql,/analytics,/analytics/**` → `:8083`（`ANALYTICS_URI` 可覆盖）；
> `dev-key-tenantA-admin` 带 `analytics` scope，是本能力的演示 key。
