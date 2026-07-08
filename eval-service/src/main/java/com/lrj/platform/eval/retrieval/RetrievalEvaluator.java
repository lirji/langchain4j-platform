package com.lrj.platform.eval.retrieval;

import com.lrj.platform.protocol.eval.RetrievalCase;
import com.lrj.platform.protocol.eval.RetrievalCaseResult;
import com.lrj.platform.protocol.eval.RetrievalSummary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索质量评测编排：逐 case 调 {@link RetrievalClient} 取有序检索 id → {@link RetrievalMetrics} 算指标 →
 * 宏平均汇总 {@link RetrievalSummary}。不经 LLM，纯 IR 指标。
 */
@Component
public class RetrievalEvaluator {

    private final RetrievalClient client;

    public RetrievalEvaluator(RetrievalClient client) {
        this.client = client;
    }

    public RetrievalSummary evaluate(List<RetrievalCase> cases, Integer topK, String category, String targetBaseUrl) {
        List<RetrievalCaseResult> results = new ArrayList<>();
        double sumRecall = 0;
        double sumPrecision = 0;
        double sumMrr = 0;
        int hits = 0;
        long totalMs = 0;

        for (RetrievalCase c : cases) {
            long t0 = System.nanoTime();
            List<String> retrieved = client.retrieve(targetBaseUrl, c.question(), topK, category);
            long durationMs = (System.nanoTime() - t0) / 1_000_000L;

            RetrievalMetrics.CaseMetrics m = RetrievalMetrics.compute(retrieved, c.relevantDocIds());
            results.add(new RetrievalCaseResult(c.id(), c.question(), retrieved, c.relevantDocIds(),
                    m.recall(), m.precision(), m.mrr(), m.hit(), durationMs));
            sumRecall += m.recall();
            sumPrecision += m.precision();
            sumMrr += m.mrr();
            if (m.hit()) {
                hits++;
            }
            totalMs += durationMs;
        }

        int n = cases.size();
        return new RetrievalSummary(
                n,
                n == 0 ? 0.0 : sumRecall / n,
                n == 0 ? 0.0 : sumPrecision / n,
                n == 0 ? 0.0 : sumMrr / n,
                n == 0 ? 0.0 : (double) hits / n,
                totalMs,
                results);
    }
}
