package com.lrj.platform.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lrj.platform.eventbus.InMemoryProcessedEventStore;
import com.lrj.platform.protocol.channel.ChannelCallbackRequest;
import com.lrj.platform.protocol.event.WorkflowTerminalMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 纯 POJO：工作流终态监听器把消息映射为回调请求并调 {@link ChannelCallbackService}，
 * 且经 {@link InMemoryProcessedEventStore} 对同一 eventId 去重（二次投递 no-op）。
 */
class WorkflowTerminalKafkaListenerTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static WorkflowTerminalMessage message() {
        return new WorkflowTerminalMessage("workflow:inst-9", 1, "acme", "inst-9",
                "feishu:u1", "granted", "COMPLETED", "refund approved", Instant.now(), "trace-1");
    }

    @Test
    void mapsMessageToCallbackAndDispatches() {
        ChannelCallbackService callbackService = mock(ChannelCallbackService.class);
        WorkflowTerminalKafkaListener listener =
                new WorkflowTerminalKafkaListener(callbackService, new InMemoryProcessedEventStore(), mapper);

        listener.handle(message());

        ArgumentCaptor<ChannelCallbackRequest> req = ArgumentCaptor.forClass(ChannelCallbackRequest.class);
        verify(callbackService).handleCallback(req.capture(), eq("acme"));
        assertThat(req.getValue().channel()).isEqualTo("feishu");
        assertThat(req.getValue().target()).isEqualTo("u1");
        assertThat(req.getValue().message()).isEqualTo("refund approved");
        assertThat(req.getValue().status()).isEqualTo("COMPLETED");
        assertThat(req.getValue().sourceId()).isEqualTo("inst-9");
    }

    @Test
    void deduplicatesSameEventId() {
        ChannelCallbackService callbackService = mock(ChannelCallbackService.class);
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
        WorkflowTerminalKafkaListener listener =
                new WorkflowTerminalKafkaListener(callbackService, store, mapper);

        listener.handle(message());
        listener.handle(message());

        verify(callbackService, times(1)).handleCallback(org.mockito.ArgumentMatchers.any(), eq("acme"));
    }

    @Test
    void deserializesJsonPayload() throws Exception {
        ChannelCallbackService callbackService = mock(ChannelCallbackService.class);
        WorkflowTerminalKafkaListener listener =
                new WorkflowTerminalKafkaListener(callbackService, new InMemoryProcessedEventStore(), mapper);

        listener.onMessage(mapper.writeValueAsString(message()));

        verify(callbackService).handleCallback(org.mockito.ArgumentMatchers.any(), eq("acme"));
    }
}
