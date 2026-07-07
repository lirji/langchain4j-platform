package com.lrj.platform.knowledge;

import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.graph.GraphSearchService;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import com.lrj.platform.knowledge.store.SingleEmbeddingStoreRouter;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class KnowledgeQueryService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQueryService.class);

    private final EmbeddingStoreRouter storeRouter;
    private final EmbeddingModel embeddingModel;
    private final KeywordSearchService keywordSearchService;
    private final GraphSearchService graphSearchService;
    private final boolean hybridEnabled;
    private final boolean graphIncludedInQuery;
    private final int defaultTopK;
    private final double defaultMinScore;
    private final int keywordTopK;
    private final int graphTopK;
    private final double vectorWeight;
    private final double keywordWeight;
    private final double graphWeight;

    @Autowired
    public KnowledgeQueryService(EmbeddingStoreRouter storeRouter,
                                 EmbeddingModel embeddingModel,
                                 KeywordSearchService keywordSearchService,
                                 @Value("${app.rag.query.top-k:5}") int defaultTopK,
                                 @Value("${app.rag.query.min-score:0.0}") double defaultMinScore,
                                 @Value("${app.rag.hybrid.enabled:true}") boolean hybridEnabled,
                                 @Value("${app.rag.hybrid.keyword-top-k:${app.rag.query.top-k:5}}") int keywordTopK,
                                 ObjectProvider<GraphSearchService> graphSearchServiceProvider,
                                 @Value("${app.rag.graph.include-in-query:${app.rag.graph.enabled:false}}") boolean graphIncludedInQuery,
                                 @Value("${app.rag.graph.query-top-k:${app.rag.graph.max-triples:20}}") int graphTopK,
                                 @Value("${app.rag.ranking.vector-weight:1.0}") double vectorWeight,
                                 @Value("${app.rag.ranking.keyword-weight:1.0}") double keywordWeight,
                                 @Value("${app.rag.ranking.graph-weight:1.0}") double graphWeight) {
        this(storeRouter,
                embeddingModel,
                keywordSearchService,
                defaultTopK,
                defaultMinScore,
                hybridEnabled,
                keywordTopK,
                graphSearchServiceProvider == null ? null : graphSearchServiceProvider.getIfAvailable(),
                graphIncludedInQuery,
                graphTopK,
                vectorWeight,
                keywordWeight,
                graphWeight);
    }

    public KnowledgeQueryService(EmbeddingStore<TextSegment> embeddingStore,
                                 EmbeddingModel embeddingModel,
                                 KeywordSearchService keywordSearchService,
                                 int defaultTopK,
                                 double defaultMinScore,
                                 boolean hybridEnabled,
                                 int keywordTopK) {
        this(new SingleEmbeddingStoreRouter(embeddingStore, embeddingModel.dimension()),
                embeddingModel,
                keywordSearchService,
                defaultTopK,
                defaultMinScore,
                hybridEnabled,
                keywordTopK,
                (GraphSearchService) null,
                false,
                0,
                1.0,
                1.0,
                1.0);
    }

    public KnowledgeQueryService(EmbeddingStore<TextSegment> embeddingStore,
                                 EmbeddingModel embeddingModel,
                                 KeywordSearchService keywordSearchService,
                                 int defaultTopK,
                                 double defaultMinScore,
                                 boolean hybridEnabled,
                                 int keywordTopK,
                                 GraphSearchService graphSearchService,
                                 boolean graphIncludedInQuery,
                                 int graphTopK) {
        this(new SingleEmbeddingStoreRouter(embeddingStore, embeddingModel.dimension()),
                embeddingModel,
                keywordSearchService,
                defaultTopK,
                defaultMinScore,
                hybridEnabled,
                keywordTopK,
                graphSearchService,
                graphIncludedInQuery,
                graphTopK,
                1.0,
                1.0,
                1.0);
    }

    public KnowledgeQueryService(EmbeddingStore<TextSegment> embeddingStore,
                                 EmbeddingModel embeddingModel,
                                 KeywordSearchService keywordSearchService,
                                 int defaultTopK,
                                 double defaultMinScore,
                                 boolean hybridEnabled,
                                 int keywordTopK,
                                 GraphSearchService graphSearchService,
                                 boolean graphIncludedInQuery,
                                 int graphTopK,
                                 double vectorWeight,
                                 double keywordWeight,
                                 double graphWeight) {
        this(new SingleEmbeddingStoreRouter(embeddingStore, embeddingModel.dimension()),
                embeddingModel,
                keywordSearchService,
                defaultTopK,
                defaultMinScore,
                hybridEnabled,
                keywordTopK,
                graphSearchService,
                graphIncludedInQuery,
                graphTopK,
                vectorWeight,
                keywordWeight,
                graphWeight);
    }

    public KnowledgeQueryService(EmbeddingStoreRouter storeRouter,
                                 EmbeddingModel embeddingModel,
                                 KeywordSearchService keywordSearchService,
                                 int defaultTopK,
                                 double defaultMinScore,
                                 boolean hybridEnabled,
                                 int keywordTopK,
                                 GraphSearchService graphSearchService,
                                 boolean graphIncludedInQuery,
                                 int graphTopK,
                                 double vectorWeight,
                                 double keywordWeight,
                                 double graphWeight) {
        this.storeRouter = storeRouter;
        this.embeddingModel = embeddingModel;
        this.keywordSearchService = keywordSearchService;
        this.graphSearchService = graphSearchService;
        this.defaultTopK = defaultTopK;
        this.defaultMinScore = defaultMinScore;
        this.hybridEnabled = hybridEnabled;
        this.graphIncludedInQuery = graphIncludedInQuery;
        this.keywordTopK = keywordTopK;
        this.graphTopK = Math.max(1, graphTopK);
        this.vectorWeight = normalizeWeight(vectorWeight);
        this.keywordWeight = normalizeWeight(keywordWeight);
        this.graphWeight = normalizeWeight(graphWeight);
    }

    public QueryResult query(String query, Integer topK, Double minScore, String category) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        String tenantId = TenantContext.current().tenantId();
        int limit = topK != null && topK > 0 ? topK : defaultTopK;
        double floor = minScore != null && minScore >= 0 ? minScore : defaultMinScore;

        Filter filter = metadataKey("tenantId").isEqualTo(tenantId);
        if (category != null && !category.isBlank()) {
            filter = Filter.and(filter, metadataKey("category").isEqualTo(category));
        }

        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(limit)
                .minScore(floor)
                .filter(filter)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = storeRouter
                .forTenant(tenantId, embeddingModel.dimension())
                .search(request)
                .matches();
        Map<String, Hit> merged = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            Hit hit = toVectorHit(match, segment);
            merged.put(segmentKey(segment, hit.id()), hit);
        }
        if (hybridEnabled) {
            int keywordLimit = Math.max(limit, keywordTopK);
            for (KeywordSearchService.KeywordHit keywordHit : keywordSearchService.search(query, keywordLimit, category)) {
                Hit hit = toKeywordHit(keywordHit);
                String key = segmentKey(keywordHit.segment(), hit.id());
                merged.merge(key, hit, KnowledgeQueryService::mergeHits);
            }
        }
        if (graphIncludedInQuery && graphSearchService != null) {
            int graphLimit = Math.max(limit, graphTopK);
            for (GraphSearchService.GraphHit graphHit : graphSearchService.query(query, null, graphLimit, category).hits()) {
                Hit hit = toGraphHit(graphHit);
                merged.putIfAbsent(hit.id(), hit);
            }
        }
        List<Hit> hits = new ArrayList<>(merged.values()).stream()
                .sorted(Comparator.comparingDouble(KnowledgeQueryService::scoreOrZero).reversed())
                .limit(limit)
                .toList();
        log.info("knowledge query tenant={} topK={} minScore={} category={} -> {} hits",
                tenantId, limit, floor, category, hits.size());
        return new QueryResult(query, tenantId, hits);
    }

    private Hit toVectorHit(EmbeddingMatch<TextSegment> match, TextSegment segment) {
        double score = weighted(match.score(), vectorWeight);
        if (segment == null || segment.metadata() == null) {
            return new Hit(match.embeddingId(), score, null, null, null, null, null, "vector");
        }
        return new Hit(
                match.embeddingId(),
                score,
                segment.metadata().getString("docId"),
                segment.metadata().getString("displayName"),
                segment.metadata().getString("category"),
                segment.metadata().getString("index"),
                segment.text(),
                "vector");
    }

    private Hit toKeywordHit(KeywordSearchService.KeywordHit keywordHit) {
        TextSegment segment = keywordHit.segment();
        String key = segmentKey(segment, "keyword");
        double score = weighted(keywordHit.score(), keywordWeight);
        if (segment == null || segment.metadata() == null) {
            return new Hit("keyword:" + key, score, null, null, null, null, null, "keyword");
        }
        return new Hit(
                "keyword:" + key,
                score,
                segment.metadata().getString("docId"),
                segment.metadata().getString("displayName"),
                segment.metadata().getString("category"),
                segment.metadata().getString("index"),
                segment.text(),
                "keyword");
    }

    private Hit toGraphHit(GraphSearchService.GraphHit graphHit) {
        SourceParts source = SourceParts.from(graphHit.sourceId());
        return new Hit(
                "graph:" + graphHit.sourceId() + ":" + graphHit.subject() + ":" + graphHit.relation() + ":" + graphHit.object(),
                weighted(0.75, graphWeight),
                null,
                source.displayName(),
                graphHit.category(),
                source.index(),
                graphHit.text(),
                "graph");
    }

    private static Hit mergeHits(Hit vectorHit, Hit keywordHit) {
        return new Hit(
                vectorHit.id(),
                Math.max(scoreOrZero(vectorHit), scoreOrZero(keywordHit)),
                firstNonNull(vectorHit.docId(), keywordHit.docId()),
                firstNonNull(vectorHit.displayName(), keywordHit.displayName()),
                firstNonNull(vectorHit.category(), keywordHit.category()),
                firstNonNull(vectorHit.index(), keywordHit.index()),
                firstNonNull(vectorHit.text(), keywordHit.text()),
                "hybrid");
    }

    private static String segmentKey(TextSegment segment, String fallback) {
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

    private static double scoreOrZero(Hit hit) {
        return hit.score() == null ? 0.0 : hit.score();
    }

    private static double weighted(double score, double weight) {
        return score * weight;
    }

    private static double normalizeWeight(double weight) {
        if (!Double.isFinite(weight) || weight < 0.0) {
            return 1.0;
        }
        return weight;
    }

    private static String firstNonNull(String left, String right) {
        return left != null ? left : right;
    }

    private record SourceParts(String displayName, String index) {
        static SourceParts from(String sourceId) {
            if (sourceId == null || sourceId.isBlank()) {
                return new SourceParts(null, null);
            }
            int sep = sourceId.lastIndexOf('#');
            if (sep < 0 || sep == sourceId.length() - 1) {
                return new SourceParts(sourceId, null);
            }
            return new SourceParts(sourceId.substring(0, sep), sourceId.substring(sep + 1));
        }
    }

    public record QueryResult(String query, String tenantId, List<Hit> hits) {}

    public record Hit(String id, Double score, String docId, String displayName,
                      String category, String index, String text, String source) {}
}
