package com.lrj.platform.channel;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
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

    public ChannelController(AuditLogger audit,
                             ChannelMessageDispatcher dispatcher,
                             ChannelSignatureVerifier signatureVerifier) {
        this.audit = audit;
        this.dispatcher = dispatcher;
        this.signatureVerifier = signatureVerifier;
    }

    @GetMapping("/channel/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of(
                "service", "channel-service",
                "tenantId", TenantContext.current().tenantId(),
                "channels", List.of("feishu", "voice", "webhook"),
                "status", "webhook-and-feishu-ready");
    }

    @PostMapping("/channel/messages")
    public ResponseEntity<?> send(@RequestBody ChannelMessageRequest request) {
        if (request == null || blank(request.channel()) || blank(request.target()) || blank(request.message())) {
            return ResponseEntity.badRequest().body(Map.of("error", "channel, target and message are required"));
        }
        String messageId = UUID.randomUUID().toString();
        ChannelDeliveryResult delivery = dispatcher.dispatch(messageId, request);
        audit.record(AuditEventType.CHANNEL_MESSAGE_ACCEPTED,
                Map.of("messageId", messageId, "channel", request.channel(), "target", request.target(), "status", delivery.status()));
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
        return ResponseEntity.accepted().body(Map.of(
                "eventId", eventId,
                "status", "ACCEPTED",
                "receivedAt", Instant.now().toString()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
