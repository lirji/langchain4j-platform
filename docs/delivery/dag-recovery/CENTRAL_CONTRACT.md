# 中央检查点契约

V5扩展现有任务表；POST /async/tasks/{id}/checkpoint 使用专用checkpoint action JWT。请求 leaseEpoch + expectedRevision + state：revision为null时只读取；写入返回新revision。空状态返回revision0。状态上限2MiB/256操作，schemaVersion固定1。

404：任务不存在或tenant/actor不匹配；409：终态、租约过期/owner/epoch不匹配或CAS冲突；400：非法状态；413：超限。旧版本worker的claim不传checkpointVersion，只能领取v1；新版传1可领取v1/v2。

中央事务先锁父任务，再读/改检查点并追加dag-checkpoint事件（仅revision元数据），不会在事务内调用模型。父任务清理同事务逻辑删除检查点，不触发物理擦除。

先迁移V5和升级全部中央分发实例，再升级worker，最后打开Python API的ASYNC_TASK_DAG_RECOVERY_ENABLED及AGENT_DAG_OPTIMIZATION_ENABLED。旧中央实例不认识新kind，不能与新中央共同领取v2；旧Python worker由新中央版本过滤隔离。

验证：本仓 python3 deploy/test-readonly-dispatch-mysql.py；跨进程测试从Python仓运行 uv run python scripts/test_durable_worker_process.py --dag-recovery --java-repo <本仓目录>。无生产部署。更多边界见PLAN、TEST_RESULT、ENTERPRISE_FEATURE_REPORT。
