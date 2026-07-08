package com.lrj.platform.knowledge.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore.SearchMode;

import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * PgVector 后端的 {@link CollectionManager}：每租户一张表（{@code <base>_<tenant>}），
 * 建表由 langchain4j 官方 {@link PgVectorEmbeddingStore}（{@code createTable=true}，幂等）负责。
 *
 * <p>{@code existingDimension} 返回 empty（不做跨进程 catalog 探测）：因此切换 embedding 维度需先 drop 表再重灌，
 * 与整体「换 provider 需重建」的运维约定一致。维度在 {@link #ensureCollection} 建表时确定。
 */
public class PgVectorCollectionManager implements CollectionManager {

    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;
    private final boolean useIndex;
    private final int indexListSize;
    private final SearchMode searchMode;
    private final String textSearchConfig;
    private final int rrfK;

    private final ConcurrentMap<String, EmbeddingStore<TextSegment>> built = new ConcurrentHashMap<>();

    public PgVectorCollectionManager(String host, int port, String database, String user, String password,
                                     boolean useIndex, int indexListSize, String searchMode,
                                     String textSearchConfig, int rrfK) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
        this.useIndex = useIndex;
        this.indexListSize = indexListSize;
        this.searchMode = SearchMode.valueOf(searchMode == null ? "VECTOR" : searchMode.toUpperCase());
        this.textSearchConfig = textSearchConfig;
        this.rrfK = rrfK;
    }

    @Override
    public OptionalInt existingDimension(String collection) {
        return OptionalInt.empty();
    }

    @Override
    public void ensureCollection(String collection, int dimension) {
        built.computeIfAbsent(collection, table -> PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(user)
                .password(password)
                .table(table)
                .dimension(dimension)
                .createTable(true)
                .useIndex(useIndex)
                .indexListSize(indexListSize)
                .searchMode(searchMode)
                .textSearchConfig(textSearchConfig)
                .rrfK(rrfK)
                .build());
    }

    @Override
    public EmbeddingStore<TextSegment> buildStore(String collection) {
        EmbeddingStore<TextSegment> store = built.get(collection);
        if (store == null) {
            throw new IllegalStateException("ensureCollection must precede buildStore for pgvector table " + collection);
        }
        return store;
    }
}
