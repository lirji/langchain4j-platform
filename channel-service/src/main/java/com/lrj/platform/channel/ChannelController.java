package com.lrj.platform.channel;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.channel.ChannelCallbackRequest;
import com.lrj.platform.protocol.channel.ChannelEvent;
import com.lrj.platform.protocol.channel.ChannelInboundEvent;
import com.lrj.platform.protocol.channel.ChannelMessageReply;
import com.lrj.platform.protocol.channel.ChannelMessageRequest;
import com.lrj.platform.security.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class ChannelController {

    private final AuditLogger audit;
    private final ChannelMessageDispatcher dispatcher;
    private final ChannelSignatureVerifier signatureVerifier;
    private final ChannelEventPublisher eventPublisher;

    public ChannelController(AuditLogger audit,
                             ChannelMessageDispatcher dispatcher,
                             ChannelSignatureVerifier signatureVerifier,
                             ChannelEventPublisher eventPublisher) {
        this.audit = audit;
        this.dispatcher = dispatcher;
        this.signatureVerifier = signatureVerifier;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping("/channel/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of(
                "service", "channel-service",
                "tenantId", TenantContext.current().tenantId(),
                "channels", List.of("feishu", "voice", "webhook"),
                "status", "webhook-feishu-and-voice-ready");
    }

    @PostMapping("/channel/messages")
    public ResponseEntity<?> send(@RequestBody ChannelMessageRequest request) {
        return acceptMessage(request);
    }

    @PostMapping("/channel/callbacks")
    public ResponseEntity<?> callback(@RequestBody ChannelCallbackRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "callback request is required"));
        }
        return acceptMessage(new ChannelMessageRequest(
                request.channel(),
                request.target(),
                callbackMessage(request),
                callbackMetadata(request)));
    }

    @PostMapping("/channel/callbacks/async-task")
    public ResponseEntity<?> asyncTaskCallback(@RequestBody Map<String, Object> payload,
                                               @RequestHeader(value = "X-Async-Task-Id", required = false) String taskId,
                                               @RequestHeader(value = "X-Async-Task-Status", required = false) String status) {
        return callback(ChannelCallbackMapper.fromPayload("async-task", payload, taskId, status));
    }

    @PostMapping("/channel/callbacks/workflow")
    public ResponseEntity<?> workflowCallback(@RequestBody Map<String, Object> payload,
                                              @RequestHeader(value = "X-Workflow-Instance-Id", required = false) String instanceId,
                                              @RequestHeader(value = "X-Workflow-Status", required = false) String status) {
        return callback(ChannelCallbackMapper.fromPayload("workflow", payload, instanceId, status));
    }

    private ResponseEntity<?> acceptMessage(ChannelMessageRequest request) {
        if (request == null || blank(request.channel()) || blank(request.target()) || blank(request.message())) {
            return ResponseEntity.badRequest().body(Map.of("error", "channel, target and message are required"));
        }
        String messageId = UUID.randomUUID().toString();
        ChannelDeliveryResult delivery = dispatcher.dispatch(messageId, request);
        audit.record(AuditEventType.CHANNEL_MESSAGE_ACCEPTED,
                Map.of("messageId", messageId, "channel", request.channel(), "target", request.target(), "status", delivery.status()));
        eventPublisher.publish(new ChannelEvent(
                messageId,
                "channel.message.accepted",
                TenantContext.current().tenantId(),
                request.channel().trim(),
                request.target().trim(),
                delivery.status(),
                delivery.detail(),
                request.metadata(),
                Instant.now()));
        return ResponseEntity.accepted().body(new ChannelMessageReply(
                messageId,
                request.channel().trim(),
                request.target().trim(),
                delivery.status(),
                Instant.now()));
    }

    @PostMapping("/channel/inbound")
    public ResponseEntity<?> inbound(@RequestBody ChannelInboundEvent event,
                                     @RequestHeader(value = "X-Channel-Signature", required = false) String signature) {
        if (event == null || blank(event.channel()) || blank(event.eventType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "channel and eventType are required"));
        }
        if (!signatureVerifier.verify(event, signature)) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid channel signature"));
        }
        String eventId = value(event.eventId());
        audit.record(AuditEventType.CHANNEL_EVENT_RECEIVED,
                Map.of("eventId", eventId, "channel", event.channel(), "eventType", event.eventType()));
        eventPublisher.publish(new ChannelEvent(
                eventId,
                event.eventType(),
                TenantContext.current().tenantId(),
                event.channel(),
                event.source(),
                "ACCEPTED",
                "inbound event accepted",
                event.payload(),
                Instant.now()));
        return ResponseEntity.accepted().body(Map.of(
                "eventId", eventId,
                "status", "ACCEPTED",
                "receivedAt", Instant.now().toString()));
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
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(request.metadata());
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
}
