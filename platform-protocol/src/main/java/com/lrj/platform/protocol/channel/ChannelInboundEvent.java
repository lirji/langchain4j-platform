package com.lrj.platform.protocol.channel;

import java.time.Instant;
import java.util.Map;

public record ChannelInboundEvent(String eventId,
                                  String channel,
                                  String source,
                                  String eventType,
                                  Map<String, Object> payload,
                                  Instant receivedAt) {

    public ChannelInboundEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
