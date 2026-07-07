package com.lrj.platform.eventbus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lrj.platform.protocol.event.EventTopics;
import com.lrj.platform.protocol.event.WorkflowTerminalMessage;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaEventPublisherTest {

    @Test
    void publishesJsonToTopicKeyedByTenantId() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        KafkaEventPublisher publisher = new KafkaEventPublisher(kafkaTemplate, mapper);

        WorkflowTerminalMessage msg = new WorkflowTerminalMessage(
                "evt-1",
                WorkflowTerminalMessage.CURRENT_SCHEMA_VERSION,
                "acme",
                "inst-9",
                "chat-3",
                "APPROVED",
                "COMPLETED",
                "refund approved",
                Instant.parse("2026-07-07T10:00:00Z"),
                "trace-abc");

        publisher.publish(EventTopics.WORKFLOW_TERMINAL, msg.tenantId(), msg);

        verify(kafkaTemplate).send(
                eq("platform.workflow.terminal"),
                eq("acme"),
                contains("\"eventId\":\"evt-1\""));
        verify(kafkaTemplate).send(
                eq("platform.workflow.terminal"),
                eq("acme"),
                contains("\"outcome\":\"APPROVED\""));
    }
}
