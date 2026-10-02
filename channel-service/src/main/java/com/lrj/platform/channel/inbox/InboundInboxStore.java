package com.lrj.platform.channel.inbox;

import java.util.List;

/** 消息所有权留在 channel；ACK 与执行之间由持久化状态连接。 */
public interface InboundInboxStore {

    /** 原子接收；同键同内容返回 false，同键异内容抛冲突，不能覆盖已接收消息。 */
    boolean receive(InboundInboxMessage message);

    /** 有界领取待执行或租约已过期的消息；每次领取递增 epoch。 */
    List<InboundInboxMessage> claimDue(String tenantId, String source, String owner,
                                     long now, long leaseMillis, int limit);

    /** 只允许仍持有有效 owner/epoch 的执行器提交成功。 */
    boolean succeed(InboundInboxMessage claim, long now);

    /** 心跳只延续有效租约，过期执行器不能自我复活。 */
    boolean renew(InboundInboxMessage claim, long now, long leaseUntil);

    /** 失败由当前持有者转为延迟重试或 DEAD；旧执行器不得改写新租约。 */
    boolean retry(InboundInboxMessage claim, long now, long nextAttemptAt, boolean dead, String errorCode);

    /** 仅返回指定租户的有界状态快照，供受鉴权的运维入口使用。 */
    List<InboundInboxMessage> list(String tenantId, int limit);

    /** 人工重放只允许 DEAD，仍保留原消息与去重键；返回是否发生合法迁移。 */
    boolean replay(String tenantId, String inboxId, long now);

    /** 仅按明确配置的保留窗口、有界删除成功消息；未决和 DEAD 消息不自动删除。 */
    int cleanupSucceeded(long before, int limit);

    /** 同一业务键不能绑定另一份消息内容。 */
    final class PayloadConflictException extends RuntimeException {
        public PayloadConflictException() { super("inbound message key conflicts with its payload"); }
    }
}
