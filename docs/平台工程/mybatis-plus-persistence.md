# MyBatis-Plus 持久化

Java 自有关系库访问统一由 `platform-persistence` 装配 MyBatis-Plus 3.5.17，MyBatis 3.5.19、mybatis-spring 3.0.5。Java 21 / Spring Boot 3.3.5 不变。Python AgentScope 服务不使用 Java 持久化框架。

## 实现约定

- Store 持有类型化 Mapper；参数具名绑定，SQL 位于 Mapper 同包、同名 XML（resources 下完整包路径）。返回持久化 Row，再转换领域/接口对象。禁止 `${sql}` 及业务层拼 SQL。
- BaseMapper 适合固定表 CRUD（事件去重）；CAS、租约、upsert、复合锁使用 XML。不能仅靠 ORM 插件替代租户、状态和影响行数校验。
- `PlusMapperFactory` 使用现有 DataSource、SpringManagedTransactionFactory 和 SqlSessionTemplate；不开全局 MapperScan，不创建第二数据源，不改变 Flowable 自有会话工厂。
- 禁用二级缓存，一级缓存使用 STATEMENT；全部投影列为 NULL 也保留行对象，避免将空值误认成缺行。
- 记忆和知识运营保留原 10 秒 SQL 超时；其他存储沿用事务/驱动配置，不因迁移新增超时行为。
- `Jdbc*Store` 类名和 `store=jdbc` 配置仍表示关系库模式，部署环境变量无需改名。表由独立 Flyway 发布前迁移，业务启动只校验契约。

## 覆盖范围

| 模块 | Mapper 数 | 内容 |
|---|---:|---|
| auth-service | 6 | 用户、角色、用户组、组关系、租户策略、刷新会话 |
| order-service | 1 | 订单/客户与演示数据 |
| platform-eventbus | 1 | 事件去重 |
| workflow-service | 4 | 回复、幂等、HTTP/Kafka Outbox |
| async-task-service | 5 | 任务、事件、两个 Outbox、只读调度 |
| conversation-service | 1 | 长期记忆治理 |
| knowledge-service | 3 | 入库任务、图谱、知识运营 |
| channel-service | 1 | 持久化 Inbox |

保留的直接 JDBC：Flyway/元数据迁移；NL2SQL 只读沙箱与结构探测；Doris ANN 向量专用适配器。前两者不是固定业务表 CRUD；Doris 使用动态租户表、数组字面量和 ANN 方言，属于可重建检索投影，不能伪装成普通 MySQL BaseMapper。Redis、ES、向量库 SDK 保持原适配器。

## 验证

```bash
python3 deploy/test-mybatis-boundary.py
mvn -B test
mvn -B -pl workflow-service -am -Pflowable-it test
# 共享 MySQL 可用时；仅创建随机隔离 schema 和短期测试账号，finally 清理
python3 deploy/test-mybatis-mysql.py
python3 deploy/test-profile-memory-mysql.py
python3 deploy/test-knowledge-operations-mysql.py
python3 deploy/test-readonly-dispatch-mysql.py
python3 deploy/test-workflow-receipt-mysql.py
python3 deploy/test-channel-inbox-mysql.py
```

[官方安装依赖](https://baomidou.com/getting-started/install/)；[Spring 事务参与约束](https://mybatis.org/spring/transactions.html)。完整验证与 Git 交付记录位于 `docs/delivery/mybatis-plus-migration/`。

## 0/1 逻辑删除

按追加要求，8 个业务 schema 的 33 张自有表增加 `DELETED TINYINT NOT NULL DEFAULT 0`，数据库 CHECK 限定 0/1。`0` 是正常记录，`1` 是墓碑。既有 HTTP DELETE 接口保留；其关系库实现改为条件 UPDATE。所有正常查询、统计、JOIN 两侧和写入条件过滤正常行；BaseMapper 事件去重使用 `@TableLogic(value="0", delval="1")`。

- 用户/角色/组/成员关系/租户策略允许显式重建；唯一键仍保留，只恢复墓碑，不覆盖正常记录；实体版本持续递增，旧版本不能修改重建后的对象。
- 图谱按稳定 GRAPH_ID 原子 upsert，显式重新入库可恢复相同投影。
- 任务、事件、Inbox 和工作流幂等键保留去重依据；迟到回调不能复活已删除的工作流回复或 Outbox。重新发起工作流须使用新幂等键。
- 事件最大序号包含墓碑，避免续传序号回退。任务删除与调度上下文逻辑级联在同一事务。数据库种子检测包含墓碑，重启不会自动恢复删除的演示账号。
- 记忆删除仍增加所有者代次，原内容以墓碑保留，不再宣称物理擦除。逻辑删除不会释放存储空间；归档/物理擦除需要另行明确策略，本次不添加物理清理作业。

只读沙箱、Flyway历史、Flowable自有ACT_*表及检索/向量适配器仍按组件自身机制维护，不套业务表的0/1规则。隔离测试库的销毁和历史迁移脚本中的SQL也不是业务删除接口。

### 发布与回滚边界

先运行新增迁移：auth/async-task/workflow V4，knowledge-ingestion V5，knowledge-graph/order/channel/conversation V3。历史迁移保持不变。原行默认变为正常记录，新增列和联合索引无数据删除。

结构扩展允许旧程序暂时读取，但删除语义**不兼容旧程序**。切换前应暂停业务写入并完成所有相关实例更新，再恢复写入；不能混跑会忽略DELETED的旧读写实例。新增字段/索引在大库的锁耗时需部署时评估，本地验证不代表生产在线DDL时间承诺。

首次逻辑删除前可以退回基础MyBatis迁移检查点；产生墓碑后必须回滚到同样识别DELETED的版本，禁止直接回退到旧JdbcTemplate或无过滤Mapper。不得用批量置0“回滚”，那会恢复用户已删除的数据。本轮只交付代码和迁移，未在现有业务库或生产执行。
