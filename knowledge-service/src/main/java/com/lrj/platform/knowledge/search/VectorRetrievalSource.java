package com.lrj.platform.knowledge.search;

import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.Filter;

import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 向量检索源（阶段2，es-hybrid-rerank）。逐字迁出原 {@code KnowledgeQueryService} 的向量召回：
 * 对每个 query 变体嵌入、按 tenantId(+category) 过滤、search(maxResults=limit, minScore)。多变体去重交给融合层。
 */
public class VectorRetrievalSource implements RetrievalSource {

    private final EmbeddingStoreRouter storeRouter;
    private final EmbeddingModel embeddingModel;
    private final double vectorWeight;

    public VectorRetrievalSource(EmbeddingStoreRouter storeRouter, EmbeddingModel embeddingModel, double vectorWeight) {
        this.storeRouter = storeRouter;
        this.embeddingModel = embeddingModel;
        this.vectorWeight = vectorWeight;
    }

    @Override
    public String name() {
        return "vector";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<RetrievalHit> retrieve(RetrievalRequest request) {
        List<RetrievalHit> out = new ArrayList<>();
        // 隔离查各自租户（shared=false）；公共库开启时再对保留公共分区查一次并入并标 shared=true
        // （各分区独立 collection + 独立 tenantId 过滤，隔离不破）。
        searchTenant(request.tenantId(), request, out, false);
        if (request.publicTenantId() != null && !request.publicTenantId().isBlank()) {
            searchTenant(request.publicTenantId(), request, out, true);
        }
        return out;
    }

    private void searchTenant(String tenantId, RetrievalRequest request, List<RetrievalHit> out, boolean shared) {
        Filter filter = metadataKey("tenantId").isEqualTo(tenantId);
        if (request.category() != null && !request.category().isBlank()) {
            filter = Filter.and(filter, metadataKey("category").isEqualTo(request.category()));
        }
        for (String variant : request.variants()) {
            if (variant == null || variant.isBlank()) {
                continue;
            }
            Embedding queryEmbedding = embeddingModel.embed(variant).content();
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(request.limit())
                    .minScore(request.minScore())
                    .filter(filter)
                    .build();
            List<EmbeddingMatch<TextSegment>> matches = storeRouter
                    .forTenant(tenantId, embeddingModel.dimension())
                    .search(searchRequest)
                    .matches();
            for (EmbeddingMatch<TextSegment> match : matches) {
                out.add(toHit(match, match.embedded(), shared));
            }
        }
    }

    private RetrievalHit toHit(EmbeddingMatch<TextSegment> match, TextSegment segment, boolean shared) {
        double score = match.score() * vectorWeight;
        String key = Segments.key(segment, match.embeddingId());
        if (segment == null || segment.metadata() == null) {
            return new RetrievalHit(match.embeddingId(), key, score, null, null, null, null, null, "vector", shared);
        }
        return new RetrievalHit(
                match.embeddingId(),
                key,
                score,
                segment.metadata().getString("docId"),
                segment.metadata().getString("displayName"),
                segment.metadata().getString("category"),
                segment.metadata().getString("index"),
                segment.text(),
                "vector",
                shared);
    }
}
