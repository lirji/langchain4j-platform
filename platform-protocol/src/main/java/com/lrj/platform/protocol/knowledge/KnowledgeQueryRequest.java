package com.lrj.platform.protocol.knowledge;

/**
 * knowledge-service RAG 检索的查询请求（跨服务 DTO）。
 * 承载 {@code query} 查询词、{@code topK} 返回条数、{@code minScore} 相关度阈值与 {@code category} 类目过滤，
 * 其应答见 {@link KnowledgeQueryReply}。
 */
public record KnowledgeQueryRequest(String query, Integer topK, Double minScore, String category) {
}
