package com.lrj.platform.knowledge.search;

import com.lrj.platform.knowledge.graph.GraphSearchService;

import java.util.ArrayList;
import java.util.List;

/**
 * 图谱检索源（阶段2，es-hybrid-rerank）。逐字迁出原 graph 召回：GraphRAG 三元组命中，固定分 0.75×graphWeight，
 * 用自身唯一 id 作 mergeKey，因此在融合层永远独立、不与 chunk 命中合并（复刻原 putIfAbsent 语义）。
 */
public class GraphRetrievalSource implements RetrievalSource {

    private final GraphSearchService graphSearchService;
    private final double graphWeight;
    private final int graphTopK;
    private final boolean enabled;

    public GraphRetrievalSource(GraphSearchService graphSearchService,
                                double graphWeight,
                                int graphTopK,
                                boolean includedInQuery) {
        this.graphSearchService = graphSearchService;
        this.graphWeight = graphWeight;
        this.graphTopK = graphTopK;
        this.enabled = includedInQuery && graphSearchService != null;
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
            String id = "graph:" + graphHit.sourceId() + ":" + graphHit.subject()
                    + ":" + graphHit.relation() + ":" + graphHit.object();
            SourceParts source = SourceParts.from(graphHit.sourceId());
            double score = 0.75 * graphWeight;
            // 图谱检索当前未并入公共分区（GraphRetrievalSource 不查 publicTenantId），故 shared 恒 false。
            out.add(new RetrievalHit(id, id, score, null, source.displayName(), graphHit.category(), source.index(), graphHit.text(), "graph", false));
        }
        return out;
    }

    /** 复刻原 {@code KnowledgeQueryService.SourceParts}：sourceId 形如 displayName#index。 */
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
}
