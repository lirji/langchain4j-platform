package com.lrj.platform.knowledge.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.OptionalInt;

/**
 * 基于真实 {@link QdrantClient} 的 collection 管理：探测维度、幂等建 collection、建 payload 索引、绑定 store。
 *
 * <p>所有网络调用带超时；建 payload 索引失败不阻断（仅告警）。此类不在单测中对真实 Qdrant 执行。
 */
public class QdrantClientCollectionManager implements CollectionManager {

    private static final Logger log = LoggerFactory.getLogger(QdrantClientCollectionManager.class);

    private final QdrantClient client;
    private final String payloadTextKey;
    private final Duration timeout;
    private final boolean createPayloadIndexes;

    public QdrantClientCollectionManager(QdrantClient client,
                                         String payloadTextKey,
                                         Duration timeout,
                                         boolean createPayloadIndexes) {
        this.client = client;
        this.payloadTextKey = payloadTextKey == null || payloadTextKey.isBlank() ? "text" : payloadTextKey;
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        this.createPayloadIndexes = createPayloadIndexes;
    }

    @Override
    public OptionalInt existingDimension(String collection) {
        try {
            Boolean exists = client.collectionExistsAsync(collection, timeout).get();
            if (exists == null || !exists) {
                return OptionalInt.empty();
            }
            CollectionInfo info = client.getCollectionInfoAsync(collection, timeout).get();
            VectorsConfig vectorsConfig = info.getConfig().getParams().getVectorsConfig();
            if (vectorsConfig.hasParams()) {
                return OptionalInt.of((int) vectorsConfig.getParams().getSize());
            }
            // 命名向量场景不做维度守卫（本服务只用默认向量），返回 empty 视作未知。
            return OptionalInt.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while introspecting Qdrant collection " + collection, e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to introspect Qdrant collection " + collection, e);
        }
    }

    @Override
    public void ensureCollection(String collection, int dimension) {
        try {
            VectorParams params = VectorParams.newBuilder()
                    .setSize(dimension)
                    .setDistance(Distance.Cosine)
                    .build();
            client.createCollectionAsync(collection, params, timeout).get();
            log.info("created Qdrant collection {} (dimension={}, distance=Cosine)", collection, dimension);
            if (createPayloadIndexes) {
                createIndexQuietly(collection, "tenantId");
                createIndexQuietly(collection, "category");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while creating Qdrant collection " + collection, e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to create Qdrant collection " + collection, e);
        }
    }

    @Override
    public EmbeddingStore<TextSegment> buildStore(String collection) {
        return QdrantEmbeddingStore.builder()
                .client(client)
                .collectionName(collection)
                .payloadTextKey(payloadTextKey)
                .build();
    }

    private void createIndexQuietly(String collection, String field) {
        try {
            client.createPayloadIndexAsync(collection, field, PayloadSchemaType.Keyword, null, true, null, timeout).get();
            log.info("created Qdrant payload index {} on collection {}", field, collection);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("interrupted while creating payload index {} on {}", field, collection);
        } catch (Exception e) {
            log.warn("failed to create payload index {} on {} (non-fatal): {}", field, collection, e.toString());
        }
    }
}
