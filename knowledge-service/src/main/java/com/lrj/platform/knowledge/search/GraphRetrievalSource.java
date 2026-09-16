package com.lrj.platform.knowledge.search;

import com.lrj.platform.knowledge.graph.GraphSearchService;
import com.lrj.platform.knowledge.graph.GraphSourceId;

import java.util.ArrayList;
import java.util.List;

/**
 * 图谱检索源（阶段2，es-hybrid-rerank）。逐字迁出原 graph 召回：GraphRAG 三元组命中，固定分 0.75×graphWeight，
 * 用自身唯一 id 作 mergeKey，因此在融合层永远独立、不与 chunk 命中合并（复刻原 putIfAbsent 语义）。
 *
 * <p>命中会带上从 {@code sourceId} 还原出的 {@code docId} / {@code version}
 * （见 {@link GraphSourceId}），因而与向量/ES 命中一样进入两道过滤：按 Registry 当前版本丢弃过期命中、
 * 以及 enforce 档的文档级判权。{@code requireProvenance} 为 true 时，还原不出版本归属的历史三元组
 * 直接丢弃——它们既证不明新鲜度也证不明可读性，放行等于绕过这两道过滤。
 */
public class GraphRetrievalSource implements RetrievalSource {

    private final GraphSearchService graphSearchService;
    private final double graphWeight;
    private final int graphTopK;
    private final boolean enabled;
    private final boolean requireProvenance;

    public GraphRetrievalSource(GraphSearchService graphSearchService,
                                double graphWeight,
                                int graphTopK,
                                boolean includedInQuery) {
        this(graphSearchService, graphWeight, graphTopK, includedInQuery, false);
    }

    public GraphRetrievalSource(GraphSearchService graphSearchService,
                                double graphWeight,
                                int graphTopK,
                                boolean includedInQuery,
                                boolean requireProvenance) {
        this.graphSearchService = graphSearchService;
        this.graphWeight = graphWeight;
        this.graphTopK = graphTopK;
        this.enabled = includedInQuery && graphSearchService != null;
        this.requireProvenance = requireProvenance;
    }

    @Override
    public String name() {
        return "graph";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<RetrievalHit> retrieve(RetrievalRequest request) {
        int graphLimit = Math.max(request.limit(), graphTopK);
        List<RetrievalHit> out = new ArrayList<>();
        for (GraphSearchService.GraphHit graphHit : graphSearchService.query(request.query(), null, graphLimit, request.category()).hits()) {
            GraphSourceId source = GraphSourceId.parse(graphHit.sourceId());
            if (requireProvenance && !source.hasProvenance()) {
                continue;
            }
            String id = "graph:" + graphHit.sourceId() + ":" + graphHit.subject()
                    + ":" + graphHit.relation() + ":" + graphHit.object();
            double score = 0.75 * graphWeight;
            // 图谱检索当前未并入公共分区（GraphRetrievalSource 不查 publicTenantId），故 shared 恒 false。
            out.add(new RetrievalHit(id, id, score, source.docId(), source.displayName(),
                    graphHit.category(), source.index(), source.version(), graphHit.text(), "graph", false));
        }
        return out;
    }
}
