package com.lrj.platform.knowledge.es;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * 默认索引器（ES 关闭 / 未部署时）：不做任何事，对入库与删除零副作用。
 * 由 {@code EsRagConfig} 在 {@code app.rag.es.index-enabled=false}（默认）时装配。
 */
public class NoopSegmentIndexer implements SegmentIndexer {

    @Override
    public void index(List<TextSegment> segments) {
        // no-op
    }

    @Override
    public void deleteByDoc(String tenantId, String docId) {
        // no-op
    }
}
