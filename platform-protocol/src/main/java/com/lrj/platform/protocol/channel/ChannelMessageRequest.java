package com.lrj.platform.protocol.channel;

import java.util.Map;

/**
 * 渠道出站消息投递请求（{@code POST /channel/**}）：指定目标渠道 {@code channel}、接收方
 * {@code target}、消息正文 {@code message} 与扩展 {@code metadata}。
 */
public record ChannelMessageRequest(String channel,
                                    String target,
                                    String message,
                                    Map<String, Object> metadata) {

    public ChannelMessageRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
