package com.lrj.platform.protocol.channel;

import java.util.Map;

public record ChannelMessageRequest(String channel,
                                    String target,
                                    String message,
                                    Map<String, Object> metadata) {

    public ChannelMessageRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
