package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.authz.KnowledgeAuthz;
import com.lrj.platform.knowledge.es.SegmentIndexer;
import com.lrj.platform.knowledge.graph.GraphIngestor;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Java 领域副作用执行器。每个 sink 都使用文档版本构造稳定主键；REGISTRY 是最后的可见性提交点。
 */
public class DefaultIngestionSinkProcessor implements IngestionSinkProcessor {

    private final EmbeddingStoreRouter storeRouter;
    private final SegmentIndexer segmentIndexer;
    private final GraphIngestor graphIngestor;
    private final KnowledgeAuthz authorization;
    private final DocumentRegistry registry;
    private final IngestionBatcher batcher;

    public DefaultIngestionSinkProcessor(
            EmbeddingStoreRouter storeRouter,
            EmbeddingModel embeddingModel,
            SegmentIndexer segmentIndexer,
            GraphIngestor graphIngestor,
            KnowledgeAuthz authorization,
            DocumentRegistry registry
    ) {
        this(storeRouter, embeddingModel, segmentIndexer, graphIngestor, authorization, registry, new IngestionResourceProperties());
    }

    /** 分批预算共享准备器配置，重试仍使用原快照的最终下标构造主键。 */
    public DefaultIngestionSinkProcessor(EmbeddingStoreRouter storeRouter, EmbeddingModel embeddingModel,
            SegmentIndexer segmentIndexer, GraphIngestor graphIngestor, KnowledgeAuthz authorization,
            DocumentRegistry registry, IngestionResourceProperties resources) {
        this.batcher = new IngestionBatcher(resources);
        this.storeRouter = Objects.requireNonNull(storeRouter);
        Objects.requireNonNull(embeddingModel);
        this.segmentIndexer = Objects.requireNonNull(segmentIndexer);
        this.graphIngestor = graphIngestor;
        this.authorization = Objects.requireNonNull(authorization);
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public void process(
            IngestionJob job,
            PreparedIngestionDocument prepared,
            IngestionSink sink
    ) {
        process(job, prepared, sink, () -> { });
    }

    /** 批次失败不推进 sink；恢复时所有已发批次按稳定 ID 重放。 */
    @Override
    public void process(IngestionJob job, PreparedIngestionDocument prepared, IngestionSink sink, Runnable checkpoint) {
        checkpoint.run();
        switch (sink) {
            case VECTOR, ELASTICSEARCH, GRAPH -> {
                List<IngestionBatcher.Batch> batches = batcher.sinkBatches(prepared);
                EmbeddingStore<TextSegment> store = sink == IngestionSink.VECTOR
                        ? storeRouter.forTenant(job.tenantId(), prepared.embeddings().getFirst().dimension()) : null;
                for (var batch : batches) {
                    checkpoint.run();
                    List<TextSegment> segments = prepared.segments().subList(batch.from(), batch.to());
                    if (sink == IngestionSink.VECTOR) {
                        store.addAll(vectorIds(job, batch), prepared.embeddings().subList(batch.from(), batch.to()), segments);
                    } else if (sink == IngestionSink.ELASTICSEARCH) {
                        segmentIndexer.index(segments);
                    } else if (graphIngestor != null) {
                        graphIngestor.ingestSynchronously(segments, checkpoint);
                    }
                    checkpoint.run();
                }
            }
            case AUTHORIZATION -> {
                if (job.newDocument()) {
                    authorization.onDocumentCreated(
                            job.tenantId(), job.documentId(), job.userId(), job.department());
                }
            }
            case REGISTRY -> {
                if (!registry.commitVersion(prepared.info())) {
                    throw new IllegalArgumentException("document version commit rejected");
                }
            }
        }
    }

    private List<String> vectorIds(IngestionJob job, IngestionBatcher.Batch batch) {
        List<String> ids = new ArrayList<>(batch.to() - batch.from());
        for (int index = batch.from(); index < batch.to(); index++) {
            String versionedKey = job.tenantId() + "/" + job.documentId()
                    + "/v" + job.documentVersion() + "/" + index;
            // 全文下标不随批次或重试变化；Qdrant 仍使用确定性 UUID。
            ids.add(UUID.nameUUIDFromBytes(versionedKey.getBytes(StandardCharsets.UTF_8)).toString());
        }
        return ids;
    }
}
