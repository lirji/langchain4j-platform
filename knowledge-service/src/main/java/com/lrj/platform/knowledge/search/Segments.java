package com.lrj.platform.knowledge.search;

import dev.langchain4j.data.segment.TextSegment;

import java.util.Objects;

/**
 * chunk 融合去重键工具（阶段2，es-hybrid-rerank）。逐字复刻原 {@code KnowledgeQueryService.segmentKey}：
 * 有 docId+index 元数据时用 {@code docId#index}，否则退回 {@code fallback}#textHash，保证同一 chunk 跨源可合并。
 */
public final class Segments {

    private Segments() {
    }

    public static String key(TextSegment segment, String fallback) {
        if (segment == null || segment.metadata() == null) {
            return fallback;
        }
        String docId = segment.metadata().getString("docId");
        String index = segment.metadata().getString("index");
        if (docId != null && index != null) {
            return docId + "#" + index;
        }
        return Objects.toString(docId, "segment") + "#" + Objects.hashCode(segment.text());
    }
}
