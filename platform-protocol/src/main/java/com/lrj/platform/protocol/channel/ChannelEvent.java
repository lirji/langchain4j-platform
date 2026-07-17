package com.lrj.platform.protocol.channel;

import java.time.Instant;
import java.util.Map;

/**
 * 渠道领域的出站/生命周期事件：承载事件 id 与类型、租户、渠道与目标、状态与详情、扩展 {@code payload}
 * 及发生时间 {@code occurredAt}（缺省取当前时刻）。经 {@code platform-eventbus} 发布/消费。
 */
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
