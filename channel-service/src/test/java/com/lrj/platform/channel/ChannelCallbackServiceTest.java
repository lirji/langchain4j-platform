package com.lrj.platform.channel;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.channel.ChannelCallbackRequest;
import com.lrj.platform.protocol.channel.ChannelEvent;
import com.lrj.platform.protocol.channel.ChannelMessageRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 纯 POJO：抽出的 {@link ChannelCallbackService} 行为——出站投递 + 事件用<b>显式传入</b>的 tenantId
 * （Kafka 消费线程无 TenantContext，必须靠事件里的租户）。
 */
class ChannelCallbackServiceTest {

    @Test
    void acceptUsesExplicitTenantIdOnPublishedEvent() {
        CapturingPublisher publisher = new CapturingPublisher();
        ChannelCallbackService service = new ChannelCallbackService(
                mock(AuditLogger.class), (id, req) -> ChannelDeliveryResult.sent("ok"), publisher);

        ChannelCallbackService.Result result = service.accept(
                new ChannelMessageRequest("feishu", "u1", "hello", Map.of()), "acme");

        assertThat(result.accepted()).isTrue();
        assertThat(result.reply().status()).isEqualTo("SENT");
        assertThat(publisher.events).singleElement().satisfies(e -> {
            assertThat(e.tenantId()).isEqualTo("acme");
            assertThat(e.channel()).isEqualTo("feishu");
            assertThat(e.target()).isEqualTo("u1");
        });
    }

    @Test
    void handleCallbackDerivesMessageAndMetadata() {
        CapturingPublisher publisher = new CapturingPublisher();
        List<ChannelMessageRequest> dispatched = new ArrayList<>();
        ChannelCallbackService service = new ChannelCallbackService(
                mock(AuditLogger.class),
                (id, req) -> { dispatched.add(req); return ChannelDeliveryResult.sent("ok"); },
                publisher);

        service.handleCallback(new ChannelCallbackRequest(
                "workflow", "inst-1", "COMPLETED", "feishu", "u1", "done", Map.of()), "acme");

        assertThat(dispatched).singleElement().satisfies(req -> {
            assertThat(req.channel()).isEqualTo("feishu");
            assertThat(req.target()).isEqualTo("u1");
            assertThat(req.message()).isEqualTo("done");
            assertThat(req.metadata())
                    .containsEntry("callbackSource", "workflow")
                    .containsEntry("callbackSourceId", "inst-1")
                    .containsEntry("callbackStatus", "COMPLETED");
        });
    }

    @Test
    void rejectsWhenChannelTargetOrMessageMissing() {
        ChannelCallbackService service = new ChannelCallbackService(
                mock(AuditLogger.class), (id, req) -> ChannelDeliveryResult.sent("ok"), new CapturingPublisher());

        assertThat(service.accept(new ChannelMessageRequest("", "u1", "hi", Map.of()), "acme").accepted()).isFalse();
    }

    private static class CapturingPublisher implements ChannelEventPublisher {
        final List<ChannelEvent> events = new ArrayList<>();

        @Override
        public void publish(ChannelEvent event) {
            events.add(event);
        }
    }
}
