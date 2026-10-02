package com.lrj.platform.channel.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class InboundInboxStoreTest {
    private InboundInboxStore store(boolean jdbc) {
        if (!jdbc) return new InMemoryInboundInboxStore(100);
        String integrationUrl = System.getenv("CHANNEL_INBOX_TEST_DB_URL");
        if (integrationUrl != null && !integrationUrl.matches(
                "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/lc4j_inbox_it_[a-z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("integration tests require an isolated lc4j_inbox_it database");
        var ds = integrationUrl == null
                ? new DriverManagerDataSource("jdbc:h2:mem:inbox_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")
                : new DriverManagerDataSource(integrationUrl, System.getenv("CHANNEL_INBOX_TEST_DB_USER"),
                    System.getenv("CHANNEL_INBOX_TEST_DB_PASSWORD"));
        SchemaMigrationRunner.migrate(ds, SchemaName.CHANNEL);
        if (integrationUrl != null) {
            // 仅允许专用临时数据库，每个恢复场景从独立消息集开始。
            new org.springframework.jdbc.core.JdbcTemplate(ds).update("DELETE FROM CHANNEL_INBOUND_INBOX");
        }
        return new JdbcInboundInboxStore(ds);
    }

    private InboundInboxMessage message(String id, String tenant, String hash) {
        return new InboundInboxMessage(id, tenant, "feishu", "original", hash, "{}", "trace",
                InboundInboxMessage.PENDING, 0, null, 0, 0, 1000, 1000, 1000, null);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void duplicateCannotOverwriteAndTenantIsIsolated(boolean jdbc) {
        var s = store(jdbc);
        assertThat(s.receive(message("id", "t1", "hash"))).isTrue();
        assertThat(s.receive(message("id", "t1", "hash"))).isFalse();
        assertThatThrownBy(() -> s.receive(message("id", "t1", "other")))
                .isInstanceOf(InboundInboxStore.PayloadConflictException.class);
        assertThat(s.claimDue("t2", "feishu", "worker", 1000, 1000, 1)).isEmpty();
        assertThat(s.list("t2", 100)).isEmpty();
        assertThat(s.replay("t2", "id", 1000)).isFalse();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void abandonedLeaseRecoversAndOldExecutorIsFenced(boolean jdbc) {
        var s = store(jdbc);
        s.receive(message("id", "t1", "hash"));
        var old = s.claimDue("t1", "feishu", "old", 1000, 1000, 1).getFirst();
        assertThat(s.claimDue("t1", "feishu", "new", 1999, 1000, 1)).isEmpty();
        assertThat(s.renew(old, 2000, 5000)).isFalse();
        var fresh = s.claimDue("t1", "feishu", "new", 2000, 1000, 1).getFirst();
        assertThat(fresh.leaseEpoch()).isEqualTo(old.leaseEpoch() + 1);
        assertThat(s.succeed(old, 2001)).isFalse();
        assertThat(s.retry(old, 2001, 5000, true, "failed")).isFalse();
        assertThat(s.renew(fresh, 2500, 4000)).isTrue();
        assertThat(s.succeed(fresh, 3001)).isTrue();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void delayedRetryDeadReplayAndCleanupHaveLegalTransitions(boolean jdbc) {
        var s = store(jdbc);
        s.receive(message("id", "t1", "hash"));
        var c = s.claimDue("t1", "feishu", "w", 1000, 1000, 1).getFirst();
        assertThat(s.retry(c, 1100, 1800, false, "failed")).isTrue();
        assertThat(s.claimDue("t1", "feishu", "w", 1799, 1000, 1)).isEmpty();
        var retry = s.claimDue("t1", "feishu", "w", 1800, 1000, 1).getFirst();
        assertThat(retry.attempts()).isEqualTo(2);
        assertThat(s.retry(retry, 1900, 1900, true, "failed")).isTrue();
        assertThat(s.cleanupSucceeded(10000, 100)).isZero();
        assertThat(s.claimDue("t1", "feishu", "w", 2000, 1000, 1)).isEmpty();
        assertThat(s.replay("t2", "id", 2000)).isFalse();
        assertThat(s.replay("t1", "id", 2000)).isTrue();
        assertThat(s.replay("t1", "id", 2000)).isFalse();
        var replay = s.claimDue("t1", "feishu", "w", 2000, 1000, 1).getFirst();
        assertThat(replay.attempts()).isEqualTo(1);
        assertThat(replay.leaseEpoch()).isGreaterThan(retry.leaseEpoch());
        assertThat(s.succeed(replay, 2100)).isTrue();
        s.receive(message("pending", "t1", "hash"));
        assertThat(s.cleanupSucceeded(3000, 100)).isEqualTo(1);
        assertThat(s.list("t1", 100)).extracting(InboundInboxMessage::inboxId).containsExactly("pending");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentClaimHasSingleWinner(boolean jdbc) throws Exception {
        var s = store(jdbc);
        s.receive(message("id", "t1", "hash"));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(pool.submit(() -> { start.await(); return s.claimDue("t1", "feishu", "a", 1000, 1000, 1).size(); }),
                    pool.submit(() -> { start.await(); return s.claimDue("t1", "feishu", "b", 1000, 1000, 1).size(); }));
            start.countDown();
            assertThat(futures.get(0).get() + futures.get(1).get()).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ackedButDroppedExecutorRecoversWithoutChannelRedelivery(boolean jdbc) {
        var s = store(jdbc);
        var now = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
        var lost = new InboundInbox(s, new ObjectMapper(), runnable -> {}, now, 1, 2, 1000, 0, 2000);
        lost.register("t1", "feishu", String.class, msg -> fail("old process must not execute"));
        lost.receive("t1", "feishu", "msg", "hello");
        lost.tick();
        assertThat(s.list("t1", 1).getFirst().status()).isEqualTo(InboundInboxMessage.RUNNING);
        AtomicInteger executed = new AtomicInteger();
        var recovered = new InboundInbox(s, new ObjectMapper(), Runnable::run,
                Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC), 1, 2, 1000, 0, 2000);
        recovered.register("t1", "feishu", String.class, msg -> executed.incrementAndGet());
        recovered.tick();
        assertThat(executed).hasValue(1);
        assertThat(s.list("t1", 1).getFirst().status()).isEqualTo(InboundInboxMessage.SUCCEEDED);
    }
}
