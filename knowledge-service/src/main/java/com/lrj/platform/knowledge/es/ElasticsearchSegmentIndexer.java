package com.lrj.platform.knowledge.es;

import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * ES 索引器（阶段3，es-hybrid-rerank）。把写入向量库的同一批 chunk 也 upsert 进 ES 全文索引，删除时同步清理。
 * 受 {@code app.rag.es.index-enabled} 与总开关门控；写失败按 {@code fail-fast} 抛出或 best-effort 记日志。
 */
public class ElasticsearchSegmentIndexer implements SegmentIndexer {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchSegmentIndexer.class);

    private final EsGateway gateway;
    private final EsRagProperties props;
    // #3：建索引惰性化（首次写时），把 ES 可用性从 Spring 启动解耦；失败不置位，下次写重试。
    private volatile boolean indexEnsured = false;

    public ElasticsearchSegmentIndexer(EsGateway gateway, EsRagProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    private void ensureIndexOnce() {
        if (!indexEnsured) {
            gateway.ensureIndex();
            indexEnsured = true;
        }
    }

    @Override
    public void index(List<TextSegment> segments) {
        if (!props.isIndexActive() || segments == null || segments.isEmpty()) {
            return;
        }
        List<EsSegmentDocument> docs = new ArrayList<>(segments.size());
        long now = System.currentTimeMillis();
        for (TextSegment segment : segments) {
            if (segment == null || segment.metadata() == null) {
                continue;
            }
            String tenantId = segment.metadata().getString("tenantId");
            String docId = segment.metadata().getString("docId");
            String index = segment.metadata().getString("index");
            if (tenantId == null || docId == null || index == null) {
                continue;
            }
            docs.add(new EsSegmentDocument(
                    tenantId,
                    docId,
                    segment.metadata().getString("displayName"),
                    segment.metadata().getString("category"),
                    index,
                    segment.metadata().getString("version"),
                    segment.text(),
                    now));
        }
        if (docs.isEmpty()) {
            return;
        }
        try {
            ensureIndexOnce();
            gateway.bulkUpsert(docs);
        } catch (RuntimeException e) {
            if (props.isFailFast()) {
                throw e;
            }
            log.warn("ES bulk upsert failed (best-effort, ignored): docs={} err={}", docs.size(), e.toString());
        }
    }

    @Override
    public void deleteByDoc(String tenantId, String docId) {
        if (!props.isIndexActive()) {
            return;
        }
        try {
            gateway.deleteByDoc(tenantId, docId);
        } catch (RuntimeException e) {
            if (props.isFailFast()) {
                throw e;
            }
            log.warn("ES deleteByDoc failed (best-effort, ignored): tenant={} docId={} err={}", tenantId, docId, e.toString());
        }
    }
}
