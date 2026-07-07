package com.lrj.platform.protocol.event;

import java.time.Instant;

/**
 * 工作流终态事件（一 topic 一固定类型，带 schemaVersion，避免多态反序列化）。
 * 发往 {@link EventTopics#WORKFLOW_TERMINAL}，key = tenantId。
 */
public record WorkflowTerminalMessage(String eventId,
                                      int schemaVersion,
                                      String tenantId,
                                      String instanceId,
                                      String chatId,
                                      String outcome,
                                      String status,
                                      String reply,
                                      Instant occurredAt,
                                      String traceId) {

    /** 当前契约 schema 版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WorkflowTerminalMessage {
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
