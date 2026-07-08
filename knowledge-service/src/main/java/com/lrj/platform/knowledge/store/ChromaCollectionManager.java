package com.lrj.platform.knowledge.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;

import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Chroma 后端的 {@link CollectionManager}：每租户一个 collection（{@code <base>_<tenant>}）。
 *
 * <p>Chroma 自动推导维度，故 {@code dimension} 不参与建库；collection 由 langchain4j 官方
 * {@link ChromaEmbeddingStore} 通过 HTTP 惰性建。
 */
public class ChromaCollectionManager implements CollectionManager {

    private final String baseUrl;
    private final String tenant;
    private final String database;

    private final ConcurrentMap<String, EmbeddingStore<TextSegment>> built = new ConcurrentHashMap<>();

    public ChromaCollectionManager(String baseUrl, String tenant, String database) {
        this.baseUrl = baseUrl;
        this.tenant = tenant;
        this.database = database;
    }

    @Override
    public OptionalInt existingDimension(String collection) {
        return OptionalInt.empty();
    }

    @Override
    public void ensureCollection(String collection, int dimension) {
        built.computeIfAbsent(collection, name -> {
            ChromaEmbeddingStore.Builder b = ChromaEmbeddingStore.builder()
                    .baseUrl(baseUrl)
                    .collectionName(name);
            if (tenant != null && !tenant.isBlank()) {
                b.tenantName(tenant);
            }
            if (database != null && !database.isBlank()) {
                b.databaseName(database);
            }
            return b.build();
        });
    }

    @Override
    public EmbeddingStore<TextSegment> buildStore(String collection) {
        EmbeddingStore<TextSegment> store = built.get(collection);
        if (store == null) {
            throw new IllegalStateException("ensureCollection must precede buildStore for chroma collection " + collection);
        }
        return store;
    }
}
