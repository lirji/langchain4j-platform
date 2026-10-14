# 验证记录

## S1 发布复查版本

- 先加入缺失/旧版本/新版本/空版本反例，原实现失败；修复后全部通过。
- `mvn -o -B -pl knowledge-service -am test` PASS。版本字段通过controller映射验证；原有发布前后registry检查保留。
- 兼容：KnowledgeHit.version为加法可空字段，旧Java九参数构造器保留；新复查面对旧query无version时拒绝通过。升级顺序query→ingest-api；普通查询无行为变化。
- 未运行真实模型或部署。

## S2 版本GC

- 首先加入batch=1且两个文档的回归，原实现失败（首文档重复、次文档未处理）。
- 公平推进、总版本预算、重启检查点、失败不推进且重试、不跨租户/保留窗口测试通过。
- 真实共享Redis随机前缀测试PASS：SCAN/HSCAN覆盖25文档/3租户、Lua条件推进、重新装配后进度存留、移除后重建拒绝旧writer。测试键已清理。
- `GC_REDIS_TEST=true mvn -o -B -pl knowledge-service -am test` PASS（密码仅进子进程环境）。
- 为验证暂启dev-infra Redis/MySQL，任务结束恢复其原停止状态。
