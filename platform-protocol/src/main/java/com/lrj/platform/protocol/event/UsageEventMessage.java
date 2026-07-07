package com.lrj.platform.protocol.event;

import java.time.Instant;

/**
 * 用量/计费事件（fire-and-forget，可丢）。发往 {@link EventTopics#METERING_USAGE}，key = tenantId。
 */
public record UsageEventMessage(String eventId,
                                String tenantId,
                                String model,
                                String provider,
                                long inputTokens,
                                long outputTokens,
                                double costUsd,
                                Instant occurredAt,
                                String traceId) {

    /** 当前契约 schema 版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public UsageEventMessage {
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
