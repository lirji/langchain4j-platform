package com.lrj.platform.knowledge.store;

import com.lrj.platform.knowledge.store.doris.DorisEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Doris 后端的 {@link CollectionManager}：每租户一张表（{@code <base>_<tenant>}），
 * 表由自研 {@link DorisEmbeddingStore}（{@code CREATE TABLE IF NOT EXISTS} + HNSW ANN 索引）惰性建。
 *
 * <p>{@code existingDimension} 返回 empty；维度在 {@link #ensureCollection} 建表时确定。
 */
public class DorisCollectionManager implements CollectionManager {

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String metric;
    private final boolean createTable;
    private final int buckets;

    private final ConcurrentMap<String, EmbeddingStore<TextSegment>> built = new ConcurrentHashMap<>();

    public DorisCollectionManager(String jdbcUrl, String user, String password,
                                  String metric, boolean createTable, int buckets) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.metric = metric;
        this.createTable = createTable;
        this.buckets = buckets;
    }

    @Override
    public OptionalInt existingDimension(String collection) {
        return OptionalInt.empty();
    }

    @Override
    public void ensureCollection(String collection, int dimension) {
        built.computeIfAbsent(collection, table ->
                new DorisEmbeddingStore(jdbcUrl, user, password, table, dimension, metric, createTable, buckets));
    }

    @Override
    public EmbeddingStore<TextSegment> buildStore(String collection) {
        EmbeddingStore<TextSegment> store = built.get(collection);
        if (store == null) {
            throw new IllegalStateException("ensureCollection must precede buildStore for doris table " + collection);
        }
        return store;
    }
}
