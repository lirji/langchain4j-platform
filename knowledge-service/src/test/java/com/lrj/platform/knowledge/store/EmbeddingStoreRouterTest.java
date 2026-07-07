package com.lrj.platform.knowledge.store;

import com.lrj.platform.knowledge.KnowledgeEmbeddingConfig;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingStoreRouterTest {

    private final EmbeddingModel model = new KnowledgeEmbeddingConfig.HashEmbeddingModel();

    @Test
    void inMemoryRouter_isolatesTenantsIntoSeparateStores() {
        InMemoryEmbeddingStoreRouter router = new InMemoryEmbeddingStoreRouter();

        EmbeddingStore<TextSegment> acme = router.forTenant("acme", model.dimension());
        EmbeddingStore<TextSegment> globex = router.forTenant("globex", model.dimension());

        assertThat(acme).isNotSameAs(globex);
        assertThat(router.tenantCount()).isEqualTo(2);

        TextSegment segment = TextSegment.from("refund policy alpha");
        Embedding embedding = model.embed(segment.text()).content();
        acme.add(embedding, segment);

        // Tenant A finds its own segment.
        var acmeMatches = acme.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(model.embed("refund policy alpha").content())
                .maxResults(5)
                .build()).matches();
        assertThat(acmeMatches).isNotEmpty();

        // Tenant B's store is physically empty — strong isolation, not just metadata filtering.
        var globexMatches = globex.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(model.embed("refund policy alpha").content())
                .maxResults(5)
                .build()).matches();
        assertThat(globexMatches).isEmpty();
    }

    @Test
    void inMemoryRouter_returnsSameStoreForSameTenant() {
        InMemoryEmbeddingStoreRouter router = new InMemoryEmbeddingStoreRouter();
        assertThat(router.forTenant("acme", 64)).isSameAs(router.forTenant("acme", 64));
    }

    @Test
    void inMemoryRouter_failsFastOnDimensionSwitch() {
        InMemoryEmbeddingStoreRouter router = new InMemoryEmbeddingStoreRouter();
        router.forTenant("acme", 64);

        assertThatThrownBy(() -> router.forTenant("acme", 128))
                .isInstanceOf(DimensionMismatchException.class)
                .hasMessageContaining("acme")
                .hasMessageContaining("64")
                .hasMessageContaining("128");
    }

    @Test
    void singleRouter_ignoresTenantButGuardsDimension() {
        EmbeddingStore<TextSegment> shared = new InMemoryEmbeddingStore<>();
        SingleEmbeddingStoreRouter router = new SingleEmbeddingStoreRouter(shared, 64);

        assertThat(router.forTenant("acme", 64)).isSameAs(shared);
        assertThat(router.forTenant("globex", 64)).isSameAs(shared);

        assertThatThrownBy(() -> router.forTenant("acme", 1536))
                .isInstanceOf(DimensionMismatchException.class);
    }

    @Test
    void qdrantRouter_namesCollectionPerTenantAndSanitizes() {
        assertThat(QdrantEmbeddingStoreRouter.collectionName("knowledge_segments", "acme"))
                .isEqualTo("knowledge_segments_acme");
        assertThat(QdrantEmbeddingStoreRouter.collectionName("kb", "tenant/with:weird chars"))
                .isEqualTo("kb_tenant_with_weird_chars");
        assertThat(QdrantEmbeddingStoreRouter.collectionName("kb", " "))
                .isEqualTo("kb_default");
    }

    @Test
    void qdrantRouter_createsCollectionLazilyThenReuses() {
        RecordingManager manager = new RecordingManager(java.util.OptionalInt.empty());
        QdrantEmbeddingStoreRouter router = new QdrantEmbeddingStoreRouter(manager, "kb");

        router.forTenant("acme", 64);
        router.forTenant("acme", 64);

        assertThat(manager.created).containsExactly("kb_acme");
        assertThat(manager.built).containsExactly("kb_acme"); // cached: built once
    }

    @Test
    void qdrantRouter_failsFastWhenExistingCollectionDimensionDiffers() {
        RecordingManager manager = new RecordingManager(java.util.OptionalInt.of(1536));
        QdrantEmbeddingStoreRouter router = new QdrantEmbeddingStoreRouter(manager, "kb");

        assertThatThrownBy(() -> router.forTenant("acme", 64))
                .isInstanceOf(DimensionMismatchException.class)
                .hasMessageContaining("kb_acme");
        assertThat(manager.created).isEmpty();
    }

    private static final class RecordingManager implements QdrantCollectionManager {
        private final java.util.OptionalInt existing;
        private final List<String> created = new java.util.ArrayList<>();
        private final List<String> built = new java.util.ArrayList<>();

        private RecordingManager(java.util.OptionalInt existing) {
            this.existing = existing;
        }

        @Override
        public java.util.OptionalInt existingDimension(String collection) {
            return existing;
        }

        @Override
        public void ensureCollection(String collection, int dimension) {
            created.add(collection);
        }

        @Override
        public EmbeddingStore<TextSegment> buildStore(String collection) {
            built.add(collection);
            return new InMemoryEmbeddingStore<>();
        }
    }
}
