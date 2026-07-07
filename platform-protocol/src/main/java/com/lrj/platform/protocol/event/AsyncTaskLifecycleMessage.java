package com.lrj.platform.protocol.event;

import java.time.Instant;

/**
 * 异步任务生命周期事件（一 topic 一固定类型，带 schemaVersion，避免多态反序列化）。
 * 发往 {@link EventTopics#ASYNCTASK_LIFECYCLE}，key = tenantId。
 */
public record AsyncTaskLifecycleMessage(String eventId,
                                        int schemaVersion,
                                        String tenantId,
                                        String taskId,
                                        String kind,
                                        String status,
                                        Object result,
                                        String error,
                                        String webhookUrl,
                                        Instant occurredAt,
                                        String traceId) {

    /** 当前契约 schema 版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AsyncTaskLifecycleMessage {
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
