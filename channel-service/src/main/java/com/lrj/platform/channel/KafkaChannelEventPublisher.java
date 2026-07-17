package com.lrj.platform.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.channel.ChannelEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link ChannelEventPublisher} 的 Kafka 实现：把 {@link ChannelEvent} 序列化为 JSON，以 eventId 为 key
 * 发往 {@code app.channel.events-topic} 配置的主题。仅当类路径存在 {@code KafkaTemplate} 且
 * {@code app.channel.events-enabled=true} 时装配，否则使用默认（内存/noop）实现。
 */
@Component
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "app.channel", name = "events-enabled", havingValue = "true")
public class KafkaChannelEventPublisher implements ChannelEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ChannelProperties properties;

    public KafkaChannelEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                      ObjectMapper objectMapper,
                                      ChannelProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(ChannelEvent event) {
        try {
            kafkaTemplate.send(properties.getEventsTopic(), event.eventId(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize channel event", ex);
        }
    }
}
