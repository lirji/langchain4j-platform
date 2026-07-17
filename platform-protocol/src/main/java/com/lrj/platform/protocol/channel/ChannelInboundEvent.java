package com.lrj.platform.protocol.channel;

import java.time.Instant;
import java.util.Map;

/**
 * 从外部渠道（如钉钉/飞书）入站桥接进来的原始事件：携带事件 id、渠道、来源、事件类型、
 * 原始 {@code payload} 与接收时间 {@code receivedAt}，供 channel-service 归一化处理。
 */
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
