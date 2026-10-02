package com.lrj.platform.metering;

import com.lrj.platform.metering.budget.BudgetLedger;
import com.lrj.platform.metering.budget.RedisBudgetLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** 复用 dev_infra 实例，只创建/清理本测试 UUID 命名空间，不断开其他连接。 */
@EnabledIfEnvironmentVariable(named = "BUDGET_REDIS_TEST_PASSWORD", matches = ".+")
class RedisBudgetLedgerIntegrationTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private TokenBudgetProperties props;
    private RedisTokenBudgetTracker tracker;
    private RedisBudgetLedger ledger;
    private final MutableClock clock = new MutableClock();

    @BeforeEach void connect() {
        var connection = new RedisStandaloneConfiguration("127.0.0.1", 46379);
        connection.setPassword(System.getenv("BUDGET_REDIS_TEST_PASSWORD"));
        factory = new LettuceConnectionFactory(connection); factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
        props = new TokenBudgetProperties(); props.setTimezone("UTC"); props.getDailyTokens().setDefault(1000);
        props.getRedis().setKeyPrefix("codex-inbox-budget-it:" + UUID.randomUUID() + ":");
        tracker = new RedisTokenBudgetTracker(redis, props, props.getRedis().getKeyPrefix(), clock);
        ledger = new RedisBudgetLedger(redis, props, clock);
    }

    @AfterEach void cleanup() {
        if (redis == null) return;
        var keys = new ArrayList<String>();
        try (var scan = redis.scan(ScanOptions.scanOptions().match(props.getRedis().getKeyPrefix() + "*").count(100).build())) {
            while (scan.hasNext()) keys.add(scan.next());
        }
        if (!keys.isEmpty()) redis.delete(keys);
        factory.destroy();
    }

    @Test void sharedJavaAndPythonReservationsSettleIdempotently() {
        tracker.consume("t1", 400);
        var nativeCall = ledger.reserve("t1", "u1", "native", 600);
        var replica = new RedisBudgetLedger(redis, props, clock);
        assertThat(replica.reserve("t1", "u1", "native", 600)).isEqualTo(nativeCall);
        assertThatThrownBy(() -> replica.reserve("t1", "u1", "python", 1)).isInstanceOf(BudgetLedger.Exceeded.class);
        replica.settle(nativeCall, 300); ledger.settle(nativeCall, 300);
        assertThat(tracker.currentUsed("t1")).isEqualTo(700);
        replica.reserve("t1", "u1", "python", 300);
        assertThatThrownBy(() -> ledger.reserve("t1", "u1", "native", 600)).isInstanceOf(BudgetLedger.Conflict.class);
        assertThatThrownBy(() -> ledger.settle(nativeCall, 299)).isInstanceOf(BudgetLedger.Conflict.class);
    }

    @Test void forgedOwnerCannotReleaseUnknownReservation() {
        var r = ledger.reserve("t1", "u1", "op", 1000);
        assertThatThrownBy(() -> ledger.settle(new BudgetLedger.Reservation("t2", "u1", "op", r.day(), 1000), 0))
                .isInstanceOf(BudgetLedger.Conflict.class);
        assertThatThrownBy(() -> ledger.settle(new BudgetLedger.Reservation("t1", "u2", "op", r.day(), 1000), 0))
                .isInstanceOf(BudgetLedger.Conflict.class);
        assertThatThrownBy(() -> ledger.reserve("t1", "u2", "other", 1)).isInstanceOf(BudgetLedger.Exceeded.class);
    }

    @Test void independentReplicaAdmissionsAreAtomic() throws Exception {
        var replica = new RedisBudgetLedger(redis, props, clock);
        var accepted = new AtomicInteger(); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(20)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int n = i;
                futures.add(executor.submit(() -> {
                    try {
                        start.await(); (n % 2 == 0 ? ledger : replica).reserve("t1", "u1", "op" + n, 100);
                        accepted.incrementAndGet();
                    } catch (BudgetLedger.Exceeded expected) {}
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }));
            }
            start.countDown();
            for (var future : futures) future.get();
        }
        assertThat(accepted).hasValue(10);
    }

    @Test void crossMidnightSettlementDoesNotReleaseNewDayQuota() {
        var original = ledger.reserve("t1", "u1", "op", 1000);
        clock.now = clock.now.plus(Duration.ofDays(1));
        var nextDay = ledger.reserve("t1", "u1", "next", 1000);
        ledger.settle(original, 800);
        assertThat(tracker.currentUsed("t1")).isZero();
        assertThatThrownBy(() -> ledger.reserve("t1", "u1", "another", 1)).isInstanceOf(BudgetLedger.Exceeded.class);
        ledger.settle(nextDay, 1200);
        assertThat(tracker.currentUsed("t1")).isEqualTo(1200);
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
