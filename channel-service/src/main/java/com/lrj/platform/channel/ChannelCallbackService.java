package com.lrj.platform.channel;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.channel.ChannelCallbackRequest;
import com.lrj.platform.protocol.channel.ChannelEvent;
import com.lrj.platform.protocol.channel.ChannelInboundEvent;
import com.lrj.platform.protocol.channel.ChannelMessageReply;
import com.lrj.platform.protocol.channel.ChannelMessageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 渠道回推的出站投递收口（从 {@link ChannelController} 抽出）。HTTP callback 端点与
 * B1b 的 Kafka 监听器（{@code WorkflowTerminalKafkaListener}/{@code AsyncTaskLifecycleKafkaListener}）
 * <b>汇入同一个 accept/handleCallback</b>，共用 dispatcher + 审计 + 事件发布，保证两条通道幂等一致。
 *
 * <p>不依赖 {@code TenantContext}：tenantId 由调用方显式传入（HTTP 路径传 {@code TenantContext.current()}，
 * Kafka 路径传事件里的 tenantId），因为 Kafka 消费线程没有过过滤器链、无租户上下文。
 */
@Service
public class ChannelCallbackService {

    private final AuditLogger audit;
    private final ChannelMessageDispatcher dispatcher;
    private final ChannelEventPublisher eventPublisher;

    public ChannelCallbackService(AuditLogger audit,
                                  ChannelMessageDispatcher dispatcher,
                                  ChannelEventPublisher eventPublisher) {
        this.audit = audit;
        this.dispatcher = dispatcher;
        this.eventPublisher = eventPublisher;
    }

    /** 把回调请求转成出站消息并投递（HTTP callback 与 Kafka 监听器共用）。 */
    public Result handleCallback(ChannelCallbackRequest request, String tenantId) {
        return accept(new ChannelMessageRequest(
                request.channel(),
                request.target(),
                callbackMessage(request),
                callbackMetadata(request)), tenantId);
    }

    /** 校验 + 出站投递 + 审计 + 事件发布。校验不过返回 {@link Result#rejected()}（调用方映射 400）。 */
    public Result accept(ChannelMessageRequest request, String tenantId) {
        if (request == null || blank(request.channel()) || blank(request.target()) || blank(request.message())) {
            return Result.rejected();
        }
        String messageId = UUID.randomUUID().toString();
        ChannelDeliveryResult delivery = dispatcher.dispatch(messageId, request);
        audit.record(AuditEventType.CHANNEL_MESSAGE_ACCEPTED, Map.of(
                "messageId", messageId,
                "channel", request.channel(),
                "target", request.target(),
                "status", delivery.status()));
        eventPublisher.publish(new ChannelEvent(
                messageId,
                "channel.message.accepted",
                tenantId,
                request.channel().trim(),
                request.target().trim(),
                delivery.status(),
                delivery.detail(),
                request.metadata(),
                Instant.now()));
        return Result.accepted(new ChannelMessageReply(
                messageId,
                request.channel().trim(),
                request.target().trim(),
                delivery.status(),
                Instant.now()));
    }

    /** 入站事件的审计 + 事件发布，返回实际使用的 eventId（供 controller 组织响应体）。 */
    public String publishInbound(ChannelInboundEvent event, String tenantId) {
        String eventId = value(event.eventId());
        audit.record(AuditEventType.CHANNEL_EVENT_RECEIVED, Map.of(
                "eventId", eventId,
                "channel", event.channel(),
                "eventType", event.eventType()));
        eventPublisher.publish(new ChannelEvent(
                eventId,
                event.eventType(),
                tenantId,
                event.channel(),
                event.source(),
                "ACCEPTED",
                "inbound event accepted",
                event.payload(),
                Instant.now()));
        return eventId;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String callbackMessage(ChannelCallbackRequest request) {
        if (!blank(request.message())) {
            return request.message();
        }
        return "%s %s %s".formatted(text(request.source()), text(request.sourceId()), text(request.status())).trim();
    }

    private static Map<String, Object> callbackMetadata(ChannelCallbackRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>(request.metadata());
        putIfPresent(metadata, "callbackSource", request.source());
        putIfPresent(metadata, "callbackSourceId", request.sourceId());
        putIfPresent(metadata, "callbackStatus", request.status());
        return metadata;
    }

    private static void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (!blank(value)) {
            metadata.put(key, value);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    /** accept/handleCallback 结果：接受则带回执，拒绝则空。 */
    public record Result(boolean accepted, ChannelMessageReply reply) {
        static Result rejected() {
            return new Result(false, null);
        }

        static Result accepted(ChannelMessageReply reply) {
            return new Result(true, reply);
        }
    }
}
