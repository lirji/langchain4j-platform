package com.lrj.platform.eventbus;

/**
 * 事件总线发布 SPI。默认 {@link NoopEventPublisher}（零依赖，仅 debug log）；
 * 开启 {@code platform.eventbus.enabled=true} 且 classpath 有 Kafka 时切 {@link KafkaEventPublisher}。
 */
public interface EventPublisher {

    /**
     * 发布一条事件。
     *
     * @param topic   目标 topic（见 {@link com.lrj.platform.protocol.event.EventTopics}）
     * @param key     分区 key，约定为 tenantId（保证同租户有序）
     * @param payload 事件负载（不可变 record，序列化为 JSON）
     */
    void publish(String topic, String key, Object payload);
}
