package com.lrj.platform.protocol.channel;

import java.time.Instant;

/**
 * 渠道出站消息投递的受理响应（{@code POST /channel/**}）：回带生成的 {@code messageId}、
 * 渠道、目标、受理状态与受理时间 {@code acceptedAt}。
 */
public record ChannelMessageReply(String messageId,
                                  String channel,
                                  String target,
                                  String status,
                                  Instant acceptedAt) {
}
