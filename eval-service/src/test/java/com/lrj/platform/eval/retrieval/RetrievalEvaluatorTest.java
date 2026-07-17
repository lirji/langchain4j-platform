package com.lrj.platform.eval.retrieval;

import com.lrj.platform.protocol.eval.RetrievalCase;
import com.lrj.platform.protocol.eval.RetrievalSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * RetrievalEvaluatorTest：验证 {@link RetrievalEvaluator} 用桩 {@link RetrievalClient} 逐 case 检索后，
 * 对 recall/hitRate 等指标做跨用例宏平均聚合，并覆盖空用例集返回全零摘要的场景。
 */
class RetrievalEvaluatorTest {

    @Test
    void aggregatesMacroAverageAcrossCases() {
        // stub 检索：case-1 完美召回、case-2 完全没召回
        Map<String, List<String>> canned = Map.of(
                "q1", List.of("a.md#0"),
                "q2", List.of("noise.md#0"));
        RetrievalClient client = (base, question, topK, category) -> canned.getOrDefault(question, List.of());
        RetrievalEvaluator evaluator = new RetrievalEvaluator(client);

        List<RetrievalCase> cases = List.of(
                new RetrievalCase("c1", "q1", List.of("a.md")),
                new RetrievalCase("c2", "q2", List.of("b.md")));

        RetrievalSummary summary = evaluator.evaluate(cases, 5, null, null);

        assertThat(summary.cases()).isEqualTo(2);
        // recall: (1.0 + 0.0)/2 = 0.5
        assertThat(summary.avgRecall()).isCloseTo(0.5, within(1e-9));
        // hitRate: 1/2
        assertThat(summary.hitRate()).isCloseTo(0.5, within(1e-9));
        assertThat(summary.results()).hasSize(2);
        assertThat(summary.results().get(0).hit()).isTrue();
        assertThat(summary.results().get(1).hit()).isFalse();
    }

    @Test
    void emptyCases_zeroSummary() {
        RetrievalClient client = (base, q, k, c) -> List.of();
        RetrievalSummary summary = new RetrievalEvaluator(client).evaluate(List.of(), 5, null, null);
        assertThat(summary.cases()).isZero();
        assertThat(summary.avgRecall()).isZero();
        assertThat(summary.hitRate()).isZero();
    }
}
