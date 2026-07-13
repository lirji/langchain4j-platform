package com.lrj.platform.knowledge.es;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * chunk 文本索引 SPI（阶段1，es-hybrid-rerank）。挂在 {@code DocumentService.upload/deleteInternal} 上，
 * 与向量写入保持同一批最终 chunk 文本一致。默认 {@link NoopSegmentIndexer}（ES 关闭时零副作用），
 * 开启后由 {@code ElasticsearchSegmentIndexer} 写入 ES。
 */
public interface SegmentIndexer {

    /**
     * 幂等索引一批 chunk（同 id upsert）。入参为写入向量库的同一批 {@link TextSegment}，
     * metadata 必含 tenantId/docId/index 等键。
     */
    void index(List<TextSegment> segments);

    /** 删除某文档的全部 chunk（按 tenantId + docId）。 */
    void deleteByDoc(String tenantId, String docId);
}
