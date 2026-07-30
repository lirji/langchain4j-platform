package com.lrj.platform.knowledge;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import com.lrj.platform.knowledge.es.NoopSegmentIndexer;
import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.hybrid.SimpleKeywordTokenizer;
import com.lrj.platform.knowledge.ingest.NoopContextualEnricher;
import com.lrj.platform.knowledge.ingest.job.DefaultIngestionDocumentPreparer;
import com.lrj.platform.knowledge.ingest.job.DefaultIngestionSinkProcessor;
import com.lrj.platform.knowledge.ingest.job.InMemoryDocumentSourceStore;
import com.lrj.platform.knowledge.ingest.job.InMemoryIngestionJobStore;
import com.lrj.platform.knowledge.ingest.job.IngestionJobWorker;
import com.lrj.platform.knowledge.ingest.job.IngestionSink;
import com.lrj.platform.knowledge.ingest.job.IngestionStatus;
import com.lrj.platform.knowledge.ingest.job.IngestionSubmissionService;
import com.lrj.platform.knowledge.lifecycle.DocumentService;
import com.lrj.platform.knowledge.lifecycle.DocumentTextExtractor;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import com.lrj.platform.knowledge.store.InMemoryEmbeddingStoreRouter;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 旧同步 façade 与 v2 worker 使用同一输入的最小双跑门禁。
 */
class KnowledgeIngestionDualRunTest {

    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void combinedAndSplitIngestionProduceEquivalentQueryableContent() throws Exception {
        var model = new KnowledgeEmbeddingConfig.HashEmbeddingModel();
        var legacyRouter = new InMemoryEmbeddingStoreRouter();
        var legacyRegistry = new InMemoryDocumentRegistry();
        var splitter = splitterFactory(model);
        var legacyDocuments = new DocumentService(
                legacyRouter, model, new DocumentMirror(), splitter, legacyRegistry,
                mock(AuditLogger.class), null, null);
        var tenant = new TenantContext.Tenant(
                "acme", "alice", Set.of("ingest", "chat"), "engineering");
        TenantContext.set(tenant);
        String text = "refund policy phoenix marker";
        var legacyInfo = legacyDocuments.upload(
                "guide.md", "text/markdown", text, "manual");

        var splitRouter = new InMemoryEmbeddingStoreRouter();
        var splitRegistry = new InMemoryDocumentRegistry();
        var sources = new InMemoryDocumentSourceStore();
        var jobs = new InMemoryIngestionJobStore();
        var submissions = new IngestionSubmissionService(
                sources, jobs, splitRegistry, new NoopKnowledgeAuthz(), CLOCK,
                Set.of(IngestionSink.VECTOR, IngestionSink.REGISTRY));
        var job = submissions.submit(new IngestionSubmissionService.SubmitCommand(
                "dual-run-1", "acme", "alice", tenant.scopes(), tenant.department(),
                "trace-dual", legacyInfo.docId(), "guide.md", "manual", 1,
                "text/markdown", text.getBytes(StandardCharsets.UTF_8)));
        var preparer = new DefaultIngestionDocumentPreparer(
                sources, new DocumentTextExtractor(), splitter,
                new NoopContextualEnricher(), model, null, CLOCK);
        var processor = new DefaultIngestionSinkProcessor(
                splitRouter, model, new NoopSegmentIndexer(), null,
                new NoopKnowledgeAuthz(), splitRegistry);
        var worker = new IngestionJobWorker(jobs, preparer, processor, CLOCK);

        assertThat(worker.process("acme", job.jobId())).isTrue();
        assertThat(jobs.find("acme", job.jobId()).orElseThrow().status())
                .isEqualTo(IngestionStatus.READY);

        KnowledgeQueryService legacyQuery = query(legacyRouter, model);
        KnowledgeQueryService splitQuery = query(splitRouter, model);
        splitQuery.setDocumentRegistry(splitRegistry);
        TenantContext.set(new TenantContext.Tenant(
                "acme", "alice", Set.of("chat"), "engineering"));

        var legacyResult = legacyQuery.query("phoenix", 5, 0.0, "manual");
        var splitResult = splitQuery.query("phoenix", 5, 0.0, "manual");

        assertThat(legacyResult.hits()).isNotEmpty();
        assertThat(splitResult.hits()).isNotEmpty();
        assertThat(splitResult.hits().getFirst().docId()).isEqualTo(legacyInfo.docId());
        assertThat(splitResult.hits().getFirst().text())
                .isEqualTo(legacyResult.hits().getFirst().text());
    }

    private static KnowledgeQueryService query(
            InMemoryEmbeddingStoreRouter router,
            KnowledgeEmbeddingConfig.HashEmbeddingModel model
    ) {
        DocumentMirror mirror = new DocumentMirror();
        return new KnowledgeQueryService(
                router, model,
                new KeywordSearchService(mirror, new SimpleKeywordTokenizer()),
                5, 0.0, false, 5, null, false, 5,
                1.0, 1.0, 1.0);
    }

    private static DocumentSplitterFactory splitterFactory(
            KnowledgeEmbeddingConfig.HashEmbeddingModel model
    ) {
        return new DocumentSplitterFactory(
                "recursive", "chars", 80, 0, 0, "gpt-4o-mini",
                "recursive", 300, 0, 1, 95, 200, 0, model);
    }
}
