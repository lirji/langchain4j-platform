package com.lrj.platform.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.List;

/**
 * 给底层 embedding 模型加「非对称任务前缀」：查询走 {@code queryPrefix}，文档走 {@code documentPrefix}。
 *
 * <p>{@code nomic-embed-text} 等检索型模型要求 {@code "search_query: "} / {@code "search_document: "}
 * 前缀才能对齐 query 与 document 的向量空间、发挥检索效果。约定与 langchain4j 用法一致：
 * <ul>
 *   <li>{@link #embed(String)} —— 裸字符串视为<b>查询</b>（{@code KnowledgeQueryService} 走这里）。</li>
 *   <li>{@link #embed(TextSegment)} / {@link #embedAll(List)} —— {@code TextSegment} 视为<b>文档</b>
 *       （{@code DocumentService} 入库走这里）。</li>
 * </ul>
 *
 * <p><b>只前缀送去 embed 的副本，不改调用方持有的原始 segment</b> —— 入库时存的是原文，
 * 前缀只影响向量计算，检索命中返回的 {@code text} 不含前缀。
 */
public class PrefixingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final String queryPrefix;
    private final String documentPrefix;

    public PrefixingEmbeddingModel(EmbeddingModel delegate, String queryPrefix, String documentPrefix) {
        this.delegate = delegate;
        this.queryPrefix = queryPrefix == null ? "" : queryPrefix;
        this.documentPrefix = documentPrefix == null ? "" : documentPrefix;
    }

    @Override
    public Response<Embedding> embed(String text) {
        return delegate.embed(queryPrefix + text);
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        return delegate.embed(withDocumentPrefix(textSegment));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<TextSegment> prefixed = textSegments.stream().map(this::withDocumentPrefix).toList();
        return delegate.embedAll(prefixed);
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    private TextSegment withDocumentPrefix(TextSegment segment) {
        if (documentPrefix.isEmpty()) {
            return segment;
        }
        String text = documentPrefix + segment.text();
        return segment.metadata() == null
                ? TextSegment.from(text)
                : TextSegment.from(text, segment.metadata());
    }
}
