package com.lrj.platform.asynctask;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncTaskWebhookOutboxTest {

    @Test
    void scheduleUsesExponentialBackoff() {
        AsyncTaskWebhookOutbox.Decision first = AsyncTaskWebhookOutbox.schedule(1, 5, 1000L, 250L);
        AsyncTaskWebhookOutbox.Decision third = AsyncTaskWebhookOutbox.schedule(3, 5, 1000L, 250L);

        assertThat(first.dead()).isFalse();
        assertThat(first.nextAttemptAt()).isEqualTo(1250L);
        assertThat(third.dead()).isFalse();
        assertThat(third.nextAttemptAt()).isEqualTo(3250L);
    }

    @Test
    void scheduleMarksDeadAtMaxAttempts() {
        assertThat(AsyncTaskWebhookOutbox.schedule(3, 3, 1000L, 250L).dead()).isTrue();
    }
}
