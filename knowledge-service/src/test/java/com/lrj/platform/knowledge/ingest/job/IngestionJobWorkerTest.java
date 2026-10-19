package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.observability.TraceIdFilter;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionJobWorkerTest {

    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private final InMemoryIngestionJobStore store = new InMemoryIngestionJobStore();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        MDC.clear();
    }

    @Test
    void claimsEachSinkAndRestoresPersistedSecurityContextForSideEffects() {
        IngestionJob job = store.createOrGet(job("job-1"));
        List<IngestionSink> processed = new ArrayList<>();
        TenantContext.set(new TenantContext.Tenant("outer", "caller", Set.of("chat")));
        MDC.put(TraceIdFilter.MDC_KEY, "outer-trace");
        IngestionJobWorker worker = new IngestionJobWorker(store, current -> {
            assertThat(TenantContext.current().tenantId()).isEqualTo("acme");
            return prepared(current);
        }, (current, prepared, sink) -> {
            assertThat(TenantContext.current().tenantId()).isEqualTo("acme");
            assertThat(TenantContext.current().userId()).isEqualTo("alice");
            assertThat(TenantContext.current().scopes()).containsExactly("ingest");
            assertThat(TenantContext.current().department()).isEqualTo("acme_engineering");
            assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isEqualTo("trace-1");
            processed.add(sink);
        }, clock);

        assertThat(worker.process("acme", job.jobId())).isTrue();

        assertThat(processed).containsExactly(IngestionSink.VECTOR, IngestionSink.REGISTRY);
        assertThat(store.find("acme", job.jobId()).orElseThrow().status())
                .isEqualTo(IngestionStatus.READY);
        assertThat(TenantContext.current().tenantId()).isEqualTo("outer");
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isEqualTo("outer-trace");
    }

    @Test
    void failedIdempotentSinkBecomesPartialThenReconcilesForRetry() {
        IngestionJob job = store.createOrGet(job("job-2"));
        AtomicBoolean failOnce = new AtomicBoolean(true);
        IngestionSinkProcessor processor = (current, prepared, sink) -> {
            if (sink == IngestionSink.VECTOR && failOnce.getAndSet(false)) {
                throw new IllegalStateException("vector timeout");
            }
        };
        IngestionJobWorker worker = new IngestionJobWorker(
                store, this::prepared, processor, clock);

        assertThat(worker.process("acme", job.jobId())).isTrue();
        assertThat(store.find("acme", job.jobId()).orElseThrow().status())
                .isEqualTo(IngestionStatus.PARTIAL);

        IngestionReconciler reconciler = new IngestionReconciler(
                store, Clock.offset(clock, Duration.ofSeconds(5)), Duration.ofMinutes(5), 10);
        assertThat(reconciler.reconcile()).isEqualTo(1);
        assertThat(worker.process("acme", job.jobId())).isTrue();
        assertThat(store.find("acme", job.jobId()).orElseThrow().status())
                .isEqualTo(IngestionStatus.READY);
    }

    @Test
    void invalidDocumentRequiresManualRecovery() {
        IngestionJob job = store.createOrGet(job("job-3"));
        IngestionJobWorker worker = new IngestionJobWorker(
                store,
                current -> {
                    throw new IllegalArgumentException("encrypted document");
                },
                (current, prepared, sink) -> {
                },
                clock);

        assertThat(worker.process("acme", job.jobId())).isTrue();

        IngestionJob failed = store.find("acme", job.jobId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(IngestionStatus.MANUAL_REVIEW);
        assertThat(failed.execution().errorCode()).isEqualTo("INVALID_INPUT_OR_PERMISSION");
        assertThat(failed.error()).isEqualTo("PREPARATION_FAILED");
        assertThat(new IngestionReconciler(
                store, clock, Duration.ofMinutes(5), 10).reconcile()).isZero();
    }

    @Test
    void retryAfterVectorSuccessMustReuseTheSamePreparedContent() {
        var job = store.createOrGet(job("retry-snapshot",
                Set.of(IngestionSink.VECTOR, IngestionSink.ELASTICSEARCH, IngestionSink.REGISTRY)));
        var preparations = new AtomicInteger();
        var vectorText = new AtomicReference<String>();
        var esText = new AtomicReference<String>();
        var failEsOnce = new AtomicBoolean(true);
        try (var worker = new IngestionJobWorker(store, current -> {
            var original = prepared(current);
            // 模拟切分配置变化或外部模型恢复；第二次准备会产生不同内容。
            return new PreparedIngestionDocument(original.info(),
                    List.of(TextSegment.from("generation-" + preparations.incrementAndGet())), original.embeddings());
        }, (current, document, sink) -> {
            if (sink == IngestionSink.VECTOR) vectorText.set(document.segments().getFirst().text());
            if (sink == IngestionSink.ELASTICSEARCH) {
                if (failEsOnce.getAndSet(false)) throw new IllegalStateException("temporary ES failure");
                esText.set(document.segments().getFirst().text());
            }
        }, clock)) {
            assertThat(worker.process("acme", job.jobId())).isTrue();
            assertThat(store.find("acme", job.jobId()).orElseThrow().sinks().get(IngestionSink.VECTOR))
                    .isEqualTo(IngestionSinkState.SUCCEEDED);
            assertThat(new IngestionReconciler(store, Clock.offset(clock, Duration.ofSeconds(5)),
                    Duration.ofMinutes(5), 10).reconcile()).isEqualTo(1);
            assertThat(worker.process("acme", job.jobId())).isTrue();
            assertThat(esText.get()).isEqualTo(vectorText.get());
            assertThat(preparations).hasValue(1);
        }
    }

    private IngestionJob job(String jobId) {
        return job(jobId, Set.of(IngestionSink.VECTOR, IngestionSink.REGISTRY));
    }

    private IngestionJob job(String jobId, Set<IngestionSink> sinks) {
        return IngestionJob.received(
                jobId,
                "key-" + jobId,
                "acme",
                "alice",
                Set.of("ingest"),
                "acme_engineering",
                "trace-1",
                "doc-1",
                "guide.txt",
                "manual",
                1,
                true,
                new DocumentSourceRef(
                        "knowledge",
                        "acme/doc-1/v1/sha256-abc/source",
                        "sha256:abc",
                        "text/plain",
                        5),
                sinks,
                NOW);
    }

    private PreparedIngestionDocument prepared(IngestionJob job) {
        return new PreparedIngestionDocument(
                new DocumentInfo(
                        job.documentId(), job.tenantId(), job.displayName(),
                        job.source().contentType(), job.source().size(), 1,
                        Math.toIntExact(job.documentVersion()), NOW, job.category()),
                List.of(TextSegment.from("hello")),
                List.of(Embedding.from(new float[]{1f})));
    }
}
