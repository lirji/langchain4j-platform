package com.lrj.platform.knowledge;

import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.graph.GraphSearchService;
import com.lrj.platform.knowledge.query.NoopQueryExpander;
import com.lrj.platform.knowledge.query.QueryExpander;
import com.lrj.platform.knowledge.rerank.NoopReranker;
import com.lrj.platform.knowledge.rerank.Reranker;
import com.lrj.platform.knowledge.search.FusionStrategy;
import com.lrj.platform.knowledge.search.GraphRetrievalSource;
import com.lrj.platform.knowledge.search.HybridFusionService;
import com.lrj.platform.knowledge.search.InMemoryKeywordRetrievalSource;
import com.lrj.platform.knowledge.search.RetrievalHit;
import com.lrj.platform.knowledge.search.RetrievalRequest;
import com.lrj.platform.knowledge.search.RetrievalSource;
import com.lrj.platform.knowledge.search.VectorRetrievalSource;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import com.lrj.platform.knowledge.store.SingleEmbeddingStoreRouter;
import com.lrj.platform.knowledge.authz.AuthzMode;
import com.lrj.platform.knowledge.authz.KnowledgeAuthz;
import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import com.lrj.platform.knowledge.authz.RagAuthzProperties;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索编排器（es-hybrid-rerank 重构后）。职责：校验 → topK/minScore → 查询扩展 → 收集各启用
 * {@link RetrievalSource}（向量 / 内存关键词 / ES / 图谱）的命中 → {@link HybridFusionService} 融合 →
 * {@link Reranker} 重排。ES 关闭时源列表与融合语义（weighted_max）与重构前逐字等价。
 */
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

    // 内部检索源：由现有协作者在终端构造器构建，测试构造器天然拿到默认三源，无需改测试。
    private final RetrievalSource vectorSource;
    private final RetrievalSource keywordSource;
    private final RetrievalSource graphSource;

    // 检索增强协作者：默认 Noop / 空扩展源 / weighted_max，仅 Spring @Autowired 路径按开关注入真实实现。
    private QueryExpander queryExpander = new NoopQueryExpander();
    private Reranker reranker = new NoopReranker();
    private HybridFusionService fusion = new HybridFusionService();
    // 额外源（ES 等 Spring 装配的 RetrievalSource bean），插在关键词与图谱之间。
    private List<RetrievalSource> extraSources = List.of();
    private FusionStrategy fusionStrategy = FusionStrategy.WEIGHTED_MAX;
    private int rrfK = 60;
    // 公共/共享库（默认关闭 → 行为与引入前完全一致）。开启时查询在隔离查各自租户的基础上，
    // 把保留公共分区 publicKbTenantId 的命中也并入（读取不破坏隔离）。
    private boolean publicKbEnabled = false;
    private String publicKbTenantId = PublicKb.TENANT_ID;
    // 细粒度读授权（接 auth-platform）：默认 Noop（关时不过滤），app.rag.authz.mode=shadow|enforce 时注入 Real。
    private KnowledgeAuthz knowledgeAuthz = new NoopKnowledgeAuthz();
    // 授权候选/批次上限：默认值即安全；生产由 Spring 注入 app.rag.authz.* 的 RagAuthzProperties。
    private RagAuthzProperties authzProps = new RagAuthzProperties();

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
                                 @Value("${app.rag.ranking.graph-weight:1.0}") double graphWeight,
                                 ObjectProvider<QueryExpander> queryExpanderProvider,
                                 ObjectProvider<Reranker> rerankerProvider,
                                 ObjectProvider<HybridFusionService> fusionProvider,
                                 ObjectProvider<RetrievalSource> retrievalSourceProvider,
                                 @Value("${app.rag.fusion.strategy:}") String fusionStrategyProp,
                                 @Value("${app.rag.fusion.rrf-k:60}") int rrfK,
                                 @Value("${app.rag.es.enabled:false}") boolean esEnabled,
                                 @Value("${app.rag.es.query-enabled:true}") boolean esQueryEnabled,
                                 @Value("${app.rag.public.enabled:false}") boolean publicKbEnabled,
                                 @Value("${app.rag.public.tenant-id:__public__}") String publicKbTenantId) {
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
        if (queryExpanderProvider != null) {
            this.queryExpander = queryExpanderProvider.getIfAvailable(NoopQueryExpander::new);
        }
        if (rerankerProvider != null) {
            this.reranker = rerankerProvider.getIfAvailable(NoopReranker::new);
        }
        if (fusionProvider != null) {
            this.fusion = fusionProvider.getIfAvailable(HybridFusionService::new);
        }
        this.extraSources = retrievalSourceProvider == null ? List.of() : retrievalSourceProvider.orderedStream().toList();
        this.rrfK = rrfK;
        // 有效默认（#5）：只有 ES 真正参与查询（enabled 且 query-enabled）才翻 RRF；只写不查/关闭时保持 weighted_max。
        this.fusionStrategy = FusionStrategy.effectiveDefault(fusionStrategyProp, esEnabled, esQueryEnabled);
        this.publicKbEnabled = publicKbEnabled;
        if (publicKbTenantId != null && !publicKbTenantId.isBlank()) {
            this.publicKbTenantId = publicKbTenantId;
        }
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
        this.vectorSource = new VectorRetrievalSource(this.storeRouter, this.embeddingModel, this.vectorWeight);
        this.keywordSource = new InMemoryKeywordRetrievalSource(
                this.keywordSearchService, this.keywordWeight, this.keywordTopK, this.hybridEnabled);
        this.graphSource = new GraphRetrievalSource(
                this.graphSearchService, this.graphWeight, this.graphTopK, this.graphIncludedInQuery);
    }

    public QueryResult query(String query, Integer topK, Double minScore, String category) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        // 请求期一次性快照可信身份（tenantId/userId 同源于已验签的内部 JWT），避免链路中二次读取上下文。
        var ctx = TenantContext.current();
        String tenantId = ctx.tenantId();
        String userId = ctx.userId();
        int limit = topK != null && topK > 0 ? topK : defaultTopK;
        double floor = minScore != null && minScore >= 0 ? minScore : defaultMinScore;

        // 候选池：授权关闭时 = topK × max(1, rerank 放大)（与接入前一致）；授权开启时有界 overfetch（见方法注释）。
        int poolLimit = computePoolLimit(limit);

        // 查询扩展：原 query + 若干变体；关闭时只有原 query，行为不变。
        List<String> variants = new ArrayList<>();
        for (String variant : queryExpander.expand(query)) {
            if (variant != null && !variant.isBlank()) {
                variants.add(variant);
            }
        }
        if (variants.isEmpty()) {
            variants.add(query);
        }

        // 公共库开启且调用方不是公共分区本身时，把公共租户带下去，各源据此并入公共命中。
        String reqPublicTenant = (publicKbEnabled && publicKbTenantId != null
                && !publicKbTenantId.equals(tenantId)) ? publicKbTenantId : null;
        RetrievalRequest request = new RetrievalRequest(
                query, variants, tenantId, category, poolLimit, floor, reqPublicTenant);

        // 顺序敏感（weighted_max）：向量 → 关键词 → ES/额外源 → 图谱，复刻原合并顺序。
        List<List<RetrievalHit>> groups = new ArrayList<>();
        groups.add(vectorSource.retrieve(request));
        if (keywordSource.enabled()) {
            groups.add(keywordSource.retrieve(request));
        }
        for (RetrievalSource extra : extraSources) {
            if (extra.enabled()) {
                groups.add(extra.retrieve(request));
            }
        }
        if (graphSource.enabled()) {
            groups.add(graphSource.retrieve(request));
        }

        List<Hit> candidates = fusion.fuse(groups, fusionStrategy, rrfK);
        // 细粒度读授权过滤（融合后、重排前；关闭时直通）。
        candidates = filterReadable(tenantId, userId, candidates);
        List<Hit> hits = reranker.rerank(query, candidates, limit);
        log.info("knowledge query tenant={} topK={} minScore={} category={} strategy={} variants={} -> {} hits",
                tenantId, limit, floor, category, fusionStrategy, variants.size(), hits.size());
        return new QueryResult(query, tenantId, hits);
    }

    /**
     * 候选池大小。授权关闭时：{@code = topK × max(1, rerank 放大)}（与接入前逐字一致）。
     * 授权开启时（shadow/enforce）：放大倍数取 rerank 与 {@code authz.candidate-multiplier} 的 <em>max</em>
     * （不相乘），并封顶到 {@code authz.max-candidates}——给授权过滤留 overfetch 余量以减少 underfill，
     * 同时对 checkBulk 候选数设绝对上限（纵深防御）。授权关闭路径完全不受新上限影响。
     */
    private int computePoolLimit(int limit) {
        int rerankMult = Math.max(1, reranker.retrieveMultiplier());
        if (!knowledgeAuthz.enabled()) {
            return Math.max(limit, limit * rerankMult);
        }
        int maxCandidates = Math.max(1, authzProps.getMaxCandidates());
        int base = Math.min(limit, maxCandidates);
        int mult = Math.max(rerankMult, Math.max(1, authzProps.getCandidateMultiplier()));
        long raw = (long) base * mult;
        return (int) Math.max(1, Math.min(raw, maxCandidates));
    }

    /**
     * 细粒度读授权过滤（app.rag.authz.mode=shadow|enforce 时生效）：对非公共候选按当前用户 view 判权，
     * 公共库命中（shared）短路放行。关闭时直通。候选池 poolLimit 有界，判权侧按 bulk-size 分批。
     */
    private List<Hit> filterReadable(String tenantId, String userId, List<Hit> candidates) {
        if (!knowledgeAuthz.enabled() || candidates.isEmpty()) {
            return candidates;
        }
        Set<String> docIds = candidates.stream()
                .filter(h -> !h.shared() && h.docId() != null)
                .map(Hit::docId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // 触发判权（shadow 记 would_filter 指标并返回全集；enforce 返回可读子集）。
        Set<String> readable = docIds.isEmpty()
                ? Set.of()
                : knowledgeAuthz.filterReadable(tenantId, userId, docIds);
        // shadow/disabled 不拦截（指标已记录）；仅 enforce 真过滤。
        if (knowledgeAuthz.mode() != AuthzMode.ENFORCE) {
            return candidates;
        }
        // enforce：公共库放行；有 docId 按可读过滤；【无 docId 命中（如 GraphRAG 三元组）无法资源级判权 → fail-closed 丢弃】。
        List<Hit> out = candidates.stream()
                .filter(h -> h.shared() || (h.docId() != null && readable.contains(h.docId())))
                .toList();
        // 无 docId 命中（图谱三元组、或缺 metadata 的向量/关键词命中）无法资源级判权 → fail-closed 丢弃并计数。
        long droppedNoDocId = candidates.stream().filter(h -> !h.shared() && h.docId() == null).count();
        if (droppedNoDocId > 0) {
            log.info("authz enforce dropped {} hits without docId (no resource-level authz; incl. graph triples / metadata-less vector·keyword hits)",
                    droppedNoDocId);
        }
        log.debug("authz enforce filter tenant={} user={} candidates={} -> kept={}", tenantId, userId, candidates.size(), out.size());
        return out;
    }

    /** 测试注入自定义扩展器（生产由 Spring @Autowired 按开关装配）。 */
    void setQueryExpander(QueryExpander queryExpander) {
        this.queryExpander = queryExpander == null ? new NoopQueryExpander() : queryExpander;
    }

    /** 测试注入公共库开关（生产由 @Autowired 构造器按 app.rag.public.* 装配）。 */
    void setPublicKb(boolean enabled, String tenantId) {
        this.publicKbEnabled = enabled;
        if (tenantId != null && !tenantId.isBlank()) {
            this.publicKbTenantId = tenantId;
        }
    }

    /** 测试注入自定义重排器（生产由 Spring @Autowired 按开关装配）。 */
    void setReranker(Reranker reranker) {
        this.reranker = reranker == null ? new NoopReranker() : reranker;
    }

    /** 注入细粒度读授权（生产由 Spring @Autowired 按 app.rag.authz.mode 装配；测试保持默认 Noop）。 */
    @Autowired(required = false)
    public void setKnowledgeAuthz(KnowledgeAuthz knowledgeAuthz) {
        if (knowledgeAuthz != null) {
            this.knowledgeAuthz = knowledgeAuthz;
        }
    }

    /** 注入授权候选/批次上限（生产由 Spring @Autowired 注入 RagAuthzProperties；测试保持默认值）。 */
    @Autowired(required = false)
    public void setRagAuthzProperties(RagAuthzProperties authzProps) {
        if (authzProps != null) {
            this.authzProps = authzProps;
        }
    }

    /** 测试注入额外检索源（如 ES 源的 fake）。 */
    void setExtraSources(List<RetrievalSource> sources) {
        this.extraSources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** 测试选择融合策略。 */
    void setFusionStrategy(FusionStrategy strategy) {
        if (strategy != null) {
            this.fusionStrategy = strategy;
        }
    }

    private static double normalizeWeight(double weight) {
        if (!Double.isFinite(weight) || weight < 0.0) {
            return 1.0;
        }
        return weight;
    }

    /** 共享/公共库读并入是否开启（供 {@code GET /rag/config} 只读回显运行时状态）。 */
    public boolean publicKbEnabled() {
        return publicKbEnabled;
    }

    public record QueryResult(String query, String tenantId, List<Hit> hits) {}

    /**
     * 融合/重排后的单条命中。{@code shared} 为末尾加法字段：true=命中来自共享库保留分区 {@code __public__}，
     * false=来自当前租户。经 {@code KnowledgeQueryController.toReply} 映射为 {@code KnowledgeHit.visibility}。
     */
    public record Hit(String id, Double score, String docId, String displayName,
                      String category, String index, String text, String source, boolean shared) {}
}
