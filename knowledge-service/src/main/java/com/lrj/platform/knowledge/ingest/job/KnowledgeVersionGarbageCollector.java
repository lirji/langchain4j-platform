package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.DocumentMirror;
import com.lrj.platform.knowledge.es.SegmentIndexer;
import com.lrj.platform.knowledge.graph.GraphIngestor;
import com.lrj.platform.knowledge.graph.GraphSourceId;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
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

    private DocumentRegistry.MaintenanceScan scan;
    private Iterator<DocumentInfo> page = List.<DocumentInfo>of().iterator();

    /** 同一实例串行推进游标；多实例可重复幂等删除，检查点仅在全部sink成功后条件递增。 */
    public synchronized GcReport runOnce() {
        if (!policy.isEnabled()) {
            return new GcReport(0, 0, 0);
        }
        validatePolicy();
        Instant eligibleBefore = clock.instant().minus(policy.getGracePeriod());
        if (scan == null) scan = registry.maintenanceScan(policy.getBatchSize());
        int documents = 0, examined = 0, reads = 0, versions = 0, failures = 0;
        while (examined < policy.getBatchSize() && versions < policy.getMaxVersionsPerRun()) {
            if (!page.hasNext()) {
                if (scan.exhausted()) { scan = null; break; }
                if (reads++ >= policy.getBatchSize()) break;
                page = scan.nextPage().iterator();
                if (!page.hasNext()) continue;
            }
            DocumentInfo current = page.next(); examined++;
            // 扫描快照不授权删除：再次读取权威当前文档，重建、升级或删除后留待下一轮。
            if (!registry.get(current.tenantId(), current.docId()).filter(current::equals).isPresent()
                    || current.uploadedAt() == null || current.uploadedAt().isAfter(eligibleBefore)) continue;
            long last = registry.cleanedThrough(current.tenantId(), current.docId());
            long deleteThrough = (long) current.version() - policy.getRetainVersions();
            if (last >= deleteThrough) continue;
            documents++;
            int allowance = Math.min(policy.getMaxVersionsPerDocument(), policy.getMaxVersionsPerRun() - versions);
            for (int count = 0; count < allowance && last < deleteThrough; count++) {
                versions++;
                int failed = purgeVersion(current, last + 1); failures += failed;
                // 任一sink失败都保留当前版本供重试，但继续扫描其他文档，避免毒文档阻塞全局。
                if (failed > 0 || !registry.markCleaned(current, last, last + 1)) break;
                last++;
            }
        }
        return new GcReport(documents, versions, failures);
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
        try {
        mirror.removeWhere(segment -> segment.metadata() != null
                && Objects.equals(current.tenantId(), segment.metadata().getString("tenantId"))
                && Objects.equals(current.docId(), segment.metadata().getString("docId"))
                && Objects.equals(Long.toString(version), segment.metadata().getString("version")));
        } catch (RuntimeException exception) { failures++; warn("mirror", current, version, exception); }
        try {
            segmentIndexer.deleteByDocVersion(current.tenantId(), current.docId(), version);
        } catch (RuntimeException exception) {
            failures++;
            warn("elasticsearch", current, version, exception);
        }
        if (graphIngestor != null) {
            try {
                graphIngestor.removeBySourcePrefix(
                        current.tenantId(), GraphSourceId.prefix(current.docId(), version));
            } catch (RuntimeException exception) {
                failures++;
                warn("graph", current, version, exception);
            }
        }
        return failures;
    }

    private void validatePolicy() {
        if (policy.getRetainVersions() < 1 || policy.getBatchSize() < 1 || policy.getBatchSize() > 10000
                || policy.getMaxVersionsPerRun() < 1 || policy.getMaxVersionsPerRun() > 10000
                || policy.getMaxVersionsPerDocument() < 1 || policy.getMaxVersionsPerDocument() > 10000
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
