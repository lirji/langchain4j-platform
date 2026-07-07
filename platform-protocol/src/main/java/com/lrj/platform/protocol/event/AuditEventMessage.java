package com.lrj.platform.protocol.event;

import java.time.Instant;
import java.util.Map;

/**
 * 审计事件（fire-and-forget，可丢）。发往 {@link EventTopics#AUDIT_EVENTS}，key = tenantId。
 */
public record AuditEventMessage(String eventId,
                                String tenantId,
                                String userId,
                                String type,
                                String traceId,
                                Instant occurredAt,
                                Map<String, Object> fields) {

    /** 当前契约 schema 版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AuditEventMessage {
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
