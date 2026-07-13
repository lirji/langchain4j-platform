package com.lrj.platform.knowledge.search;

/**
 * 单个检索源产出的一条命中（阶段1，es-hybrid-rerank）。分数 {@code score} 已由源侧按各自权重加权。
 *
 * <p>{@code mergeKey} 是跨源去重键：向量/关键词/ES 等 chunk 级命中用 {@code docId#index}（同一 chunk 被多源命中时合并）；
 * 图谱命中用其自身唯一 id（因此永远不与 chunk 合并，保持与现有 {@code putIfAbsent} 语义一致）。
 *
 * @param id          展示用唯一 id（向量=embeddingId；关键词="keyword:"+key；ES="es:"+key；图谱="graph:..."）
 * @param mergeKey    跨源融合去重键
 * @param score       已加权分数
 * @param docId       文档 id（图谱命中可能为 null）
 * @param displayName 文档展示名
 * @param category    分类
 * @param index       chunk 序号
 * @param text        chunk 文本
 * @param source      来源标记：vector | keyword | es | graph
 * @param shared      该命中是否来自共享库保留分区 {@code __public__}（true=共享/public，false=当前租户/tenant）。
 *                    融合层据此贯穿 visibility；共享库关闭时恒 false。
 */
public record RetrievalHit(
        String id,
        String mergeKey,
        double score,
        String docId,
        String displayName,
        String category,
        String index,
        String text,
        String source,
        boolean shared) {
}
