# TEST_RESULT — MP0–MP5

2026-10-14，本地验证 PASS；代码行为以 `VERIFICATION_EVIDENCE.json` 的文件 SHA-256 固定。最终全量测试后只同步了两处工作流 Java 注释，并重新打包 workflow 及依赖通过。没有把先前失败的日志作为通过证据。

| 验证 | 结果 |
|---|---|
| `mvn -o -B test` 全 Reactor | 1470 项，0 失败、0 错误、17 跳过；全部模块 BUILD SUCCESS |
| `mvn -o -B -pl workflow-service -am -Pflowable-it test` | 含依赖 209 项，0 失败、0 错误、4 跳过；其中 Flowable 10 项全部执行通过 |
| `mvn -o -B -Dmaven.test.skip=true package` | 全 Reactor BUILD SUCCESS |
| 真实 MySQL：认证 / 订单 / 入库 / 图谱 | 3 / 4 / 4 / 4 项，通过 |
| 真实 MySQL：长期记忆 / 知识运营 | 17 / 11 项，通过；应用账号无 DELETE 权限 |
| 真实 MySQL：只读调度 / 渠道 Inbox | 6 / 10 项，通过 |
| 真实 MySQL：工作流回执 / 并发幂等 / 逻辑删除 | 每类 1 项，通过 |
| `python3 deploy/test-mybatis-boundary.py` | 22 个 XML：无物理 DELETE/TRUNCATE、`${}`、无类型参数或运行时 DDL；8 处专用 JDBC 例外 |
| 5 项配置门禁 | database-migration / production-cutover / runtime-hardening / observability / supply-chain 全通过 |
| 打包资源检查 | 7 个应用包均含 platform-persistence 与本模块 Mapper XML，事件 Mapper 在共享 eventbus 包 |
| Code Hygiene | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；无 formatter，未配置静态分析；无阻断 |
| `git diff --check` | PASS |

普通测试的环境条件跳过未冒充执行通过；真实 MySQL 条目独立执行，Flowable 使用专门 profile。未修改前端，本轮未重跑前端测试。

本次曾暴露并修复 NULL 投影、XML 参数绑定、H2 upsert 方言和旧测试的物理删除断言；最终完整回归全部通过。工作流测试同时验证旧键重试冲突、新键可创建，未为测试放宽幂等语义。

原始日志留在本机 `/tmp/lc4j-soft-final-*.log` 及各模块 `target/*mysql.log`；仓库中的 [VERIFICATION_EVIDENCE.json](VERIFICATION_EVIDENCE.json) 持久记录摘要、日志哈希与源码指纹，不提交可能含临时账号或测试密码的原始日志。

真实数据库只使用随机隔离 schema；检查无残留 schema/测试账号。`dev-infra-mysql84-1` 从原停止状态临时启动，测试后恢复停止。未操作既有业务库/生产数据。

新增 MyBatis 6 个依赖坐标的 OSV 查询无匹配条目，见 [DEPENDENCY_AUDIT.json](DEPENDENCY_AUDIT.json)。供应链**配置**门禁不是漏洞扫描，未宣称全仓安全扫描通过；远程基线 `4fd8fd4` 的供应链流程已有失败，本任务未处理历史漏洞债务。远程本次结果见 DELIVERY_RESULT。
