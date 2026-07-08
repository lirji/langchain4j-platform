package com.lrj.platform.knowledge.ingest;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * 默认入库增强：不改 chunk，原样返回。等价于「不做 contextual retrieval」。
 */
public class NoopContextualEnricher implements ContextualEnricher {

    @Override
    public List<TextSegment> enrich(String docText, List<TextSegment> segments) {
        return segments;
    }
}
