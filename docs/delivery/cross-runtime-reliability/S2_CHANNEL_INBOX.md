# 渠道可靠入站收件箱

## 行为与所有权

channel-service 是渠道消息的唯一存储/写入权威。飞书、钉钉经已有签名校验后，将规范化消息写入
CHANNEL_INBOUND_INBOX，持久化失败返回 503 + Retry-After: 5；只有提交成功才 ACK。
后台处理与 ACK 解耦，不能以 HTTP 200 证明已经回复用户。未配置的渠道不创建处理绑定。

业务键为 SHA-256(JSON([配置租户, 渠道, 外部消息 ID]))。重复内容不覆盖；同键异内容返回 409。
存储不接受消息载荷选择租户、权限或执行器，不保存 JWT、应用凭据或原始回调报文。

PENDING → RUNNING → SUCCEEDED；处理失败 → 延迟 PENDING，次数耗尽 → DEAD。
RUNNING 租约过期可重领，每次领取递增 epoch。完成、失败、心跳均检查 owner/epoch/有效租约。
进程退出或队列丢失后，可由其他副本恢复；租约超时本身也计入尝试次数。
最大 5 次（可配置），指数退避最多 300 秒，每次领取有界，执行器容量与队列有界。
渠道绑定轮询防止单渠道长期垄断资源；当前一个应用配置绑定一个租户，不声称完成通用多租户公平队列。

远程 chat/回复与数据库事务分开，语义为至少一次。回复发送成功但本地完成未提交时可能重复回复。
退款仍把原 messageId 传给 workflow 的数据库幂等入口，重放不创建第二张同键工单。
超过执行时间停止续租，已在途的远程效果不能撤销；不要声称本地 fencing 能取消外部请求。

## 配置与迁移

- CHANNEL_INBOX_STORE 默认沿用 CHANNEL_DEDUP_STORE；生产选择 jdbc，开发 memory 容量默认 10000。
- 复用 channel.dedup.datasource / CHANNEL_DEDUP_DB_*，不新建数据库实例。
- 应用不执行 DDL。发布前由已有 database-migrations runner 对 channel 执行新增 V2；V1 保持原样。
- 并发 4、租约 60000ms、最多续租至执行 180000ms、轮询 1000ms、初始退避 5000ms。
- CHANNEL_INBOX_SUCCESS_RETENTION_MILLIS 默认 0（不删除），由业务决定保留窗口。
  有界清理仅删除 SUCCEEDED；PENDING/RUNNING/DEAD 不自动删除。清理去重记录后过期重投可能再次执行。
- 监控现有日志的 inbox polling/retry persistence unavailable 和 stale completion；
  用受鉴权状态接口识别积压、DEAD、重复租约。日志不输出载荷或外部异常正文。

GET /channel/inbox?limit=50 要求 channel scope，只返回当前验签租户的状态，不返回消息内容。
POST /channel/inbox/{id}/replay 要求 channel.admin scope，只允许当前租户 DEAD → PENDING。
管理员先核对远程效果再重放，不能更改原业务键或内容；权限通过现有 IAM 授予，不给默认账号加管理员。

先迁移再滚动发布，观察 503、隔离和积压。回滚旧镜像不会消费新 inbox：先停止接收或排空新消费者，
保留 V2 表与记录以供恢复；不要 DROP 表。历史 PROCESSED_EVENT 只有去重键，无法恢复此前丢失的消息内容。

## 验证

- 原有桥、签名、意图路由行为继续验证，桥去重测试改为 inbox 调度语义。
- 内存/H2 相同恢复契约：重复/冲突、租户隔离、并发领取、重领 epoch、旧写回拒绝、延迟重试、DEAD、人工重放、清理。
- 丢弃执行器的已 ACK 消息可在新实例领取，不依赖渠道再次投递。
- 两个控制器存储不可用均返回 503，受鉴权运维入口不能访问其他租户或消息正文。
- `python3 deploy/test-channel-inbox-mysql.py` 使用 dev_infra MySQL 8.4 的本次独立临时库，
  运行相同恢复/并发测试，最后只清理本脚本创建的库；不会触碰现有业务表。
