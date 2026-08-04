package com.lrj.platform.asynctask;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncTaskLifecycleOutboxTest {

    @Test
    void claimExpiryAllowsRecoveryAndFencesStaleRelay() {
        var dataSource = AsyncTaskTestDatabase.migrated("async_lifecycle_claim");
        AsyncTaskLifecycleOutbox outbox = new AsyncTaskLifecycleOutbox(dataSource);
        long now = 10_000L;
        outbox.enqueue("event-1", "acme", "{}", now);

        AsyncTaskLifecycleOutbox.Row stale = outbox.claimDue(
                now, 10, "relay-1", 1_000L).getFirst();
        assertThat(outbox.claimDue(now + 500L, 10, "relay-2", 1_000L)).isEmpty();
        AsyncTaskLifecycleOutbox.Row current = outbox.claimDue(
                now + 1_500L, 10, "relay-2", 30_000L).getFirst();

        assertThat(outbox.markDelivered(
                stale.eventId(), stale.claimOwner(), now + 1_600L)).isFalse();
        assertThat(outbox.markDelivered(
                current.eventId(), current.claimOwner(), now + 1_600L)).isTrue();
    }
}
