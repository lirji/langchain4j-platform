# MyBatis-Plus 与逻辑删除重构报告

## projectSummary

2026-10-14，langchain4j-platform 自有关系业务持久化切换至 MyBatis-Plus，并按追加要求使用 `DELETED=0/1`。8 个模块、22 个 Mapper；8 个 schema、33 张自有表增加逻辑删除字段。Python agentscope-platform 无修改。Java 21、Spring Boot 3.3.5 保持现有版本。

## baseline

基线 commit `4fd8fd4`，原 JdbcTemplate/手写 JDBC 固定 SQL 分散在 Store 中。重构前全 Reactor 测试通过（本机 `/tmp/lc4j-mp-baseline.log`）。任务分支 `refactor/mybatis-plus-persistence`；原目录工作树干净，从此基线按批次实施。

## problems

- P2：固定关系 SQL 位于 Store，无法统一检查参数绑定、逻辑删除和查询边界；证据为基线各 `Jdbc*Store`，当前迁入同包 XML。
- P1：用户追加的逻辑删除会改变唯一键、幂等、CAS、序号与清理语义；只替换 DELETE 会导致关系无法重加、迟到请求复活或事件续传回退。证据见逻辑删除行为测试。
- P1：旧代码不识别删除标记，产生墓碑后直接回滚会重新显示数据，不能仅用“新增列兼容”描述业务兼容性。
- 没有发现需要本任务扩大范围处理的 P0；不将未测试的容量或生产环境宣称安全。

## targetArchitecture

Current：业务 Store → 类型化 Mapper → 可审查 XML → 原 DataSource。`platform-persistence` 显式创建 MyBatis-Plus 工厂与 SqlSessionTemplate，参与原 Spring 事务，不覆盖 Flowable 工厂或创建第二数据源。BaseMapper 用于事件去重，其余 CAS/锁/租约/复杂投影保留明确 SQL。关闭会话缓存，保留 NULL 投影、影响行数和原 SQL 超时语义。

Transition：先发布新 Flyway 扩展，再暂停写入并完成所有相关实例升级；有删除标记的读写不能与旧版本混跑。

Target：全部自有固定关系业务 SQL 集中在 Mapper；0 正常、1 已删除，数据库默认值、非空与 CHECK 约束兜底。Normal read/write 显式过滤；内部去重、序号和种子判断的少数读允许包含墓碑，边界脚本逐项列出。

## refactoringPlan / changeSets

| 切片 | 内容 | 结果 |
|---|---|---|
| MP0 | 共享装配、依赖、类型边界、事务测试 | `92eb562` |
| MP1 | 认证、订单、事件去重 | `fdcbf79` |
| MP2 | 工作流、异步任务与 Outbox | `54793f1` |
| MP3 | 记忆、知识、渠道 | `97106e6` |
| MP5 | 33 表逻辑删除与行为回归 | `2db7a04` |
| MP4 | SQL 边界、真实数据库验证脚本、CI、文档 | 随本报告交付 |

## testProtection / implementation

保留既有接口、租户、权限、租约、CAS 与事务测试，新增 MP 多数据源/回滚/异常翻译/全 NULL 投影验证；真实 MySQL 验证认证关系原子性、订单 JOIN、图重建、知识入库、记忆治理、知识运营、调度、Inbox、Flowable 并发和回执。

逻辑删除专项覆盖：墓碑存留、重复删除、跨租户、同键显式重建时版本递增、旧版本更新拒绝、Outbox OR 条件和迟到回调、任务与调度事务级联、事件序号与幂等保持。工作流已删除幂等键禁止复用，新工作流必须新键。记忆和知识运营的真实测试应用账号仅有 SELECT/INSERT/UPDATE，证明相关业务无需 DELETE 权限。

新迁移均带字段中文注释，未改历史迁移；常用任务/事件/outbox/画像/图谱查询增加联合索引。保留 `Jdbc*` 类名和 `store=jdbc` 配置以兼容既有装配。

## verification

命令、测试数量和实际结果见 [TEST_RESULT](TEST_RESULT.md)。生产数据库未迁移，未部署；真实集成仅使用随机隔离 schema 并清理。

## behaviorCompatibility

| 维度 | 结果 | 说明 |
|---|---|---|
| API | UNCHANGED | 路径、DTO、HTTP DELETE 方法和权限不变；删除后行为如下 |
| DB | CHANGED | 8 个增量迁移，33 张表增加删除列/约束及必要索引 |
| MQ | UNCHANGED | 消息格式、租约和确认边界保留 |
| External | UNCHANGED | HTTP、模型、向量/检索适配器协议不变 |
| Business | CHANGED | 删除保留墓碑，不代表物理擦除；已删除工作流幂等键继续占用 |

## remainingRisks / technicalDebt

- 逻辑删除会保留正文和占用存储，未增加物理清理或编造保留期限；未来归档/擦除需单独业务决策。
- 大库 DDL 锁耗时、真实容量/索引收益未测，不承诺在线零停顿。
- 8 处直接 JDBC 有明确用途：NL2SQL 沙箱/元数据、Flyway迁移、Doris ANN 适配器。Flowable内部表、Redis、ES、向量投影不套业务表软删除规则。
- 没有仓库统一 formatter/static analyzer；遵循相邻代码，Code Hygiene 按明确限制报告。
- 新增 MyBatis 坐标 OSV 查询无匹配已知条目（见 JSON），并不等于全仓供应链扫描通过；历史漏洞债务未纳入本次改造。
- 已解决：固定业务 SQL 分散、无类型参数入口及本次逻辑删除引入的一致性窗口。暂缓：上述专用适配器、历史依赖债务与容量治理。

## rollbackPlan

首次逻辑删除前可回退到 MP 基础检查点；一旦产生墓碑，只能回退到同样识别 DELETED 的代码。禁止回退到忽略删除标记的旧程序，禁止批量置 0 作为回滚。旧迁移不覆盖，新迁移按原 Flyway 发布流程执行。

## nextMilestone / evidence

本任务交付后，部署方按 [持久化指南](../../平台工程/mybatis-plus-persistence.md) 评估 DDL 并安排切换；不隐含生产部署授权。验证脚本位于 `deploy/test-mybatis-*.py` 及现有真实 MySQL 脚本；新增测试与 Mapper、迁移随代码提交。最终 Git 状态见 [DELIVERY_RESULT](DELIVERY_RESULT.md)。
