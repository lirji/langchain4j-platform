package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.KnowledgeEmbeddingConfig;
import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import com.lrj.platform.knowledge.es.SegmentIndexer;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultIngestionSinkProcessorTest {

    @SuppressWarnings("unchecked")
    @Test
    void vectorUsesStableVersionedIdsAndRegistryIsExplicitCommit() {
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        EmbeddingStoreRouter router = mock(EmbeddingStoreRouter.class);
        when(router.forTenant("acme", 64)).thenReturn(store);
        KnowledgeEmbeddingConfig.HashEmbeddingModel model =
                new KnowledgeEmbeddingConfig.HashEmbeddingModel();
        InMemoryDocumentRegistry registry = new InMemoryDocumentRegistry();
        RecordingIndexer indexer = new RecordingIndexer();
        DefaultIngestionSinkProcessor processor = new DefaultIngestionSinkProcessor(
                router, model, indexer, null, new NoopKnowledgeAuthz(), registry);
        IngestionJob job = job();
        PreparedIngestionDocument prepared = prepared(job);

        processor.process(job, prepared, IngestionSink.VECTOR);
        processor.process(job, prepared, IngestionSink.VECTOR);
        processor.process(job, prepared, IngestionSink.ELASTICSEARCH);

        verify(store, times(2)).addAll(
                List.of("79d832b9-a282-329f-8c2e-ff80fa37df9a"),
                prepared.embeddings(),
                prepared.segments());
        assertThat(indexer.indexed).isEqualTo(prepared.segments());
        assertThat(registry.get("acme", "doc-1")).isEmpty();

        processor.process(job, prepared, IngestionSink.REGISTRY);

        assertThat(registry.get("acme", "doc-1")).contains(prepared.info());
    }

    private static IngestionJob job() {
        return IngestionJob.received(
                "job-1", "key-1", "acme", "alice", Set.of("ingest"),
                "engineering", "trace-1", "doc-1", "guide.md", "manual",
                2, false,
                new DocumentSourceRef(
                        "knowledge", "acme/doc-1/v2/source", "sha256:abc",
                        "text/markdown", 5),
                Set.of(IngestionSink.VECTOR, IngestionSink.ELASTICSEARCH,
                        IngestionSink.REGISTRY),
                Instant.parse("2026-07-30T00:00:00Z"));
    }

    private static PreparedIngestionDocument prepared(IngestionJob job) {
        TextSegment segment = TextSegment.from("hello");
        segment.metadata()
                .put("tenantId", job.tenantId())
                .put("docId", job.documentId())
                .put("index", "0")
                .put("version", "2");
        return new PreparedIngestionDocument(
                new DocumentInfo(
                        job.documentId(), job.tenantId(), job.displayName(),
                        job.source().contentType(), job.source().size(), 1, 2,
                        Instant.parse("2026-07-30T00:00:00Z"), job.category()),
                List.of(segment),
                List.of(Embedding.from(new float[64])));
    }

    private static final class RecordingIndexer implements SegmentIndexer {

        private List<TextSegment> indexed = List.of();

        @Override
        public void index(List<TextSegment> segments) {
            indexed = List.copyOf(segments);
        }

        @Override
        public void deleteByDoc(String tenantId, String docId) {
        }
    }
}
