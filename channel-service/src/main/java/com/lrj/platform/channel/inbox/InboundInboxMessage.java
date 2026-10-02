package com.lrj.platform.channel.inbox;

/** 语言中立的入站快照；框架对象和访问凭据不进入持久化消息。 */
public record InboundInboxMessage(String inboxId, String tenantId, String source, String messageId,
                                  String payloadHash, String payload, String traceId, String status,
                                  int attempts, String leaseOwner, long leaseEpoch, long leaseUntil,
                                  long nextAttemptAt, long createdAt, long updatedAt, String errorCode) {

    /** 状态使用显式稳定 code，数据库中不存枚举 ordinal。 */
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String DEAD = "DEAD";
}
