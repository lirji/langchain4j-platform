package com.lrj.platform.knowledge.search;

import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存关键词检索源（阶段2，es-hybrid-rerank）。逐字迁出原 keyword 召回：包 {@link KeywordSearchService}，
 * token 重叠打分 × keywordWeight。是 ES 关闭 / ES down 时的本地降级源。
 */
public class InMemoryKeywordRetrievalSource implements RetrievalSource {

    private final KeywordSearchService keywordSearchService;
    private final double keywordWeight;
    private final int keywordTopK;
    private final boolean enabled;

    public InMemoryKeywordRetrievalSource(KeywordSearchService keywordSearchService,
                                          double keywordWeight,
                                          int keywordTopK,
                                          boolean enabled) {
        this.keywordSearchService = keywordSearchService;
        this.keywordWeight = keywordWeight;
        this.keywordTopK = keywordTopK;
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<RetrievalHit> retrieve(RetrievalRequest request) {
        int keywordLimit = Math.max(request.limit(), keywordTopK);
        // KeywordSearchService 已把当前租户分区与（开启时）公共分区合并返回，单条命中的归属靠 segment 的
        // tenantId 元数据还原：等于公共保留分区即为共享命中。公共库关闭时 publicTenantId=null，恒为 tenant。
        String publicTenantId = request.publicTenantId();
        List<RetrievalHit> out = new ArrayList<>();
        for (KeywordSearchService.KeywordHit keywordHit : keywordSearchService.search(
                request.query(), keywordLimit, request.category(), publicTenantId)) {
            TextSegment segment = keywordHit.segment();
            String innerKey = Segments.key(segment, "keyword");
            String id = "keyword:" + innerKey;
            String mergeKey = Segments.key(segment, id);
            double score = keywordHit.score() * keywordWeight;
            if (segment == null || segment.metadata() == null) {
                out.add(new RetrievalHit(id, mergeKey, score, null, null, null, null, null, "keyword", false));
            } else {
                boolean shared = publicTenantId != null
                        && publicTenantId.equals(segment.metadata().getString("tenantId"));
                out.add(new RetrievalHit(
                        id,
                        mergeKey,
                        score,
                        segment.metadata().getString("docId"),
                        segment.metadata().getString("displayName"),
                        segment.metadata().getString("category"),
                        segment.metadata().getString("index"),
                        segment.text(),
                        "keyword",
                        shared));
            }
        }
        return out;
    }
}
