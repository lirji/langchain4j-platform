package com.lrj.platform.channel;

import com.lrj.platform.protocol.channel.ChannelEvent;

/**
 * 渠道事件发布抽象：把 {@link ChannelEvent}（消息受理、入站事件等）投递到事件总线。
 * 遵循本仓库「接口 + 可插拔实现」约定，默认多为 noop，{@link KafkaChannelEventPublisher}
 * 是由 {@code app.channel.events-enabled} 开启的 Kafka 变体。
 */
public interface ChannelEventPublisher {

    void publish(ChannelEvent event);
}
