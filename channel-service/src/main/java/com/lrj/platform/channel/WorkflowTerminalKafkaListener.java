package com.lrj.platform.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.eventbus.ProcessedEventStore;
import com.lrj.platform.protocol.channel.ChannelCallbackRequest;
import com.lrj.platform.protocol.event.EventTopics;
import com.lrj.platform.protocol.event.WorkflowTerminalMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B1b：消费 {@link EventTopics#WORKFLOW_TERMINAL} 的工作流终态事件，去重后经 {@link ChannelCallbackService}
 * 回推——与 HTTP {@code /channel/callbacks/workflow} 端点走同一 accept 逻辑，双通道幂等一致。
 *
 * <p>仅 {@code platform.eventbus.enabled=true} 时装配（默认 Noop，不加载任何 Kafka 监听容器）。
 * 即便生产侧已是原生事务 exactly-once，仍用 {@link ProcessedEventStore} 按 eventId 二次去重兜底（重启/重投）。
 */
@Component
@ConditionalOnProperty(prefix = "platform.eventbus", name = "enabled", havingValue = "true")
public class WorkflowTerminalKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTerminalKafkaListener.class);

    private final ChannelCallbackService callbackService;
    private final ProcessedEventStore processedEvents;
    private final ObjectMapper objectMapper;

    public WorkflowTerminalKafkaListener(ChannelCallbackService callbackService,
                                         ProcessedEventStore processedEvents,
                                         ObjectMapper objectMapper) {
        this.callbackService = callbackService;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = EventTopics.WORKFLOW_TERMINAL,
            groupId = "${platform.eventbus.consumer.group-id:channel-service}",
            containerFactory = "eventbusKafkaListenerContainerFactory")
    public void onMessage(String payload) throws Exception {
        WorkflowTerminalMessage message = objectMapper.readValue(payload, WorkflowTerminalMessage.class);
        handle(message);
    }

    /**
     * 去重 + 回推（抽出便于纯 POJO 单测，不经 Kafka）。顺序：先查 → 回推 → 成功后标记。
     * 回推抛异常时未标记 → 消息重投会再次处理（不丢）；已完成事件重投时被 isProcessed 跳过（去重）。
     * 同一 eventId 恒落同一分区、单分区消费串行，故此「查后标记」无并发竞态。
     */
    void handle(WorkflowTerminalMessage message) {
        if (processedEvents.isProcessed(message.eventId())) {
            log.debug("workflow terminal event deduplicated eventId={}", message.eventId());
            return;
        }
        callbackService.handleCallback(toCallback(message), message.tenantId());
        processedEvents.markProcessed(message.eventId());
    }

    /** 映射为回调请求；channel/target 从 chatId 前缀（如 {@code feishu:<open_id>}）解析，message = reply。 */
    static ChannelCallbackRequest toCallback(WorkflowTerminalMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", message.instanceId());
        if (message.status() != null) {
            body.put("status", message.status());
        }
        if (message.reply() != null) {
            body.put("message", message.reply());
        }
        String chatId = message.chatId();
        if (chatId != null && chatId.contains(":")) {
            int idx = chatId.indexOf(':');
            body.put("channel", chatId.substring(0, idx));
            body.put("target", chatId.substring(idx + 1));
        }
        return ChannelCallbackMapper.fromPayload("workflow", body, message.instanceId(), message.status());
    }
}
