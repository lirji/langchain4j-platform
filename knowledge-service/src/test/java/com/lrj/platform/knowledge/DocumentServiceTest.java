package com.lrj.platform.knowledge;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.knowledge.cache.SemanticCacheInvalidator;
import com.lrj.platform.knowledge.graph.GraphIngestor;
import com.lrj.platform.knowledge.graph.InMemoryGraphStore;
import com.lrj.platform.knowledge.graph.RuleBasedGraphExtractor;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import com.lrj.platform.knowledge.lifecycle.DocumentService;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DocumentServiceTest {

    private final DocumentMirror mirror = new DocumentMirror();
    private final DocumentRegistry registry = new InMemoryDocumentRegistry();
    private final DocumentService service = new DocumentService(
            new InMemoryEmbeddingStore<>(),
            new KnowledgeEmbeddingConfig.HashEmbeddingModel(),
            mirror,
            splitterFactory(),
            registry,
            mock(AuditLogger.class));

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void uploadListGetAndDelete_areTenantScoped() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));

        DocumentInfo info = service.upload("guide.md", "text/markdown", "hello knowledge base", "manual");

        assertThat(info.tenantId()).isEqualTo("acme");
        assertThat(info.version()).isEqualTo(1);
        assertThat(info.category()).isEqualTo("manual");
        assertThat(service.list()).extracting(DocumentInfo::docId).containsExactly(info.docId());
        assertThat(service.get(info.docId())).isPresent();
        assertThat(mirror.size()).isGreaterThan(0);

        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("ingest")));
        assertThat(service.list()).isEmpty();
        assertThat(service.get(info.docId())).isEmpty();

        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        assertThat(service.delete(info.docId())).isTrue();
        assertThat(service.list()).isEmpty();
        assertThat(mirror.size()).isZero();
    }

    @Test
    void reuploadSameDisplayName_replacesOldVersion() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));

        DocumentInfo v1 = service.upload("guide.md", "text/markdown", "first version", null);
        DocumentInfo v2 = service.upload("guide.md", "text/markdown", "second version", null);

        assertThat(v2.docId()).isEqualTo(v1.docId());
        assertThat(v2.version()).isEqualTo(2);
        assertThat(service.list()).hasSize(1);
        assertThat(mirror.all()).allSatisfy(seg ->
                assertThat(seg.metadata().getString("version")).isEqualTo("2"));
    }

    @Test
    void upload_canUseOriginalSourceSizeForMultimodalPayloads() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));

        DocumentInfo info = service.upload(
                "chart.png",
                "image/png",
                "Image caption:\n退款趋势图",
                "report",
                12_345);

        assertThat(info.contentType()).isEqualTo("image/png");
        assertThat(info.sizeBytes()).isEqualTo(12_345);
        assertThat(mirror.all()).singleElement()
                .satisfies(segment -> assertThat(segment.text()).contains("退款趋势图"));
    }

    @Test
    void uploadAndDeleteSynchronizeGraphTriplesWhenGraphEnabled() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        InMemoryGraphStore graphStore = new InMemoryGraphStore();
        GraphIngestor graphIngestor = new GraphIngestor(
                new RuleBasedGraphExtractor(),
                graphStore,
                10,
                Set.of(),
                Map.of(),
                Runnable::run,
                false);
        DocumentService graphEnabledService = new DocumentService(
                new InMemoryEmbeddingStore<>(),
                new KnowledgeEmbeddingConfig.HashEmbeddingModel(),
                new DocumentMirror(),
                splitterFactory(),
                new InMemoryDocumentRegistry(),
                mock(AuditLogger.class),
                graphIngestor);

        DocumentInfo info = graphEnabledService.upload(
                "people.md",
                "text/markdown",
                "张三|隶属于|研发部",
                "org");

        assertThat(graphStore.size()).isEqualTo(1);
        assertThat(graphStore.entities("acme", "org")).containsExactly("张三", "研发部");

        assertThat(graphEnabledService.delete(info.docId())).isTrue();
        assertThat(graphStore.size()).isZero();
    }

    @Test
    void uploadAndDelete_invalidateSemanticCacheWhenConfigured() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        CountingInvalidator invalidator = new CountingInvalidator();
        DocumentService svc = new DocumentService(
                new InMemoryEmbeddingStore<>(),
                new KnowledgeEmbeddingConfig.HashEmbeddingModel(),
                new DocumentMirror(),
                splitterFactory(),
                new InMemoryDocumentRegistry(),
                mock(AuditLogger.class),
                (GraphIngestor) null,
                invalidator);

        DocumentInfo info = svc.upload("guide.md", "text/markdown", "hello", "manual");
        assertThat(invalidator.calls).isEqualTo(1); // 上传后失效一次

        assertThat(svc.delete(info.docId())).isTrue();
        assertThat(invalidator.calls).isEqualTo(2); // 删除后再失效一次
    }

    @Test
    void invalidatorThatThrows_doesNotBreakIngest() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        DocumentService svc = new DocumentService(
                new InMemoryEmbeddingStore<>(),
                new KnowledgeEmbeddingConfig.HashEmbeddingModel(),
                new DocumentMirror(),
                splitterFactory(),
                new InMemoryDocumentRegistry(),
                mock(AuditLogger.class),
                (GraphIngestor) null,
                () -> { throw new RuntimeException("conversation down"); });

        // 失效器抛异常也不能影响上传结果（尽力而为）
        DocumentInfo info = svc.upload("guide.md", "text/markdown", "hello", "manual");
        assertThat(info.version()).isEqualTo(1);
    }

    static final class CountingInvalidator implements SemanticCacheInvalidator {
        int calls = 0;
        @Override
        public void invalidateCurrentTenant() {
            calls++;
        }
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
