package com.lrj.platform.knowledge.rerank;

import com.lrj.platform.knowledge.KnowledgeQueryService.Hit;

import java.util.Comparator;
import java.util.List;

/**
 * LLM-as-judge 重排器（移植单体 {@code OllamaLlmScoringModel} 思路）：对每个候选用 temp=0 的判官模型
 * 打相关性分（0..1），按分降序取前 topK。零额外依赖（复用网关 ChatModel），代价是 N 次 LLM 调用。
 *
 * <p>打分经 {@link RelevanceScorer} 抽象注入，排序逻辑可确定性单测。
 */
public class LlmReranker implements Reranker {

    private final RelevanceScorer scorer;
    private final int multiplier;
    private final double minScore;

    public LlmReranker(RelevanceScorer scorer, int multiplier) {
        this(scorer, multiplier, 0.0);
    }

    /**
     * @param minScore 相关性阈值（0..1）：rerank 打分严格低于此值的候选在截断到 topK <b>前</b>被丢弃，
     *                 从而"要 N 个也只出相关的"。{@code 0} = 不设阈值（保留原行为）。
     *                 注意：单个候选打分失败会退回其融合分（很低），故设了阈值时打分失败的候选大概率被滤除
     *                 （偏精确度的 fail-closed）。阈值过高可能返回空——这是"无够相关文档"的预期。
     */
    public LlmReranker(RelevanceScorer scorer, int multiplier, double minScore) {
        this.scorer = scorer;
        this.multiplier = Math.max(1, multiplier);
        this.minScore = Math.max(0.0, minScore);
    }

    @Override
    public int retrieveMultiplier() {
        return multiplier;
    }

    @Override
    public List<Hit> rerank(String query, List<Hit> candidates, int topK) {
        return candidates.stream()
                .map(hit -> new Scored(hit, safeScore(query, hit)))
                .filter(s -> s.score() >= minScore)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .map(Scored::hit)
                .toList();
    }

    private double safeScore(String query, Hit hit) {
        String text = hit.text();
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        try {
            double s = scorer.score(query, text);
            return Double.isFinite(s) ? s : 0.0;
        } catch (RuntimeException e) {
            // 单个候选打分失败不拖垮整轮 rerank：退回初始分（保序）
            return hit.score() == null ? 0.0 : hit.score();
        }
    }

    private record Scored(Hit hit, double score) {
    }
}
