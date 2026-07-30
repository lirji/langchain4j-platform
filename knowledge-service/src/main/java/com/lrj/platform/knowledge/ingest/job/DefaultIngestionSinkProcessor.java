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
    private final EmbeddingModel embeddingModel;
    private final SegmentIndexer segmentIndexer;
    private final GraphIngestor graphIngestor;
    private final KnowledgeAuthz authorization;
    private final DocumentRegistry registry;

    public DefaultIngestionSinkProcessor(
            EmbeddingStoreRouter storeRouter,
            EmbeddingModel embeddingModel,
            SegmentIndexer segmentIndexer,
            GraphIngestor graphIngestor,
            KnowledgeAuthz authorization,
            DocumentRegistry registry
    ) {
        this.storeRouter = Objects.requireNonNull(storeRouter);
        this.embeddingModel = Objects.requireNonNull(embeddingModel);
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
        switch (sink) {
            case VECTOR -> writeVectors(job, prepared);
            case ELASTICSEARCH -> segmentIndexer.index(prepared.segments());
            case GRAPH -> {
                if (graphIngestor != null) {
                    graphIngestor.ingest(prepared.segments());
                }
            }
            case AUTHORIZATION -> {
                if (job.newDocument()) {
                    authorization.onDocumentCreated(
                            job.tenantId(), job.documentId(), job.userId(), job.department());
                }
            }
            case REGISTRY -> registry.put(prepared.info());
        }
    }

    private void writeVectors(IngestionJob job, PreparedIngestionDocument prepared) {
        EmbeddingStore<TextSegment> store = storeRouter.forTenant(
                job.tenantId(), embeddingModel.dimension());
        List<String> ids = new ArrayList<>(prepared.segments().size());
        for (int index = 0; index < prepared.segments().size(); index++) {
            String versionedKey = job.tenantId() + "/" + job.documentId()
                    + "/v" + job.documentVersion() + "/" + index;
            // Qdrant point IDs require UUID/unsigned integer. A name-based UUID preserves
            // deterministic retry/upsert semantics without leaking backend-specific IDs outward.
            ids.add(UUID.nameUUIDFromBytes(
                    versionedKey.getBytes(StandardCharsets.UTF_8)).toString());
        }
        store.addAll(ids, prepared.embeddings(), prepared.segments());
    }
}
