package com.lrj.platform.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowTerminalEventOutboxTest {

    @Test
    void claimExpiryAllowsRecoveryAndFencesStaleRelay() {
        var dataSource = WorkflowTestDatabase.migrated("workflow_terminal_claim");
        WorkflowTerminalEventOutbox outbox = new WorkflowTerminalEventOutbox(dataSource);
        long now = 10_000L;
        outbox.enqueue(
                "wf-1", "acme", "chat-1", "granted", "http://callback.local/hook", now);

        WorkflowTerminalEventOutbox.Row stale = outbox.claimDue(
                now, 10, "relay-1", 1_000L).getFirst();
        assertThat(outbox.claimDue(now + 500L, 10, "relay-2", 1_000L)).isEmpty();
        WorkflowTerminalEventOutbox.Row current = outbox.claimDue(
                now + 1_500L, 10, "relay-2", 30_000L).getFirst();

        assertThat(outbox.markDelivered(
                stale.instanceId(), stale.claimOwner(), now + 1_600L)).isFalse();
        assertThat(outbox.markDelivered(
                current.instanceId(), current.claimOwner(), now + 1_600L)).isTrue();
    }
}
