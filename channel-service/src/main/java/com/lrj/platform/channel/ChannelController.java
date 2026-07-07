package com.lrj.platform.channel;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.channel.ChannelCallbackRequest;
import com.lrj.platform.protocol.channel.ChannelInboundEvent;
import com.lrj.platform.protocol.channel.ChannelMessageRequest;
import com.lrj.platform.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
public class ChannelController {

    private final ChannelCallbackService callbackService;
    private final ChannelSignatureVerifier signatureVerifier;

    @Autowired
    public ChannelController(ChannelCallbackService callbackService,
                             ChannelSignatureVerifier signatureVerifier) {
        this.callbackService = callbackService;
        this.signatureVerifier = signatureVerifier;
    }

    /** 兼容构造（既有单测直接注入协作者）：内部组装 {@link ChannelCallbackService}，行为等价。 */
    public ChannelController(AuditLogger audit,
                             ChannelMessageDispatcher dispatcher,
                             ChannelSignatureVerifier signatureVerifier,
                             ChannelEventPublisher eventPublisher) {
        this(new ChannelCallbackService(audit, dispatcher, eventPublisher), signatureVerifier);
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
        return toResponse(callbackService.accept(request, TenantContext.current().tenantId()));
    }

    @PostMapping("/channel/callbacks")
    public ResponseEntity<?> callback(@RequestBody ChannelCallbackRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "callback request is required"));
        }
        return toResponse(callbackService.handleCallback(request, TenantContext.current().tenantId()));
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

    @PostMapping("/channel/inbound")
    public ResponseEntity<?> inbound(@RequestBody ChannelInboundEvent event,
                                     @RequestHeader(value = "X-Channel-Signature", required = false) String signature) {
        if (event == null || blank(event.channel()) || blank(event.eventType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "channel and eventType are required"));
        }
        if (!signatureVerifier.verify(event, signature)) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid channel signature"));
        }
        String eventId = callbackService.publishInbound(event, TenantContext.current().tenantId());
        return ResponseEntity.accepted().body(Map.of(
                "eventId", eventId,
                "status", "ACCEPTED",
                "receivedAt", Instant.now().toString()));
    }

    private static ResponseEntity<?> toResponse(ChannelCallbackService.Result result) {
        if (!result.accepted()) {
            return ResponseEntity.badRequest().body(Map.of("error", "channel, target and message are required"));
        }
        return ResponseEntity.accepted().body(result.reply());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
