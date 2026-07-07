package com.lrj.platform.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lrj.platform.eventbus.InMemoryProcessedEventStore;
import com.lrj.platform.protocol.channel.ChannelCallbackRequest;
import com.lrj.platform.protocol.event.AsyncTaskLifecycleMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 纯 POJO：异步任务生命周期监听器把消息（result 里带 channel/target/reply）映射为回调请求并调
 * {@link ChannelCallbackService}，并对同一 eventId 去重。
 */
class AsyncTaskLifecycleKafkaListenerTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static AsyncTaskLifecycleMessage message() {
        return new AsyncTaskLifecycleMessage("asynctask:task-7:SUCCEEDED", 1, "acme", "task-7",
                "workflow.terminal", "SUCCEEDED",
                Map.of("channel", "feishu", "target", "u1", "reply", "all done"),
                null, "https://cb.local/x", Instant.now(), "trace-2");
    }

    @Test
    void mapsResultToCallbackAndDispatches() {
        ChannelCallbackService callbackService = mock(ChannelCallbackService.class);
        AsyncTaskLifecycleKafkaListener listener =
                new AsyncTaskLifecycleKafkaListener(callbackService, new InMemoryProcessedEventStore(), mapper);

        listener.handle(message());

        ArgumentCaptor<ChannelCallbackRequest> req = ArgumentCaptor.forClass(ChannelCallbackRequest.class);
        verify(callbackService).handleCallback(req.capture(), eq("acme"));
        assertThat(req.getValue().channel()).isEqualTo("feishu");
        assertThat(req.getValue().target()).isEqualTo("u1");
        assertThat(req.getValue().message()).isEqualTo("all done");
        assertThat(req.getValue().sourceId()).isEqualTo("task-7");
        assertThat(req.getValue().status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void deduplicatesSameEventId() {
        ChannelCallbackService callbackService = mock(ChannelCallbackService.class);
        AsyncTaskLifecycleKafkaListener listener =
                new AsyncTaskLifecycleKafkaListener(callbackService, new InMemoryProcessedEventStore(), mapper);

        listener.handle(message());
        listener.handle(message());

        verify(callbackService, times(1)).handleCallback(org.mockito.ArgumentMatchers.any(), eq("acme"));
    }

    @Test
    void deserializesJsonPayload() throws Exception {
        ChannelCallbackService callbackService = mock(ChannelCallbackService.class);
        AsyncTaskLifecycleKafkaListener listener =
                new AsyncTaskLifecycleKafkaListener(callbackService, new InMemoryProcessedEventStore(), mapper);

        listener.onMessage(mapper.writeValueAsString(message()));

        verify(callbackService).handleCallback(org.mockito.ArgumentMatchers.any(), eq("acme"));
    }
}
