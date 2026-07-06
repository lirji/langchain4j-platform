package com.lrj.platform.channel;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.channel.ChannelEvent;
import com.lrj.platform.protocol.channel.ChannelInboundEvent;
import com.lrj.platform.protocol.channel.ChannelMessageReply;
import com.lrj.platform.protocol.channel.ChannelMessageRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChannelControllerTest {

    @Test
    void acceptsOutboundMessage() {
        CapturingEventPublisher eventPublisher = new CapturingEventPublisher();
        ChannelController controller = controller(ChannelDeliveryResult.accepted("test"), true, eventPublisher);

        var response = controller.send(new ChannelMessageRequest("feishu", "chat-1", "hello", Map.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isInstanceOf(ChannelMessageReply.class);
        ChannelMessageReply body = (ChannelMessageReply) response.getBody();
        assertThat(body.channel()).isEqualTo("feishu");
        assertThat(body.status()).isEqualTo("ACCEPTED");
        assertThat(eventPublisher.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo("channel.message.accepted");
                    assertThat(event.channel()).isEqualTo("feishu");
                    assertThat(event.target()).isEqualTo("chat-1");
                    assertThat(event.status()).isEqualTo("ACCEPTED");
                });
    }

    @Test
    void rejectsInvalidMessage() {
        ChannelController controller = controller(ChannelDeliveryResult.accepted("test"), true);

        var response = controller.send(new ChannelMessageRequest("", "chat-1", "hello", Map.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void usesDispatcherStatus() {
        ChannelController controller = controller(ChannelDeliveryResult.sent("delivered"), true);

        var response = controller.send(new ChannelMessageRequest("webhook", "http://callback.local/messages", "hello", Map.of()));

        ChannelMessageReply body = (ChannelMessageReply) response.getBody();
        assertThat(body.status()).isEqualTo("SENT");
    }

    @Test
    void rejectsInvalidInboundSignature() {
        ChannelController controller = controller(ChannelDeliveryResult.accepted("test"), false);

        var response = controller.inbound(new ChannelInboundEvent(
                "event-1", "webhook", "sender", "message.created", Map.of(), Instant.now()), "bad");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void publishesInboundEvent() {
        CapturingEventPublisher eventPublisher = new CapturingEventPublisher();
        ChannelController controller = controller(ChannelDeliveryResult.accepted("test"), true, eventPublisher);

        var response = controller.inbound(new ChannelInboundEvent(
                "event-1", "webhook", "sender", "message.created", Map.of("text", "hello"), Instant.now()), null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(eventPublisher.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventId()).isEqualTo("event-1");
                    assertThat(event.eventType()).isEqualTo("message.created");
                    assertThat(event.channel()).isEqualTo("webhook");
                    assertThat(event.target()).isEqualTo("sender");
                    assertThat(event.status()).isEqualTo("ACCEPTED");
                    assertThat(event.payload()).containsEntry("text", "hello");
                });
    }

    private ChannelController controller(ChannelDeliveryResult result, boolean signatureValid) {
        return controller(result, signatureValid, event -> {
        });
    }

    private ChannelController controller(ChannelDeliveryResult result,
                                         boolean signatureValid,
                                         ChannelEventPublisher eventPublisher) {
        return new ChannelController(
                mock(AuditLogger.class),
                (messageId, request) -> result,
                new TestSignatureVerifier(signatureValid),
                eventPublisher);
    }

    private static class TestSignatureVerifier extends ChannelSignatureVerifier {

        private final boolean valid;

        TestSignatureVerifier(boolean valid) {
            super(new ChannelProperties());
            this.valid = valid;
        }

        @Override
        public boolean verify(ChannelInboundEvent event, String signatureHeader) {
            return valid;
        }
    }

    private static class CapturingEventPublisher implements ChannelEventPublisher {

        private final List<ChannelEvent> events = new ArrayList<>();

        @Override
        public void publish(ChannelEvent event) {
            events.add(event);
        }

        List<ChannelEvent> events() {
            return events;
        }
    }
}
