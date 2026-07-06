package com.lrj.platform.knowledge;

import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.hybrid.SimpleKeywordTokenizer;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.knowledge.graph.GraphIngestor;
import com.lrj.platform.knowledge.graph.GraphSearchService;
import com.lrj.platform.knowledge.graph.InMemoryGraphStore;
import com.lrj.platform.knowledge.graph.RuleBasedGraphExtractor;
import com.lrj.platform.knowledge.graph.TokenEntityLinker;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import com.lrj.platform.knowledge.lifecycle.DocumentService;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class KnowledgeQueryServiceTest {

    private final EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
    private final EmbeddingModel model = new KnowledgeEmbeddingConfig.HashEmbeddingModel();
    private final DocumentMirror mirror = new DocumentMirror();
    private final DocumentRegistry registry = new InMemoryDocumentRegistry();
    private final DocumentService documents = new DocumentService(
            store,
            model,
            mirror,
            splitterFactory(),
            registry,
            mock(AuditLogger.class));
    private final KeywordSearchService keywordSearch = new KeywordSearchService(mirror, new SimpleKeywordTokenizer());
    private final KnowledgeQueryService queries = new KnowledgeQueryService(
            store,
            model,
            keywordSearch,
            5,
            0.0,
            true,
            5);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void query_isTenantScoped() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        var acme = documents.upload("acme.md", "text/markdown", "refund policy alpha", "manual");

        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("ingest")));
        documents.upload("globex.md", "text/markdown", "refund policy alpha", "manual");

        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        var result = queries.query("refund policy alpha", 10, 0.0, null);

        assertThat(result.tenantId()).isEqualTo("acme");
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits()).allSatisfy(hit -> assertThat(hit.docId()).isEqualTo(acme.docId()));
    }

    @Test
    void query_canFilterByCategory() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        var manual = documents.upload("manual.md", "text/markdown", "refund policy alpha", "manual");
        documents.upload("faq.md", "text/markdown", "refund policy alpha", "faq");

        var result = queries.query("refund policy alpha", 10, 0.0, "manual");

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits()).allSatisfy(hit -> {
            assertThat(hit.docId()).isEqualTo(manual.docId());
            assertThat(hit.category()).isEqualTo("manual");
        });
    }

    @Test
    void blankQuery_isRejected() {
        assertThatThrownBy(() -> queries.query(" ", 5, 0.0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void hybrid_canReturnKeywordHitsWithStrictVectorThreshold() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        var info = documents.upload("manual.md", "text/markdown", "hybrid keyword phoenix marker", "manual");

        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        var result = queries.query("phoenix", 5, 1.0, "manual");

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits()).allSatisfy(hit -> {
            assertThat(hit.docId()).isEqualTo(info.docId());
            assertThat(hit.source()).isIn("keyword", "hybrid");
        });
    }

    @Test
    void query_canIncludeGraphHitsWhenEnabled() {
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        GraphIngestor graphIngestor = new GraphIngestor(
                new RuleBasedGraphExtractor(),
                graphStore,
                10,
                Set.of(),
                Map.of(),
                Runnable::run,
                false);
        DocumentMirror graphMirror = new DocumentMirror();
        DocumentService graphDocuments = new DocumentService(
                store,
                model,
                graphMirror,
                splitterFactory(),
                new InMemoryDocumentRegistry(),
                mock(AuditLogger.class),
                graphIngestor);
        GraphSearchService graphSearch = new GraphSearchService(
                graphStore,
                new TokenEntityLinker(graphStore, new SimpleKeywordTokenizer()),
                2,
                20);
        KnowledgeQueryService graphQueries = new KnowledgeQueryService(
                store,
                model,
                new KeywordSearchService(graphMirror, new SimpleKeywordTokenizer()),
                5,
                1.0,
                true,
                5,
                graphSearch,
                true,
                5);

        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        graphDocuments.upload("people.md", "text/markdown", "张三|隶属于|研发部", "org");

        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        var result = graphQueries.query("张三负责哪个团队", 5, 1.0, "org");

        assertThat(result.hits())
                .anySatisfy(hit -> {
                    assertThat(hit.source()).isEqualTo("graph");
                    assertThat(hit.displayName()).isEqualTo("people.md");
                    assertThat(hit.text()).isEqualTo("张三 --隶属于-> 研发部");
                });
    }

    private static DocumentSplitterFactory splitterFactory() {
        return new DocumentSplitterFactory(
                "recursive",
                "chars",
                80,
                0,
                0,
                "gpt-4o-mini",
                "recursive",
                300,
                0,
                1,
                95,
                200,
                0,
                new KnowledgeEmbeddingConfig.HashEmbeddingModel());
    }
}
