package com.lrj.platform.eventbus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka 事件发布实现。JSON 序列化后按 key=tenantId 发往目标 topic。
 * 仅当 {@code platform.eventbus.enabled=true} 且 classpath 有 {@link KafkaTemplate} 时装配
 * （见 {@link PlatformEventbusAutoConfiguration}），风格参考 channel-service 的 KafkaChannelEventPublisher。
 *
 * <p><b>同步发布</b>：publish() 阻塞至 broker ack（acks=all + 幂等生产者）后才返回。供 outbox relay
 * 「先确认落 broker 再 markDelivered」——避免异步 fire-and-forget 把未确认的发送误标为已投递。
 * 序列化/发送失败或超时抛 {@link IllegalStateException}，由 relay 按退避重投（配合消费侧 eventId 去重 = effective exactly-once）。
 */
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Duration sendTimeout;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                               Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void publish(String topic, String key, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize eventbus payload for topic " + topic, ex);
        }
        try {
            // 阻塞等待 broker 确认（acks=all）；超时/失败 → 抛出交由 relay 重投。
            kafkaTemplate.send(topic, key, json).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing to topic " + topic, ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("failed to publish to topic " + topic + " within " + sendTimeout, ex);
        }
    }
}
