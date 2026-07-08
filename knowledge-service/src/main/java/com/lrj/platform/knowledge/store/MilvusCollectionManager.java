package com.lrj.platform.knowledge.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;

import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Milvus 后端的 {@link CollectionManager}：每租户一个 collection（{@code <base>_<tenant>}），
 * collection 由 langchain4j 官方 {@link MilvusEmbeddingStore} 按维度惰性建。
 *
 * <p>{@code existingDimension} 返回 empty（不做跨进程探测）；维度在 {@link #ensureCollection} 建 collection 时确定。
 */
public class MilvusCollectionManager implements CollectionManager {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final IndexType indexType;
    private final MetricType metricType;

    private final ConcurrentMap<String, EmbeddingStore<TextSegment>> built = new ConcurrentHashMap<>();

    public MilvusCollectionManager(String host, int port, String username, String password,
                                   String indexType, String metricType) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.indexType = IndexType.valueOf(indexType == null ? "FLAT" : indexType.toUpperCase());
        this.metricType = MetricType.valueOf(metricType == null ? "COSINE" : metricType.toUpperCase());
    }

    @Override
    public OptionalInt existingDimension(String collection) {
        return OptionalInt.empty();
    }

    @Override
    public void ensureCollection(String collection, int dimension) {
        built.computeIfAbsent(collection, name -> {
            MilvusEmbeddingStore.Builder b = MilvusEmbeddingStore.builder()
                    .host(host)
                    .port(port)
                    .collectionName(name)
                    .dimension(dimension)
                    .indexType(indexType)
                    .metricType(metricType)
                    .consistencyLevel(ConsistencyLevelEnum.EVENTUALLY)
                    .autoFlushOnInsert(true);
            if (username != null && !username.isBlank()) {
                b.username(username);
            }
            if (password != null && !password.isBlank()) {
                b.password(password);
            }
            return b.build();
        });
    }

    @Override
    public EmbeddingStore<TextSegment> buildStore(String collection) {
        EmbeddingStore<TextSegment> store = built.get(collection);
        if (store == null) {
            throw new IllegalStateException("ensureCollection must precede buildStore for milvus collection " + collection);
        }
        return store;
    }
}
