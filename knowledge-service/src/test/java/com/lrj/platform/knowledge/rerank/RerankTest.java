package com.lrj.platform.knowledge.rerank;

import com.lrj.platform.knowledge.KnowledgeQueryService.Hit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重排器纯逻辑测试（不连模型：打分经 {@link RelevanceScorer} 桩注入）。
 */
class RerankTest {

    private static Hit hit(String id, double score, String text) {
        return new Hit(id, score, "doc", "doc.md", null, "0", text, "vector", false);
    }

    @Test
    void noop_keepsOrderAndTruncatesToTopK() {
        List<Hit> candidates = List.of(hit("a", 0.9, "x"), hit("b", 0.5, "y"), hit("c", 0.1, "z"));
        List<Hit> out = new NoopReranker().rerank("q", candidates, 2);
        assertThat(out).extracting(Hit::id).containsExactly("a", "b");
    }

    @Test
    void llm_reordersByScorerRelevance() {
        // 初始分把无关的 alpha 排前面；scorer 认为含 phoenix 的更相关 → rerank 后 phoenix 居首
        List<Hit> candidates = List.of(
                hit("alpha", 0.9, "unrelated alpha text"),
                hit("phoenix", 0.2, "the phoenix marker doc"));
        RelevanceScorer scorer = (q, t) -> t.contains("phoenix") ? 0.95 : 0.05;
        List<Hit> out = new LlmReranker(scorer, 3).rerank("phoenix", candidates, 2);
        assertThat(out).extracting(Hit::id).containsExactly("phoenix", "alpha");
    }

    @Test
    void llm_scorerThrows_fallsBackToInitialScore() {
        List<Hit> candidates = List.of(hit("a", 0.9, "x"), hit("b", 0.3, "y"));
        RelevanceScorer boom = (q, t) -> {
            throw new RuntimeException("scorer down");
        };
        // 打分全失败 → 退回各自初始分排序，不抛异常
        List<Hit> out = new LlmReranker(boom, 3).rerank("q", candidates, 2);
        assertThat(out).extracting(Hit::id).containsExactly("a", "b");
    }

    @Test
    void multiplier_isClampedToAtLeastOne() {
        assertThat(new LlmReranker((q, t) -> 0.5, 0).retrieveMultiplier()).isEqualTo(1);
        assertThat(new LlmReranker((q, t) -> 0.5, 4).retrieveMultiplier()).isEqualTo(4);
        assertThat(new NoopReranker().retrieveMultiplier()).isEqualTo(1);
    }

    @Test
    void parseScore_extractsUnitInterval() {
        assertThat(RerankConfig.parseScore("0.8")).isEqualTo(0.8);
        assertThat(RerankConfig.parseScore("相关性 0.73 分")).isEqualTo(0.73);
        assertThat(RerankConfig.parseScore("1")).isEqualTo(1.0);
        assertThat(RerankConfig.parseScore("没有数字")).isEqualTo(0.0);
        assertThat(RerankConfig.parseScore(null)).isEqualTo(0.0);
    }
}
