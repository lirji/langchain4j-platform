package com.lrj.platform.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lrj.platform.protocol.channel.ChannelEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * KafkaChannelEventPublisherTest：验证 {@link KafkaChannelEventPublisher} 将 {@link com.lrj.platform.protocol.channel.ChannelEvent}
 * 序列化为 JSON（含 eventType、tenantId 字段）并以 eventId 为 key 发往配置的 topic。
 */
class KafkaChannelEventPublisherTest {

    @Test
    void publishesJsonEventToConfiguredTopic() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        ChannelProperties properties = new ChannelProperties();
        properties.setEventsTopic("channel.events");
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        KafkaChannelEventPublisher publisher = new KafkaChannelEventPublisher(kafkaTemplate, objectMapper, properties);

        publisher.publish(new ChannelEvent(
                "event-1",
                "channel.message.accepted",
                "acme",
                "webhook",
                "http://callback.local",
                "SENT",
                "webhook delivered",
                Map.of("source", "test"),
                Instant.parse("2026-07-06T10:00:00Z")));

        verify(kafkaTemplate).send(
                eq("channel.events"),
                eq("event-1"),
                contains("\"eventType\":\"channel.message.accepted\""));
        verify(kafkaTemplate).send(
                eq("channel.events"),
                eq("event-1"),
                contains("\"tenantId\":\"acme\""));
    }
}
