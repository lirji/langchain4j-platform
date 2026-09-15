package com.lrj.platform.knowledge.ingest.job;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

/** 可控时钟与屏障模拟长解析、崩溃恢复和退避，不依赖真实外部存储。 */
class IngestionRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void renewedLeasePreventsConcurrentPreparationAndReconciliation() throws Exception {
        var store = new InMemoryIngestionJobStore();
        store.createOrGet(job());
        var time = new MutableClock();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var renewed = new CountDownLatch(1);
        var properties = new IngestionJobProperties();
        properties.setProcessingTimeout(Duration.ofSeconds(1));
        properties.setHeartbeatInterval(Duration.ofMillis(10));
        var spy = org.mockito.Mockito.spy(store);
        org.mockito.Mockito.doAnswer(inv -> {
            IngestionJob value = inv.getArgument(0);
            Object result = inv.callRealMethod();
            if (value.execution().leaseUntil() != null && value.execution().leaseUntil().isAfter(NOW.plusSeconds(1))) {
                renewed.countDown();
            }
            return result;
        }).when(spy).save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        try (var worker = new IngestionJobWorker(spy, j -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }, (j, prepared, sink) -> {}, new NoopIngestionTaskLifecycle(), time, properties);
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = callers.submit(() -> worker.process("acme", "job"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                time.now.set(NOW.plusMillis(500));
                assertThat(renewed.await(5, TimeUnit.SECONDS)).isTrue();
                time.now.set(NOW.plusMillis(1100));
                assertThat(new IngestionReconciler(spy, time, properties).reconcile()).isZero();
                assertThat(worker.process("acme", "job")).isFalse();
            } finally { release.countDown(); }
            assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(spy.find("acme", "job").orElseThrow().status()).isEqualTo(IngestionStatus.READY);
        }
    }

    @Test
    void expiredWorkerCannotStartSinkAfterReplacementWorkerFinishes() throws Exception {
        var store = new InMemoryIngestionJobStore();
        store.createOrGet(job());
        var time = new MutableClock();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writes = new AtomicInteger();
        try (var old = new IngestionJobWorker(store, j -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }, (j, prepared, sink) -> writes.incrementAndGet(), time);
             var fresh = new IngestionJobWorker(store, j -> null, (j, prepared, sink) -> writes.incrementAndGet(), time);
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = callers.submit(() -> old.process("acme", "job"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                time.now.set(NOW.plus(Duration.ofMinutes(16)));
                assertThat(new IngestionReconciler(store, time, new IngestionJobProperties()).reconcile()).isEqualTo(1);
                assertThat(fresh.process("acme", "job")).isTrue();
            } finally { release.countDown(); }
            assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(writes).hasValue(1);
        }
    }

    @Test
    void retryBackoffIsDurableBoundedAndKeepsFailureStage() {
        var store = new InMemoryIngestionJobStore();
        store.createOrGet(job());
        var time = new MutableClock();
        var properties = new IngestionJobProperties();
        properties.setMaxRetries(1);
        try (var worker = new IngestionJobWorker(store, j -> null,
                (j, prepared, sink) -> { throw new IllegalStateException("secret internal endpoint"); },
                new NoopIngestionTaskLifecycle(), time, properties)) {
            worker.process("acme", "job");
            var failed = store.find("acme", "job").orElseThrow();
            assertThat(failed.execution().failedStage()).isEqualTo("VECTOR");
            assertThat(failed.execution().nextRetryAt()).isEqualTo(NOW.plusSeconds(5));
            assertThat(failed.error()).doesNotContain("secret");
            var reconciler = new IngestionReconciler(store, time, properties);
            assertThat(reconciler.reconcile()).isZero();
            time.now.set(NOW.plusSeconds(5));
            assertThat(reconciler.reconcile()).isEqualTo(1);
            worker.process("acme", "job");
            var exhausted = store.find("acme", "job").orElseThrow();
            assertThat(exhausted.status()).isEqualTo(IngestionStatus.MANUAL_REVIEW);
            assertThat(exhausted.execution().errorCode()).isEqualTo("RETRY_EXHAUSTED");
            assertThat(reconciler.reconcile()).isZero();
        }
    }

    private static IngestionJob job() {
        return IngestionJob.received("job", "key", "acme", "alice", Set.of("ingest"), "dept", "trace",
                "doc", "doc.txt", null, 1, true,
                new DocumentSourceRef("bucket", "key", "sha256:a", "text/plain", 1), Set.of(IngestionSink.VECTOR), NOW);
    }

    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    }
}
