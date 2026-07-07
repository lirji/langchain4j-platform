package com.lrj.platform.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.eventbus.ProcessedEventStore;
import com.lrj.platform.protocol.channel.ChannelCallbackRequest;
import com.lrj.platform.protocol.event.AsyncTaskLifecycleMessage;
import com.lrj.platform.protocol.event.EventTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B1b：消费 {@link EventTopics#ASYNCTASK_LIFECYCLE} 的异步任务生命周期事件，去重后经
 * {@link ChannelCallbackService} 回推——与 HTTP {@code /channel/callbacks/async-task} 端点同一 accept 逻辑。
 *
 * <p>仅 {@code platform.eventbus.enabled=true} 时装配；{@link ProcessedEventStore} 按 eventId 去重兜底。
 */
@Component
@ConditionalOnProperty(prefix = "platform.eventbus", name = "enabled", havingValue = "true")
public class AsyncTaskLifecycleKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskLifecycleKafkaListener.class);

    private final ChannelCallbackService callbackService;
    private final ProcessedEventStore processedEvents;
    private final ObjectMapper objectMapper;

    public AsyncTaskLifecycleKafkaListener(ChannelCallbackService callbackService,
                                           ProcessedEventStore processedEvents,
                                           ObjectMapper objectMapper) {
        this.callbackService = callbackService;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = EventTopics.ASYNCTASK_LIFECYCLE,
            groupId = "${platform.eventbus.consumer.group-id:channel-service}",
            containerFactory = "eventbusKafkaListenerContainerFactory")
    public void onMessage(String payload) throws Exception {
        AsyncTaskLifecycleMessage message = objectMapper.readValue(payload, AsyncTaskLifecycleMessage.class);
        handle(message);
    }

    /**
     * 去重 + 回推（抽出便于纯 POJO 单测，不经 Kafka）。顺序：先查 → 回推 → 成功后标记。
     * 回推抛异常时未标记 → 重投再处理（不丢）；已完成事件重投被 isProcessed 跳过（去重）。
     */
    void handle(AsyncTaskLifecycleMessage message) {
        if (processedEvents.isProcessed(message.eventId())) {
            log.debug("async task lifecycle event deduplicated eventId={}", message.eventId());
            return;
        }
        callbackService.handleCallback(toCallback(message), message.tenantId());
        processedEvents.markProcessed(message.eventId());
    }

    /** 映射为回调请求；沿用 async-task HTTP 回调结构（result 里带 channel/target/reply）。 */
    static ChannelCallbackRequest toCallback(AsyncTaskLifecycleMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", message.taskId());
        if (message.status() != null) {
            body.put("status", message.status());
        }
        if (message.result() != null) {
            body.put("result", message.result());
        }
        return ChannelCallbackMapper.fromPayload("async-task", body, message.taskId(), message.status());
    }
}
