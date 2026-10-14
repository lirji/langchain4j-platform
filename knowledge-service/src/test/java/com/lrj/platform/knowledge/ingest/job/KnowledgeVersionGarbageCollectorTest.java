package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.DocumentMirror;
import com.lrj.platform.knowledge.KnowledgeEmbeddingConfig;
import com.lrj.platform.knowledge.es.SegmentIndexer;
import com.lrj.platform.knowledge.graph.GraphIngestor;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeVersionGarbageCollectorTest {

    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @SuppressWarnings("unchecked")
    @Test
    void purgesOnlyVersionsOutsideRollbackWindowAndNeverCrossesTenant() {
        InMemoryDocumentRegistry registry = new InMemoryDocumentRegistry();
        registry.put(info("acme", "doc-1", 5, NOW.minus(Duration.ofDays(10))));
        registry.put(info("globex", "doc-2", 1, NOW.minus(Duration.ofDays(20))));
        DocumentMirror mirror = new DocumentMirror();
        for (int version = 1; version <= 5; version++) {
            mirror.add(List.of(segment("acme", "doc-1", version)));
        }
        mirror.add(List.of(segment("globex", "doc-2", 1)));

        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        EmbeddingStoreRouter router = mock(EmbeddingStoreRouter.class);
        when(router.forTenant("acme", 64)).thenReturn(store);
        RecordingIndexer indexer = new RecordingIndexer();
        GraphIngestor graph = mock(GraphIngestor.class);
        IngestionJobProperties.VersionGc policy = policy(true, 2, Duration.ofDays(7));
        KnowledgeVersionGarbageCollector collector = new KnowledgeVersionGarbageCollector(
                registry, router, new KnowledgeEmbeddingConfig.HashEmbeddingModel(),
                mirror, indexer, graph, Clock.fixed(NOW, ZoneOffset.UTC), policy);

        var report = collector.runOnce();

        assertThat(report.documents()).isEqualTo(1);
        assertThat(report.versions()).isEqualTo(3);
        assertThat(report.sinkFailures()).isZero();
        verify(store, times(3)).removeAll(
                any(dev.langchain4j.store.embedding.filter.Filter.class));
        assertThat(indexer.deletedVersions).containsExactly(1L, 2L, 3L);
        verify(graph).removeBySourcePrefix("acme", "doc-1/v1/");
        verify(graph).removeBySourcePrefix("acme", "doc-1/v2/");
        verify(graph).removeBySourcePrefix("acme", "doc-1/v3/");
        assertThat(mirror.all("acme"))
                .extracting(item -> item.metadata().getString("version"))
                .containsExactly("4", "5");
        assertThat(mirror.all("globex")).hasSize(1);
        assertThat(registry.get("acme", "doc-1")).contains(info(
                "acme", "doc-1", 5, NOW.minus(Duration.ofDays(10))));
    }

    @SuppressWarnings("unchecked")
    @Test
    void disabledOrGracePeriodProtectedDocumentsAreNotTouched() {
        InMemoryDocumentRegistry registry = new InMemoryDocumentRegistry();
        registry.put(info("acme", "doc-1", 5, NOW.minus(Duration.ofDays(1))));
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        EmbeddingStoreRouter router = mock(EmbeddingStoreRouter.class);
        when(router.forTenant("acme", 64)).thenReturn(store);

        var collector = new KnowledgeVersionGarbageCollector(
                registry, router, new KnowledgeEmbeddingConfig.HashEmbeddingModel(),
                new DocumentMirror(), new RecordingIndexer(), null,
                Clock.fixed(NOW, ZoneOffset.UTC), policy(true, 2, Duration.ofDays(7)));

        assertThat(collector.runOnce().versions()).isZero();
        verify(store, times(0)).removeAll(
                any(dev.langchain4j.store.embedding.filter.Filter.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void oneSinkFailureIsReportedWithoutBlockingOtherSinkCleanup() {
        InMemoryDocumentRegistry registry = new InMemoryDocumentRegistry();
        registry.put(info("acme", "doc-1", 2, NOW.minus(Duration.ofDays(10))));
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("vector down"))
                .when(store).removeAll(any(dev.langchain4j.store.embedding.filter.Filter.class));
        EmbeddingStoreRouter router = mock(EmbeddingStoreRouter.class);
        when(router.forTenant("acme", 64)).thenReturn(store);
        RecordingIndexer indexer = new RecordingIndexer();
        var collector = new KnowledgeVersionGarbageCollector(
                registry, router, new KnowledgeEmbeddingConfig.HashEmbeddingModel(),
                new DocumentMirror(), indexer, null,
                Clock.fixed(NOW, ZoneOffset.UTC), policy(true, 1, Duration.ofDays(7)));

        var report = collector.runOnce();

        assertThat(report.versions()).isEqualTo(1);
        assertThat(report.sinkFailures()).isEqualTo(1);
        assertThat(indexer.deletedVersions).containsExactly(1L);
    }


    @Test
    void batchesEventuallyVisitEveryDocumentAndDoNotRepeatSuccessfulVersions() {
        var registry = new InMemoryDocumentRegistry();
        registry.put(info("acme", "a", 3, NOW.minus(Duration.ofDays(10))));
        registry.put(info("acme", "b", 3, NOW.minus(Duration.ofDays(10))));
        var router = mock(EmbeddingStoreRouter.class);
        when(router.forTenant("acme", 64)).thenReturn(mock(EmbeddingStore.class));
        var indexer = mock(SegmentIndexer.class);
        var p = policy(true, 2, Duration.ofDays(7)); p.setBatchSize(1);
        var collector = new KnowledgeVersionGarbageCollector(registry, router,
                new KnowledgeEmbeddingConfig.HashEmbeddingModel(), new DocumentMirror(), indexer, null,
                Clock.fixed(NOW, ZoneOffset.UTC), p);
        for (int i = 0; i < 6; i++) collector.runOnce();
        verify(indexer, times(1)).deleteByDocVersion("acme", "a", 1);
        verify(indexer, times(1)).deleteByDocVersion("acme", "b", 1);
    }

    @Test
    void workIsBoundedAndRestartContinuesFromSuccessfulCheckpoint() {
        var registry = new InMemoryDocumentRegistry();
        registry.put(info("acme", "large", 100, NOW.minus(Duration.ofDays(10))));
        var router = mock(EmbeddingStoreRouter.class);
        when(router.forTenant("acme", 64)).thenReturn(mock(EmbeddingStore.class));
        var indexer = mock(SegmentIndexer.class);
        var p = policy(true, 2, Duration.ofDays(7)); p.setMaxVersionsPerRun(2);
        var collector = new KnowledgeVersionGarbageCollector(registry, router,
                new KnowledgeEmbeddingConfig.HashEmbeddingModel(), new DocumentMirror(), indexer, null,
                Clock.fixed(NOW, ZoneOffset.UTC), p);
        assertThat(collector.runOnce().versions()).isEqualTo(2);
        assertThat(registry.cleanedThrough("acme", "large")).isEqualTo(2);
        var restarted = new KnowledgeVersionGarbageCollector(registry, router,
                new KnowledgeEmbeddingConfig.HashEmbeddingModel(), new DocumentMirror(), indexer, null,
                Clock.fixed(NOW, ZoneOffset.UTC), p);
        assertThat(restarted.runOnce().versions()).isEqualTo(2);
        verify(indexer, times(1)).deleteByDocVersion("acme", "large", 1);
        verify(indexer).deleteByDocVersion("acme", "large", 4);
    }

    @Test
    void failedDocumentDoesNotBlockOthersAndIsRetriedWithoutAdvancingCheckpoint() {
        var registry = new InMemoryDocumentRegistry();
        registry.put(info("acme", "a", 3, NOW.minus(Duration.ofDays(10))));
        registry.put(info("acme", "b", 3, NOW.minus(Duration.ofDays(10))));
        var router = mock(EmbeddingStoreRouter.class);
        when(router.forTenant("acme", 64)).thenReturn(mock(EmbeddingStore.class));
        var indexer = mock(SegmentIndexer.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("down")).doNothing().when(indexer).deleteByDocVersion("acme", "a", 1);
        var collector = new KnowledgeVersionGarbageCollector(registry, router,
                new KnowledgeEmbeddingConfig.HashEmbeddingModel(), new DocumentMirror(), indexer, null,
                Clock.fixed(NOW, ZoneOffset.UTC), policy(true, 2, Duration.ofDays(7)));
        assertThat(collector.runOnce().sinkFailures()).isEqualTo(1);
        assertThat(registry.cleanedThrough("acme", "a")).isZero();
        assertThat(registry.cleanedThrough("acme", "b")).isEqualTo(1);
        collector.runOnce();
        assertThat(registry.cleanedThrough("acme", "a")).isEqualTo(1);
        verify(indexer, times(1)).deleteByDocVersion("acme", "b", 1);
    }

    private static DocumentInfo info(
            String tenant, String docId, int version, Instant uploadedAt
    ) {
        return new DocumentInfo(
                docId, tenant, docId + ".md", "text/markdown",
                10, 1, version, uploadedAt, "manual");
    }

    private static TextSegment segment(String tenant, String docId, int version) {
        return TextSegment.from(
                "v" + version,
                Metadata.from(java.util.Map.of(
                        "tenantId", tenant,
                        "docId", docId,
                        "version", Integer.toString(version))));
    }

    private static IngestionJobProperties.VersionGc policy(
            boolean enabled, int retain, Duration grace
    ) {
        IngestionJobProperties.VersionGc policy = new IngestionJobProperties.VersionGc();
        policy.setEnabled(enabled);
        policy.setRetainVersions(retain);
        policy.setGracePeriod(grace);
        policy.setBatchSize(100);
        return policy;
    }

    private static final class RecordingIndexer implements SegmentIndexer {
        private final List<Long> deletedVersions = new ArrayList<>();

        @Override public void index(List<TextSegment> segments) {}
        @Override public void deleteByDoc(String tenantId, String docId) {}
        @Override public void deleteByDocVersion(String tenantId, String docId, long version) {
            deletedVersions.add(version);
        }
    }
}
