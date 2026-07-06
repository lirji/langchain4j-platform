package com.lrj.platform.channel;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.channel.ChannelInboundEvent;
import com.lrj.platform.protocol.channel.ChannelMessageReply;
import com.lrj.platform.protocol.channel.ChannelMessageRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChannelControllerTest {

    @Test
    void acceptsOutboundMessage() {
        ChannelController controller = controller(ChannelDeliveryResult.accepted("test"), true);

        var response = controller.send(new ChannelMessageRequest("feishu", "chat-1", "hello", Map.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isInstanceOf(ChannelMessageReply.class);
        ChannelMessageReply body = (ChannelMessageReply) response.getBody();
        assertThat(body.channel()).isEqualTo("feishu");
        assertThat(body.status()).isEqualTo("ACCEPTED");
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

    private ChannelController controller(ChannelDeliveryResult result, boolean signatureValid) {
        return new ChannelController(
                mock(AuditLogger.class),
                (messageId, request) -> result,
                new TestSignatureVerifier(signatureValid));
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
}
