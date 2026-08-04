package com.lrj.platform.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯逻辑单测：outbox 重投调度决策（指数退避 + DLQ 阈值）。不连 DB。
 */
class WorkflowOutboxTest {

    @Test
    void schedule_firstFailure_backoffIsBase() {
        // attemptsAfter=1 → 下次 = now + base*3^0 = now + base
        WorkflowOutbox.Decision d = WorkflowOutbox.schedule(1, 6, 1000L, 5000L);
        assertFalse(d.dead());
        assertEquals(1000L + 5000L, d.nextAttemptAt());
    }

    @Test
    void schedule_thirdFailure_backoffIsExponential() {
        // attemptsAfter=3 → base*3^2 = base*9
        WorkflowOutbox.Decision d = WorkflowOutbox.schedule(3, 6, 0L, 5000L);
        assertFalse(d.dead());
        assertEquals(5000L * 9, d.nextAttemptAt());
    }

    @Test
    void schedule_reachingMaxAttempts_isDead() {
        WorkflowOutbox.Decision d = WorkflowOutbox.schedule(6, 6, 0L, 5000L);
        assertTrue(d.dead(), "累计尝试达到 maxAttempts → 进 DLQ");
    }

    @Test
    void schedule_beyondMaxAttempts_isDead() {
        assertTrue(WorkflowOutbox.schedule(7, 6, 0L, 5000L).dead());
    }

    @Test
    void claimExpiryAllowsRecoveryAndFencesStaleDispatcher() {
        var dataSource = WorkflowTestDatabase.migrated("workflow_http_claim");
        WorkflowOutbox outbox = new WorkflowOutbox(dataSource);
        long now = 10_000L;
        outbox.enqueue("wf-1", "acme", "http://callback.local/hook", now);

        WorkflowOutbox.Row stale = outbox.claimDue(
                now, 10, "dispatcher-1", 1_000L).getFirst();
        assertTrue(outbox.claimDue(now + 500L, 10, "dispatcher-2", 1_000L).isEmpty());
        WorkflowOutbox.Row current = outbox.claimDue(
                now + 1_500L, 10, "dispatcher-2", 30_000L).getFirst();

        assertFalse(outbox.markDelivered(
                stale.instanceId(), stale.claimOwner(), now + 1_600L));
        assertTrue(outbox.markDelivered(
                current.instanceId(), current.claimOwner(), now + 1_600L));
    }
}
