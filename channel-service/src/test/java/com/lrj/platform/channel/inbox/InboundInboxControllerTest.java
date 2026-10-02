package com.lrj.platform.channel.inbox;

import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class InboundInboxControllerTest {
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void anonymousAndNonAdminCannotReplay() {
        var controller = new InboundInboxController(new InMemoryInboundInboxStore(10));
        assertThat(controller.list(10).getStatusCode().value()).isEqualTo(403);
        TenantContext.set(new TenantContext.Tenant("t1", "u1", Set.of("channel")));
        assertThat(controller.replay("id").getStatusCode().value()).isEqualTo(403);
    }

    @Test void tenantAdminCannotReadPayloadOrReplayAnotherTenant() {
        var store = new InMemoryInboundInboxStore(10);
        var inbox = TestInbox.immediate(store);
        inbox.receive("t1", "feishu", "m1", "secret user message");
        var claim = store.claimDue("t1", "feishu", "worker", System.currentTimeMillis(), 60000, 1).getFirst();
        store.retry(claim, System.currentTimeMillis(), 0, true, "PROCESSING_FAILED");
        var controller = new InboundInboxController(store);
        TenantContext.set(new TenantContext.Tenant("t2", "u2", Set.of("channel", "channel.admin")));
        assertThat(controller.list(10).getBody()).asList().isEmpty();
        assertThat(controller.replay(claim.inboxId()).getStatusCode().value()).isEqualTo(409);
        TenantContext.set(new TenantContext.Tenant("t1", "u1", Set.of("channel", "channel.admin")));
        assertThat(controller.list(10).getBody().toString()).doesNotContain("secret user message");
        assertThat(controller.replay(claim.inboxId()).getStatusCode().value()).isEqualTo(202);
        assertThat(controller.replay(claim.inboxId()).getStatusCode().value()).isEqualTo(409);
    }

    @Test void poisonedMessageStopsAndCapacityDoesNotEvictPendingMessages() {
        var store = new InMemoryInboundInboxStore(1);
        var inbox = TestInbox.immediate(store);
        inbox.register("t1", "feishu", String.class, msg -> { throw new IllegalStateException("private payload"); });
        inbox.receive("t1", "feishu", "m1", "hello");
        inbox.tick(); inbox.tick(); inbox.tick(); inbox.tick();
        assertThat(store.list("t1", 1).getFirst().status()).isEqualTo(InboundInboxMessage.DEAD);
        assertThat(store.list("t1", 1).getFirst().attempts()).isEqualTo(3);
        assertThatThrownBy(() -> inbox.receive("t1", "feishu", "m2", "hello"))
                .isInstanceOf(InboundInbox.UnavailableException.class);
        assertThat(store.list("t1", 1).getFirst().messageId()).isEqualTo("m1");
    }
}
