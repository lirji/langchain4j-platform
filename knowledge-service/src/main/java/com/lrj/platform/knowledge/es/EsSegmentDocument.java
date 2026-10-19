package com.lrj.platform.knowledge.es;

/**
 * ES 全文索引里的一条 chunk 文档（阶段1，es-hybrid-rerank）。字段与 chunk metadata 对齐，
 * 供 {@link SegmentIndexer} 写入 / 删除、{@code EsKeywordRetrievalSource} 检索。
 *
 * <p>文档 id 由 {@code tenantId/docId/index} 稳定生成（{@link #id()}），保证同一 chunk 重复写入是幂等 upsert，
 * 删除按 {@code tenantId + docId} 前缀清理。
 */
public record EsSegmentDocument(
        String tenantId,
        String docId,
        String displayName,
        String category,
        String index,
        String version,
        String text,
        long createdAt,
        java.util.Map<String,String> sourceMetadata) {

    public EsSegmentDocument { sourceMetadata = sourceMetadata == null ? java.util.Map.of() : java.util.Map.copyOf(sourceMetadata); }

    /** 兼容旧写入方，未提供来源扩展时保持空集合。 */
    public EsSegmentDocument(String tenantId, String docId, String displayName, String category, String index,
                             String version, String text, long createdAt) {
        this(tenantId,docId,displayName,category,index,version,text,createdAt,java.util.Map.of());
    }

    /** ES 文档主键：tenantId/docId/version/index，版本提交前不会覆盖当前可见版本。 */
    public String id() {
        return tenantId + "/" + docId + "/v" + version + "/" + index;
    }
}
