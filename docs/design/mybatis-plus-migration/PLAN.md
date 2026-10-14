# MyBatis-Plus 全项目业务持久化迁移

用户明确选择MyBatis-Plus并授权整个项目迁移。Java仓库基线4fd8fd4，任务分支refactor/mybatis-plus-persistence；Python及其历史dirty不属于本次Java框架迁移。

## 基线与约束

2026-10-14重构前 `mvn -o -B test` BUILD SUCCESS（日志/tmp/lc4j-mp-baseline.log）。既有测试覆盖MySQL兼容语法、租户、CAS、消息/工作流事务及记忆治理；保留这些行为测试，补MyBatis装配/事务保护及扫描约束。baseline=PASS，test_protection=PASS，允许逐批迁移，失败先修当前批。

固定HTTP/DTO/表结构/迁移历史/消息格式/条件更新/事务边界，保留显式租户谓词及FOR UPDATE；不启用自动租户/乐观锁/逻辑删除插件改写SQL。Flyway继续独立迁移。类名Jdbc*和store=jdbc保留为关系库配置兼容标识，不代表仍使用JdbcTemplate。

## 技术决策

MyBatis-Plus3.5.17、mybatis-spring3.0.5、MyBatis3.5.19（与官方Boot3 starter依赖一致），Java21/Boot3.3.5不升级。新增platform-persistence窄共享模块，仅管理MP会话工厂、Spring事务参与及通用结果边界；不加入自动数据源/全局MapperScan，避免覆盖既有多数据源和Flowable引擎的工厂。

每个业务Store显式绑定原DataSource、MP Mapper和可审查XML；投影使用持久化类型，复杂SQL留在XML而不拼进业务层。BaseMapper用于合适的固定表CRUD，自定义XML保留CAS、租约、upsert及锁语义。SqlSessionTemplate参与同一DataSource的Spring事务，关闭二级缓存、一级缓存使用STATEMENT以避免锁行和跨Mapper读取陈旧。

官方依据：https://baomidou.com/getting-started/install/ 、https://baomidou.com/en/getting-started/config/ 、https://mybatis.org/spring/transactions.html 。制品POM许可证Apache-2.0；本次必要引入MyBatis升级需补Flowable真实引擎原子性回归。既有供应链债务不冒充解决，新增依赖检查单独记录。

## 批次

| 批次 | 范围 | 验收 |
|---|---|---|
| MP0 | 共享装配、版本管理、事务保护测试 | BaseMapper真实写读、回滚、重复键、工厂数据源隔离 |
| MP1 | 订单、事件去重、认证六类存储 | 旧接口/角色版本、关系写入及刷新令牌事务 |
| MP2 | 工作流和异步任务/Outbox/调度 | FOR UPDATE/CAS、租约、终态+Outbox原子提交、Flowable共同事务 |
| MP3 | 记忆、知识入库/图/运营、渠道Inbox | 租户、幂等、删除代次、重启恢复、并发 |
| MP4 | 全仓SQL边界扫描、特殊适配器、文档 | 清晰保留项清单、全量/真实MySQL、配置门禁、正常Git交付 |

特殊边界：Flyway迁移及DatabaseMetaData继续直接JDBC；NL2SQL是经过SqlGuard的受控动态查询执行器，维持其只读、超时及行数限制，不将任意SQL暴露为通用Mapper `${sql}`。Doris为可选ANN向量适配器，需单独评估其动态表/数组/DDL，不能假装是标准MySQL实体CRUD；最终明确实现或保留理由。Redis/ES/向量SDK不替换为关系库Mapper。

不改生产库、不回填或删除业务数据。回退代码即可回到原业务访问层；表和协议不变。临时MySQL仅用随机隔离schema，原容器启动状态执行前记录并恢复。

## 追加要求：0/1 逻辑删除（用户在实施中明确授权）

MP0–MP3 的 MyBatis-Plus 基础迁移先作为可回退检查点提交，随后继续 MP5，不在子任务后停下。
MP5 范围是上述平台自有关系业务表：新增 `DELETED TINYINT NOT NULL DEFAULT 0`，0 正常、1 删除；保留历史 Flyway 文件，新版本扩展列及必要索引。所有正常查询和更新过滤正常行，删除用条件 UPDATE；BaseMapper 使用显式 TableLogic。

删除是可观察行为变更：保留数据库墓碑，不再表示物理清除。身份/成员关系/图等可重建实体采用显式条件恢复，版本不倒退；历史任务、已完成消息/事件不因重投自动复活，保留去重键；事件序号统计包含墓碑以保持单调。记忆仍增加删除代次阻止旧抽取回填。查询 OR、JOIN 和子查询必须分别验证删除过滤。

Flowable 内部引擎表、Flyway 历史、NL2SQL 的外部只读数据和 Redis/ES/Doris 等检索适配器不套业务表字段。历史迁移及隔离测试清理不改为业务逻辑删除。生产切换必须先迁移，再确保旧版本停止写入；首次逻辑删除后不得直接回滚到不识别 DELETED 的旧代码，否则旧查询会重新看到墓碑。需在报告明确这个回滚边界。

验收：表中保留 deleted=1；正常读不到；重复删除幂等；跨租户不能删；已删行不能被旧 CAS 改写；成员关系移除后重加、实体同键重建、图重新入库、事件去重释放再领取、任务清理后序号/去重均符合语义。H2 和真实 MySQL 验证，应用业务 SQL 边界禁止物理 DELETE/TRUNCATE。
