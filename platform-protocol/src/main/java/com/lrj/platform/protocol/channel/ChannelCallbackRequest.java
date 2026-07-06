package com.lrj.platform.protocol.channel;

import java.util.Map;

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
