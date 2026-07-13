package com.lrj.platform.knowledge.search;

import com.lrj.platform.knowledge.KnowledgeQueryService.Hit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多源融合（阶段2，es-hybrid-rerank）。入参 {@code orderedGroups} 按源顺序排列（向量→关键词→ES→图谱），
 * 顺序仅对 {@code WEIGHTED_MAX} 生效。输出按融合分降序，不截断（截断交给上游 rerank）。
 *
 * <ul>
 *   <li>{@code WEIGHTED_MAX}：逐字复刻原 {@code KnowledgeQueryService} 的 LinkedHashMap 合并——
 *       同源同 chunk 取 {@code keepHigher}；跨源同 chunk 取 {@code max} 分并标 {@code hybrid}；图谱按自身 id 独立 {@code putIfAbsent}。
 *   <li>{@code RRF}：各源按源内名次贡献 {@code 1/(k+rank)}，命中多源者标 {@code hybrid}；免疫 BM25/余弦量纲差。
 * </ul>
 */
@Service
public class HybridFusionService {

    public List<Hit> fuse(List<List<RetrievalHit>> orderedGroups, FusionStrategy strategy, int rrfK) {
        if (strategy == FusionStrategy.RRF) {
            return fuseRrf(orderedGroups, rrfK);
        }
        return fuseWeightedMax(orderedGroups);
    }

    private List<Hit> fuseWeightedMax(List<List<RetrievalHit>> orderedGroups) {
        LinkedHashMap<String, Hit> merged = new LinkedHashMap<>();
        for (List<RetrievalHit> group : orderedGroups) {
            for (RetrievalHit rh : group) {
                Hit incoming = toHit(rh);
                if ("graph".equals(rh.source())) {
                    merged.putIfAbsent(rh.mergeKey(), incoming);
                } else {
                    merged.merge(rh.mergeKey(), incoming, (existing, in) ->
                            existing.source().equals(in.source()) ? keepHigher(existing, in) : mergeHits(existing, in));
                }
            }
        }
        List<Hit> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparingDouble(HybridFusionService::scoreOrZero).reversed());
        return out;
    }

    private List<Hit> fuseRrf(List<List<RetrievalHit>> orderedGroups, int rrfK) {
        int k = Math.max(1, rrfK);
        LinkedHashMap<String, RetrievalHit> representative = new LinkedHashMap<>();
        Map<String, Double> scoreByKey = new LinkedHashMap<>();
        Map<String, Set<String>> sourcesByKey = new LinkedHashMap<>();
        for (List<RetrievalHit> group : orderedGroups) {
            // #7：源内先按 mergeKey 去重，保留最高分那条（如向量多变体命中同一 chunk），
            // 再按分降序定名次——每个源对同一 doc 只贡献一次最佳 rank，避免 RRF 重复加权。
            LinkedHashMap<String, RetrievalHit> bestPerKey = new LinkedHashMap<>();
            for (RetrievalHit rh : group) {
                bestPerKey.merge(rh.mergeKey(), rh, (a, b) -> a.score() >= b.score() ? a : b);
            }
            List<RetrievalHit> ranked = new ArrayList<>(bestPerKey.values());
            ranked.sort(Comparator.comparingDouble(RetrievalHit::score).reversed());
            int rank = 0;
            for (RetrievalHit rh : ranked) {
                rank++;
                String key = rh.mergeKey();
                scoreByKey.merge(key, 1.0 / (k + rank), Double::sum);
                sourcesByKey.computeIfAbsent(key, x -> new LinkedHashSet<>()).add(rh.source());
                representative.putIfAbsent(key, rh);
            }
        }
        // visibility 贯穿：同一 mergeKey 跨源/跨分区聚合时，只有全部贡献源都判 shared 才算 public，
        // 任一源判 tenant 即 tenant（AND，fail-safe 偏向不把租户内容误标为共享，且顺序无关、可测）。
        Map<String, Boolean> sharedByKey = new LinkedHashMap<>();
        for (List<RetrievalHit> group : orderedGroups) {
            for (RetrievalHit rh : group) {
                sharedByKey.merge(rh.mergeKey(), rh.shared(), (a, b) -> a && b);
            }
        }
        List<Hit> out = new ArrayList<>(representative.size());
        for (Map.Entry<String, RetrievalHit> e : representative.entrySet()) {
            String key = e.getKey();
            RetrievalHit r = e.getValue();
            Set<String> sources = sourcesByKey.get(key);
            String source = sources.size() > 1 ? "hybrid" : sources.iterator().next();
            out.add(new Hit(r.id(), scoreByKey.get(key), r.docId(), r.displayName(), r.category(), r.index(), r.text(),
                    source, sharedByKey.getOrDefault(key, r.shared())));
        }
        out.sort(Comparator.comparingDouble(HybridFusionService::scoreOrZero).reversed());
        return out;
    }

    private static Hit toHit(RetrievalHit r) {
        return new Hit(r.id(), r.score(), r.docId(), r.displayName(), r.category(), r.index(), r.text(), r.source(), r.shared());
    }

    /** 同源同 chunk：保留分更高者（保留其 source/visibility，如向量多变体命中仍为 vector）。 */
    private static Hit keepHigher(Hit a, Hit b) {
        return scoreOrZero(a) >= scoreOrZero(b) ? a : b;
    }

    /**
     * 跨源同 chunk：取 max 分、标 hybrid、保留先到者的 id 与非空字段（复刻原 mergeHits，existing=先到源）。
     * visibility 用 AND（both shared 才 public），fail-safe 偏向 tenant，且与 RRF 分支一致、顺序无关。
     */
    private static Hit mergeHits(Hit existing, Hit incoming) {
        return new Hit(
                existing.id(),
                Math.max(scoreOrZero(existing), scoreOrZero(incoming)),
                firstNonNull(existing.docId(), incoming.docId()),
                firstNonNull(existing.displayName(), incoming.displayName()),
                firstNonNull(existing.category(), incoming.category()),
                firstNonNull(existing.index(), incoming.index()),
                firstNonNull(existing.text(), incoming.text()),
                "hybrid",
                existing.shared() && incoming.shared());
    }

    private static double scoreOrZero(Hit hit) {
        return hit.score() == null ? 0.0 : hit.score();
    }

    private static String firstNonNull(String left, String right) {
        return left != null ? left : right;
    }
}
