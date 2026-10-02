# S5: 退款未知结果与只读回执

Java 新增 POST `/workflow/refund/receipt`，请求为原 `chatId/message/dedupeId/webhookUrl`。
权威 ledger 查询沿用发起时的 tenant/user/chat/参数哈希，不创建 claim、不重新创建 Flowable 实例。
相同键不同参数或用户返回 409；不同租户或未知键返回 404。404 仅表示此刻未读到回执，
不能证明在途写事务失败。沿用既有流程清理策略，清理后的旧回执不承诺永久可查。

Python `refund_start` 的写操作不重试。传输失败、5xx 或无效成功响应后，只查询一次原回执；
已有提交显示原 instanceId、当前状态和 deduplicated；未读到则显示“结果未确认”，不鼓励换键。
新增 `refund_receipt(message)` 只读工具，使用原上下文幂等键，不消费确认 grant；发起工具继续要求新有效确认。
明确 4xx（408 除外）保留拒绝语义，错误文本不暴露 URL/凭据。总工具超时覆盖写请求和一次只读查询。

## 本地证据

- Flowable/H2 原子性 8 项通过，新增模拟发起响应丢失后读取同实例且 create 次数仍为 1；跨用户 409、跨租户 404。
- MySQL 8.4 隔离 UUID 临时库的真实 Flowable lost-response 测试通过，库已清理。
  Connector 必须 `nullCatalogMeansCurrent=true`，否则 metadata 会把其他库的 Flowable 表误认作当前库，
  脚本固定此参数，不修改共享 MySQL 全局配置。
- Python 工具 9 项通过，覆盖未知结果、原参数查询、只读 404、错误脱敏、无写重放及只读治理元数据。
- 完整回归与发布结果汇总于 DELIVERY_STATUS；原始本地日志保存在 .git 下，不作为发布制品。

兼容：新增接口和工具；既有 start 与数据库唯一约束保持。回滚 Python 不删除 Java ledger，
Java endpoint 可保留供旧任务手动读取；不回滚已发生退款效果。
