package com.lrj.platform.protocol.channel;

import java.util.Map;

/**
 * 外部渠道对已投递消息的回执/回调请求（channel-service 接收）：携带来源标识
 * ({@code source}/{@code sourceId})、投递状态、渠道与目标、原始消息及扩展 {@code metadata}。
 */
public record ChannelCallbackRequest(String source,
                                     String sourceId,
                                     String status,
                                     String channel,
                                     String target,
                                     String message,
                                     Map<String, Object> metadata) {

    public ChannelCallbackRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
