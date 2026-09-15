package com.lrj.platform.knowledge.search;

import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

/** 使用屏障控制完成顺序和失败，避免依赖耗时比较的脆弱并发测试。 */
class ParallelRetrievalExecutorTest {
    private final RetrievalRequest request = new RetrievalRequest("q", List.of("q"), "acme", null, 5, 0);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        MDC.clear();
    }

    @ParameterizedTest
    @EnumSource(FusionStrategy.class)
    void preservesFusionResultsWhenLaterSourceFinishesFirst(FusionStrategy strategy) {
        var secondDone = new CountDownLatch(1);
        var vector = List.of(hit("vector", "same"), hit("vector", "vector-only"));
        var es = List.of(hit("es", "same"), hit("es", "es-only"));
        try (var executor = new ParallelRetrievalExecutor(2, 4)) {
            var groups = executor.retrieve(List.of(source(r -> {
                await(secondDone);
                return vector;
            }), source(r -> {
                secondDone.countDown();
                return es;
            })), request);
            assertThat(groups).containsExactly(vector, es);
            var fusion = new HybridFusionService();
            assertThat(fusion.fuse(groups, strategy, 60))
                    .isEqualTo(fusion.fuse(List.of(vector, es), strategy, 60));
        }
    }

    @Test
    void reusedWorkerDoesNotLeakTenantOrMdcEvenAfterFailure() {
        try (var executor = new ParallelRetrievalExecutor(1, 4)) {
            for (String id : List.of("acme", "other")) {
                var tenant = new TenantContext.Tenant(id, id + "-user", Set.of("chat"), id + "-dept");
                TenantContext.set(tenant);
                MDC.put("traceId", id);
                var failure = new IllegalArgumentException("source failed");
                assertThatThrownBy(() -> executor.retrieve(List.of(source(r -> {
                    assertThat(TenantContext.current()).isEqualTo(tenant);
                    assertThat(MDC.get("traceId")).isEqualTo(id);
                    TenantContext.set(TenantContext.ANONYMOUS);
                    MDC.put("extra", "must-not-leak");
                    throw failure;
                })), request)).isSameAs(failure);
                assertThat(TenantContext.current()).isEqualTo(tenant);
                assertThat(MDC.get("extra")).isNull();
            }
            TenantContext.clear();
            MDC.clear();
            executor.retrieve(List.of(source(r -> {
                assertThat(TenantContext.captureRaw()).isNull();
                assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
                return List.of();
            })), request);
        }
    }

    @Test
    void concurrentRequestsKeepTheirOwnTenantAndDepartment() throws Exception {
        var allStarted = new CountDownLatch(4);
        try (var executor = new ParallelRetrievalExecutor(4, 8);
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var sharedSource = source(r -> {
                allStarted.countDown();
                await(allStarted);
                assertThat(TenantContext.current().tenantId()).isEqualTo(r.tenantId());
                assertThat(TenantContext.current().department()).isEqualTo(r.tenantId() + "-dept");
                assertThat(MDC.get("traceId")).isEqualTo(r.tenantId());
                return List.of(hit("test", r.tenantId()));
            });
            var results = new java.util.ArrayList<java.util.concurrent.Future<List<List<RetrievalHit>>>>();
            for (String id : List.of("acme", "other")) {
                results.add(callers.submit(() -> {
                    try {
                        TenantContext.set(new TenantContext.Tenant(id, "user", Set.of("chat"), id + "-dept"));
                        MDC.put("traceId", id);
                        return executor.retrieve(List.of(sharedSource, sharedSource),
                                new RetrievalRequest("q", List.of("q"), id, null, 5, 0));
                    } finally {
                        TenantContext.clear();
                        MDC.clear();
                    }
                }));
            }
            assertThat(results.get(0).get(5, TimeUnit.SECONDS)).allSatisfy(group ->
                    assertThat(group).extracting(RetrievalHit::docId).containsExactly("acme"));
            assertThat(results.get(1).get(5, TimeUnit.SECONDS)).allSatisfy(group ->
                    assertThat(group).extracting(RetrievalHit::docId).containsExactly("other"));
        }
    }

    @Test
    void laterFailureCancelsEarlierBlockedSourceAndPreservesException() {
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var failure = new IllegalArgumentException("failed source");
        try (var executor = new ParallelRetrievalExecutor(2, 4)) {
            assertThatThrownBy(() -> executor.retrieve(List.of(source(r -> {
                started.countDown();
                waitForCancellation(cancelled);
                return List.of();
            }), source(r -> {
                await(started);
                throw failure;
            })), request)).isSameAs(failure);
            await(cancelled);
        }
    }

    @Test
    void interruptedCallerCancelsWorkAndRetainsInterruptFlag() throws Exception {
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        try (var executor = new ParallelRetrievalExecutor(1, 4)) {
            var caller = Thread.ofVirtual().start(() -> {
                try {
                    executor.retrieve(List.of(source(r -> {
                        started.countDown();
                        waitForCancellation(cancelled);
                        return List.of();
                    })), request);
                } catch (Throwable ex) {
                    failure.set(ex);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            await(started);
            caller.interrupt();
            caller.join(5000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(interrupted).isTrue();
            await(cancelled);
        }
    }

    @Test
    void saturatedQueueRejectsAndCancelsAlreadySubmittedTasks() {
        // 1 个工作线程 + 1 个排队槽位，第三个永不结束的任务必然被拒绝。
        try (var executor = new ParallelRetrievalExecutor(1, 1)) {
            var blocked = source(r -> {
                waitForCancellation(new CountDownLatch(1));
                return List.of();
            });
            assertThatThrownBy(() -> executor.retrieve(List.of(blocked, blocked, blocked), request))
                    .isInstanceOf(RejectedExecutionException.class);
        }
    }

    @Test
    void shutdownUnblocksPendingRequestAndRejectsNewWork() throws Exception {
        var started = new CountDownLatch(1);
        var executor = new ParallelRetrievalExecutor(1, 4);
        try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = caller.submit(() -> executor.retrieve(List.of(source(r -> {
                started.countDown();
                waitForCancellation(new CountDownLatch(1));
                return List.of();
            }), source(r -> List.of())), request));
            await(started);
            executor.close();
            // 关闭可能发生在第二项入队前或后，均必须在有界时间内退出。
            try {
                result.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException ex) {
                assertThat(ex.getCause()).isInstanceOfAny(
                        java.util.concurrent.CancellationException.class, RejectedExecutionException.class);
            }
            assertThatThrownBy(() -> executor.retrieve(List.of(source(r -> List.of())), request))
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            executor.close();
        }
    }

    private static RetrievalHit hit(String source, String key) {
        return new RetrievalHit(source + key, key, 0.8, key, source, null, "0", source, source, false);
    }

    private static RetrievalSource source(Function<RetrievalRequest, List<RetrievalHit>> action) {
        return new RetrievalSource() {
            public String name() { return "test"; }
            public boolean enabled() { return true; }
            public List<RetrievalHit> retrieve(RetrievalRequest request) { return action.apply(request); }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static void waitForCancellation(CountDownLatch cancelled) {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException ex) {
            cancelled.countDown();
            Thread.currentThread().interrupt();
        }
    }
}
