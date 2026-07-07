package com.lrj.platform.eventbus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka 事件发布实现。JSON 序列化后按 key=tenantId 发往目标 topic。
 * 仅当 {@code platform.eventbus.enabled=true} 且 classpath 有 {@link KafkaTemplate} 时装配
 * （见 {@link PlatformEventbusAutoConfiguration}），风格参考 channel-service 的 KafkaChannelEventPublisher。
 */
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(String topic, String key, Object payload) {
        try {
            kafkaTemplate.send(topic, key, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize eventbus payload for topic " + topic, ex);
        }
    }
}
