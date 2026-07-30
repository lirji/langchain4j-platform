package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.DocumentMirror;
import com.lrj.platform.knowledge.es.SegmentIndexer;
import com.lrj.platform.knowledge.graph.GraphIngestor;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 清理超过回滚窗口的派生索引。Registry 当前版本、最近 retainVersions 个版本和 grace period
 * 内数据永远不删；S3 原文不归本组件管理。
 */
public class KnowledgeVersionGarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVersionGarbageCollector.class);

    private final DocumentRegistry registry;
    private final EmbeddingStoreRouter stores;
    private final EmbeddingModel embeddingModel;
    private final DocumentMirror mirror;
    private final SegmentIndexer segmentIndexer;
    private final GraphIngestor graphIngestor;
    private final Clock clock;
    private final IngestionJobProperties.VersionGc policy;

    public KnowledgeVersionGarbageCollector(
            DocumentRegistry registry,
            EmbeddingStoreRouter stores,
            EmbeddingModel embeddingModel,
            DocumentMirror mirror,
            SegmentIndexer segmentIndexer,
            GraphIngestor graphIngestor,
            Clock clock,
            IngestionJobProperties.VersionGc policy
    ) {
        this.registry = Objects.requireNonNull(registry);
        this.stores = Objects.requireNonNull(stores);
        this.embeddingModel = Objects.requireNonNull(embeddingModel);
        this.mirror = Objects.requireNonNull(mirror);
        this.segmentIndexer = Objects.requireNonNull(segmentIndexer);
        this.graphIngestor = graphIngestor;
        this.clock = Objects.requireNonNull(clock);
        this.policy = Objects.requireNonNull(policy);
    }

    public GcReport runOnce() {
        if (!policy.isEnabled()) {
            return new GcReport(0, 0, 0);
        }
        validatePolicy();
        Instant eligibleBefore = clock.instant().minus(policy.getGracePeriod());
        List<DocumentInfo> eligible = registry.snapshotAll().values().stream()
                .flatMap(items -> items.stream())
                .filter(info -> info.uploadedAt() != null && !info.uploadedAt().isAfter(eligibleBefore))
                .filter(info -> info.version() > policy.getRetainVersions())
                .sorted(Comparator.comparing(DocumentInfo::tenantId)
                        .thenComparing(DocumentInfo::docId))
                .limit(policy.getBatchSize())
                .toList();

        int versions = 0;
        int failures = 0;
        for (DocumentInfo current : eligible) {
            int deleteThrough = current.version() - policy.getRetainVersions();
            for (int version = 1; version <= deleteThrough; version++) {
                versions++;
                failures += purgeVersion(current, version);
            }
        }
        return new GcReport(eligible.size(), versions, failures);
    }

    private int purgeVersion(DocumentInfo current, long version) {
        int failures = 0;
        try {
            stores.forTenant(current.tenantId(), embeddingModel.dimension()).removeAll(
                    dev.langchain4j.store.embedding.filter.Filter.and(
                            dev.langchain4j.store.embedding.filter.Filter.and(
                                    metadataKey("tenantId").isEqualTo(current.tenantId()),
                                    metadataKey("docId").isEqualTo(current.docId())),
                            metadataKey("version").isEqualTo(Long.toString(version))));
        } catch (RuntimeException exception) {
            failures++;
            warn("vector", current, version, exception);
        }
        mirror.removeWhere(segment -> segment.metadata() != null
                && Objects.equals(current.tenantId(), segment.metadata().getString("tenantId"))
                && Objects.equals(current.docId(), segment.metadata().getString("docId"))
                && Objects.equals(Long.toString(version), segment.metadata().getString("version")));
        try {
            segmentIndexer.deleteByDocVersion(current.tenantId(), current.docId(), version);
        } catch (RuntimeException exception) {
            failures++;
            warn("elasticsearch", current, version, exception);
        }
        if (graphIngestor != null) {
            try {
                graphIngestor.removeBySourcePrefix(
                        current.tenantId(), current.docId() + "/v" + version + "/");
            } catch (RuntimeException exception) {
                failures++;
                warn("graph", current, version, exception);
            }
        }
        return failures;
    }

    private void validatePolicy() {
        if (policy.getRetainVersions() < 1 || policy.getBatchSize() < 1
                || policy.getGracePeriod() == null || policy.getGracePeriod().isNegative()) {
            throw new IllegalStateException("invalid knowledge version GC policy");
        }
    }

    private static void warn(
            String sink, DocumentInfo current, long version, RuntimeException exception
    ) {
        log.warn("knowledge version GC failed sink={} tenant={} docId={} version={} type={}",
                sink, current.tenantId(), current.docId(), version,
                exception.getClass().getSimpleName());
    }

    public record GcReport(int documents, int versions, int sinkFailures) {}
}
