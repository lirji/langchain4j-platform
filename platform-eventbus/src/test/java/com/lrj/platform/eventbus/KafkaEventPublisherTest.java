package com.lrj.platform.eventbus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lrj.platform.protocol.event.EventTopics;
import com.lrj.platform.protocol.event.WorkflowTerminalMessage;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KafkaEventPublisherTest：验证 {@link KafkaEventPublisher#publish} 将事件序列化为 JSON、以 tenantId 为 key
 * 发到约定主题，并同步等待 broker ack（{@code send().get()}）；broker 失败时向上抛异常，以便中继层重试。
 */
class KafkaEventPublisherTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private WorkflowTerminalMessage msg() {
        return new WorkflowTerminalMessage(
                "evt-1", WorkflowTerminalMessage.CURRENT_SCHEMA_VERSION, "acme", "inst-9", "chat-3",
                "APPROVED", "COMPLETED", "refund approved",
                Instant.parse("2026-07-07T10:00:00Z"), "trace-abc");
    }

    @Test
    void publishesJsonToTopicKeyedByTenantId_afterBrokerAck() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        // 同步发布：send().get() 需返回已完成 future（模拟 broker 已 ack）
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));
        KafkaEventPublisher publisher = new KafkaEventPublisher(kafkaTemplate, mapper, Duration.ofSeconds(5));

        WorkflowTerminalMessage msg = msg();
        publisher.publish(EventTopics.WORKFLOW_TERMINAL, msg.tenantId(), msg);

        verify(kafkaTemplate).send(eq("platform.workflow.terminal"), eq("acme"), contains("\"eventId\":\"evt-1\""));
        verify(kafkaTemplate).send(eq("platform.workflow.terminal"), eq("acme"), contains("\"outcome\":\"APPROVED\""));
    }

    @Test
    void publishFailure_isPropagatedSoRelayCanRetry() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);
        KafkaEventPublisher publisher = new KafkaEventPublisher(kafkaTemplate, mapper, Duration.ofSeconds(5));

        assertThatThrownBy(() -> publisher.publish(EventTopics.WORKFLOW_TERMINAL, "acme", msg()))
                .isInstanceOf(IllegalStateException.class);
    }
}
