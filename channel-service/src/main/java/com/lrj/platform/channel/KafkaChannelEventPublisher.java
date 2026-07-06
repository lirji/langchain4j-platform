package com.lrj.platform.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.channel.ChannelEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

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
