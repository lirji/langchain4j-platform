package com.lrj.platform.protocol.channel;

import java.time.Instant;
import java.util.Map;

public record ChannelEvent(String eventId,
                           String eventType,
                           String tenantId,
                           String channel,
                           String target,
                           String status,
                           String detail,
                           Map<String, Object> payload,
                           Instant occurredAt) {

    public ChannelEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
