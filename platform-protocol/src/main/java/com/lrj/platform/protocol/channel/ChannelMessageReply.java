package com.lrj.platform.protocol.channel;

import java.time.Instant;

public record ChannelMessageReply(String messageId,
                                  String channel,
                                  String target,
                                  String status,
                                  Instant acceptedAt) {
}
