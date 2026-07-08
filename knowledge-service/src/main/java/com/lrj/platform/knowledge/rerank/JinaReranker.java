package com.lrj.platform.knowledge.rerank;

import com.lrj.platform.knowledge.KnowledgeQueryService.Hit;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Jina 云重排器（移植单体 {@code JinaScoringModel} 用法）：一次把全部候选连同 query 交给 Jina reranker API
 * 批量打分，按分降序取前 topK。多语言召回强，代价是外部 API + Key（{@code JINA_API_KEY}）。
 */
public class JinaReranker implements Reranker {

    private final ScoringModel scoringModel;
    private final int multiplier;

    public JinaReranker(ScoringModel scoringModel, int multiplier) {
        this.scoringModel = scoringModel;
        this.multiplier = Math.max(1, multiplier);
    }

    @Override
    public int retrieveMultiplier() {
        return multiplier;
    }

    @Override
    public List<Hit> rerank(String query, List<Hit> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        List<TextSegment> segments = candidates.stream()
                .map(h -> TextSegment.from(h.text() == null ? "" : h.text()))
                .toList();
        List<Double> scores;
        try {
            scores = scoringModel.scoreAll(segments, query).content();
        } catch (RuntimeException e) {
            // 打分服务不可用：退回初始顺序取前 topK（不因外部 rerank 故障丢结果）
            return candidates.size() <= topK ? candidates : List.copyOf(candidates.subList(0, topK));
        }
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            double s = i < scores.size() && scores.get(i) != null ? scores.get(i) : 0.0;
            scored.add(new Scored(candidates.get(i), s));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .map(Scored::hit)
                .toList();
    }

    private record Scored(Hit hit, double score) {
    }
}
