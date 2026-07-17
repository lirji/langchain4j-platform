package com.lrj.platform.channel;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.channel.ChannelCallbackRequest;
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

/**
 * ChannelControllerTest：验证 {@link ChannelController} 出站受理返回 202 并发布 channel.message.accepted 事件、
 * 非法消息/缺字段回调返回 400、入站事件验签失败返回 401、合法入站事件发布 message.created 事件，
 * 以及 async-task 回调经 {@link ChannelCallbackMapper} 正确映射为渠道消息（含 callbackSource/SourceId/Status 元数据）。
 */
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

    @Test
    void mapsAsyncTaskCallbackToChannelMessage() {
        CapturingDispatcher dispatcher = new CapturingDispatcher(ChannelDeliveryResult.sent("sent"));
        CapturingEventPublisher eventPublisher = new CapturingEventPublisher();
        ChannelController controller = controller(dispatcher, true, eventPublisher);

        var response = controller.asyncTaskCallback(Map.of(
                "result", Map.of(
                        "channel", "feishu",
                        "target", "chat-1",
                        "message", "workflow done")),
                "task-1",
                "SUCCEEDED");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(dispatcher.lastRequest().channel()).isEqualTo("feishu");
        assertThat(dispatcher.lastRequest().target()).isEqualTo("chat-1");
        assertThat(dispatcher.lastRequest().message()).isEqualTo("workflow done");
        assertThat(dispatcher.lastRequest().metadata()).containsEntry("callbackSource", "async-task");
        assertThat(dispatcher.lastRequest().metadata()).containsEntry("callbackSourceId", "task-1");
        assertThat(dispatcher.lastRequest().metadata()).containsEntry("callbackStatus", "SUCCEEDED");
        assertThat(eventPublisher.events()).singleElement()
                .satisfies(event -> assertThat(event.eventType()).isEqualTo("channel.message.accepted"));
    }

    @Test
    void rejectsCallbackWithoutChannelTargetOrMessage() {
        ChannelController controller = controller(ChannelDeliveryResult.accepted("test"), true);

        var response = controller.callback(new ChannelCallbackRequest(
                "workflow", "pi-1", "COMPLETED", "", "", "", Map.of()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    private ChannelController controller(ChannelDeliveryResult result, boolean signatureValid) {
        return controller(result, signatureValid, event -> {
        });
    }

    private ChannelController controller(ChannelDeliveryResult result,
                                         boolean signatureValid,
                                         ChannelEventPublisher eventPublisher) {
        return controller((messageId, request) -> result, signatureValid, eventPublisher);
    }

    private ChannelController controller(ChannelMessageDispatcher dispatcher,
                                         boolean signatureValid,
                                         ChannelEventPublisher eventPublisher) {
        return new ChannelController(
                mock(AuditLogger.class),
                dispatcher,
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

    private static class CapturingDispatcher implements ChannelMessageDispatcher {

        private final ChannelDeliveryResult result;
        private ChannelMessageRequest lastRequest;

        CapturingDispatcher(ChannelDeliveryResult result) {
            this.result = result;
        }

        @Override
        public ChannelDeliveryResult dispatch(String messageId, ChannelMessageRequest request) {
            this.lastRequest = request;
            return result;
        }

        ChannelMessageRequest lastRequest() {
            return lastRequest;
        }
    }
}
