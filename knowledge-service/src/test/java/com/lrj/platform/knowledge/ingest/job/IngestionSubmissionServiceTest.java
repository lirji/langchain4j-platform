package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionSubmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private final InMemoryDocumentSourceStore sources = new InMemoryDocumentSourceStore();
    private final InMemoryIngestionJobStore jobs = new InMemoryIngestionJobStore();
    private final InMemoryDocumentRegistry registry = new InMemoryDocumentRegistry();
    private final IngestionSubmissionService service = new IngestionSubmissionService(
            sources,
            jobs,
            registry,
            new NoopKnowledgeAuthz(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Set.of(IngestionSink.VECTOR, IngestionSink.REGISTRY));

    @Test
    void storesSourceAndExplicitSecurityContextBeforeCreatingDurableJob() throws Exception {
        IngestionJob job = service.submit(command("same-key", "acme"));

        assertThat(job.status()).isEqualTo(IngestionStatus.RECEIVED);
        assertThat(job.scopes()).containsExactly("ingest");
        assertThat(job.department()).isEqualTo("acme_engineering");
        assertThat(job.traceId()).isEqualTo("trace-1");
        assertThat(job.displayName()).isEqualTo("guide.txt");
        assertThat(job.category()).isEqualTo("manual");
        assertThat(job.newDocument()).isTrue();
        assertThat(job.source().objectKey())
                .startsWith("acme/doc-1/v1/sha256-")
                .endsWith("/source");
        assertThat(sources.open("acme", job.source()).readAllBytes())
                .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void replacementMustAdvanceRegistryVersion() {
        registry.put(new DocumentInfo(
                "doc-1", "acme", "guide.txt", "text/plain",
                5, 1, 2, NOW, "manual"));

        assertThatThrownBy(() -> service.submit(command("wrong-version", "acme")))
                .isInstanceOf(IngestionJobConflictException.class)
                .hasMessageContaining("advance");
    }

    @Test
    void duplicateSubmissionReturnsOriginalJobAndTenantCannotReadIt() throws Exception {
        IngestionJob first = service.submit(command("same-key", "acme"));
        IngestionJob duplicate = service.submit(command("same-key", "acme"));

        assertThat(duplicate.jobId()).isEqualTo(first.jobId());
        assertThatThrownBy(() -> service.get("globex", first.jobId()))
                .isInstanceOf(IngestionJobNotFoundException.class);
    }

    private IngestionSubmissionService.SubmitCommand command(String key, String tenant) {
        return new IngestionSubmissionService.SubmitCommand(
                key,
                tenant,
                "alice",
                Set.of("ingest"),
                "acme_engineering",
                "trace-1",
                "doc-1",
                "guide.txt",
                "manual",
                1,
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));
    }
}
