package com.lrj.platform.knowledge.search;

import com.lrj.platform.knowledge.es.EsGateway;
import com.lrj.platform.knowledge.es.EsRagProperties;
import com.lrj.platform.knowledge.es.EsSearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * ES 全文检索源（阶段3，es-hybrid-rerank）。BM25 召回，mergeKey 用 {@code docId#index} 与向量/内存关键词对齐
 * （同 chunk 跨源可合并为 hybrid）。
 *
 * <p>量纲处理：BM25 无上界，{@code weighted_max} 融合下按本次结果最高分归一到 [0,1]（{@code normalize-score}）再乘 es-weight；
 * {@code rrf} 融合只看名次、忽略分值。ES 查询失败时降级返回空（内存关键词源仍独立兜底）。
 */
public class EsKeywordRetrievalSource implements RetrievalSource {

    private static final Logger log = LoggerFactory.getLogger(EsKeywordRetrievalSource.class);

    private final EsGateway gateway;
    private final EsRagProperties props;
    private final double esWeight;

    public EsKeywordRetrievalSource(EsGateway gateway, EsRagProperties props, double esWeight) {
        this.gateway = gateway;
        this.props = props;
        this.esWeight = Double.isFinite(esWeight) && esWeight >= 0 ? esWeight : 1.0;
    }

    @Override
    public String name() {
        return "es";
    }

    @Override
    public boolean enabled() {
        return props.isQueryActive();
    }

    @Override
    public List<RetrievalHit> retrieve(RetrievalRequest request) {
        int limit = Math.max(1, request.limit());
        // 当前租户分区（shared=false）与公共保留分区（shared=true）分开保留，便于逐条标注 visibility；
        // gateway.search 已按传入 tenantId term 隔离。归一分母 max 仍跨两批统一，量纲行为不变。
        List<EsSearchHit> tenantHits = new ArrayList<>();
        List<EsSearchHit> publicHits = new ArrayList<>();
        try {
            tenantHits.addAll(gateway.search(request.tenantId(), request.category(), request.query(), limit));
            if (request.publicTenantId() != null && !request.publicTenantId().isBlank()) {
                publicHits.addAll(gateway.search(request.publicTenantId(), request.category(), request.query(), limit));
            }
        } catch (RuntimeException e) {
            log.warn("ES search failed, degrade to no ES hits: {}", e.toString());
            return List.of();
        }
        if (tenantHits.isEmpty() && publicHits.isEmpty()) {
            return List.of();
        }
        double max = 0.0;
        for (EsSearchHit h : tenantHits) {
            max = Math.max(max, h.score());
        }
        for (EsSearchHit h : publicHits) {
            max = Math.max(max, h.score());
        }
        boolean normalize = props.isNormalizeScore() && max > 0.0;
        List<RetrievalHit> out = new ArrayList<>(tenantHits.size() + publicHits.size());
        appendHits(tenantHits, false, normalize, max, out);
        appendHits(publicHits, true, normalize, max, out);
        return out;
    }

    private void appendHits(List<EsSearchHit> hits, boolean shared, boolean normalize, double max, List<RetrievalHit> out) {
        for (EsSearchHit h : hits) {
            double base = normalize ? h.score() / max : h.score();
            double score = base * esWeight;
            String mergeKey = h.docId() + "#" + h.index();
            String id = "es:" + mergeKey;
            out.add(new RetrievalHit(id, mergeKey, score, h.docId(), h.displayName(), h.category(), h.index(), h.text(), "es", shared));
        }
    }
}
