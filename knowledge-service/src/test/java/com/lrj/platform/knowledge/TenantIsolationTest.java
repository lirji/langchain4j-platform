package com.lrj.platform.knowledge;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.knowledge.graph.GraphIngestor;
import com.lrj.platform.knowledge.graph.GraphSearchService;
import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.hybrid.SimpleKeywordTokenizer;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import com.lrj.platform.knowledge.lifecycle.DocumentService;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import com.lrj.platform.knowledge.store.DimensionMismatchException;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import com.lrj.platform.knowledge.store.InMemoryEmbeddingStoreRouter;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * collection-per-tenant 强隔离：走 {@link InMemoryEmbeddingStoreRouter} 时，租户 A 查不到租户 B 的段，
 * 且 keyword 索引同样按租户分区。
 */
class TenantIsolationTest {

    private final EmbeddingModel model = new KnowledgeEmbeddingConfig.HashEmbeddingModel();
    private final EmbeddingStoreRouter router = new InMemoryEmbeddingStoreRouter();
    private final DocumentMirror mirror = new DocumentMirror();
    private final DocumentRegistry registry = new InMemoryDocumentRegistry();
    private final DocumentService documents = new DocumentService(
            router, model, mirror, splitterFactory(), registry, mock(AuditLogger.class), (GraphIngestor) null);
    private final KeywordSearchService keywordSearch = new KeywordSearchService(mirror, new SimpleKeywordTokenizer());
    private final KnowledgeQueryService queries = new KnowledgeQueryService(
            router, model, keywordSearch, 5, 0.0, true, 5,
            (GraphSearchService) null, false, 0, 1.0, 1.0, 1.0);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void tenantA_cannotSeeTenantB_segments() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        var acmeDoc = documents.upload("acme.md", "text/markdown", "refund policy alpha phoenix", "manual");

        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("ingest")));
        documents.upload("globex.md", "text/markdown", "refund policy alpha phoenix", "manual");

        // Query as acme: only acme's document surfaces (vector + keyword partitions both isolated).
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        var acmeResult = queries.query("refund policy alpha phoenix", 10, 0.0, null);
        assertThat(acmeResult.hits()).isNotEmpty();
        assertThat(acmeResult.hits()).allSatisfy(hit -> assertThat(hit.docId()).isEqualTo(acmeDoc.docId()));

        // Query as globex: acme's doc must never leak.
        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("chat")));
        var globexResult = queries.query("refund policy alpha phoenix", 10, 0.0, null);
        assertThat(globexResult.hits()).isNotEmpty();
        assertThat(globexResult.hits()).noneSatisfy(hit -> assertThat(hit.docId()).isEqualTo(acmeDoc.docId()));
    }

    @Test
    void switchingEmbeddingDimension_failsFastOnIngest() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        documents.upload("acme.md", "text/markdown", "hello", "manual");

        // Simulate an embedding-model swap that changes the vector dimension for the same tenant.
        EmbeddingModel widened = new EmbeddingModel() {
            @Override
            public dev.langchain4j.model.output.Response<java.util.List<dev.langchain4j.data.embedding.Embedding>> embedAll(
                    java.util.List<dev.langchain4j.data.segment.TextSegment> segments) {
                return model.embedAll(segments);
            }

            @Override
            public int dimension() {
                return 128;
            }
        };
        DocumentService widenedService = new DocumentService(
                router, widened, new DocumentMirror(), splitterFactory(), new InMemoryDocumentRegistry(),
                mock(AuditLogger.class), (GraphIngestor) null);

        assertThatThrownBy(() -> widenedService.upload("acme2.md", "text/markdown", "world", "manual"))
                .isInstanceOf(DimensionMismatchException.class);
    }

    private static DocumentSplitterFactory splitterFactory() {
        return new DocumentSplitterFactory(
                "recursive", "chars", 80, 0, 0, "gpt-4o-mini", "recursive",
                300, 0, 1, 95, 200, 0, new KnowledgeEmbeddingConfig.HashEmbeddingModel());
    }
}
