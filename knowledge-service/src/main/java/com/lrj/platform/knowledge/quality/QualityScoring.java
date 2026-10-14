package com.lrj.platform.knowledge.quality;

import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import java.util.*;
import static com.lrj.platform.protocol.knowledge.RetrievalQuality.*;

/** 文档级精确标注评分；不从chunk标识猜测文档ID，重复chunk不增加命中数。 */
public final class QualityScoring {
    private QualityScoring() {}

    /** 对服务返回的topK个chunk按文档去重；Precision分母为实际返回的不同文档数。 */
    public static CaseResult score(Case sample, List<KnowledgeHit> hits, int topK, long latencyMs) {
        var documents = new LinkedHashMap<Target, Observed>();
        hits.stream().limit(topK).filter(hit -> hit.docId() != null && !hit.docId().isBlank()
                && ("tenant".equals(hit.visibility()) || "public".equals(hit.visibility())))
                .forEach(hit -> {
                    var target = new Target(hit.visibility(), hit.docId());
                    documents.putIfAbsent(target, new Observed(target, hit.version()));
                });
        var ranked = new ArrayList<>(documents.keySet());
        long matched = ranked.stream().filter(sample.relevant()::contains).count();
        double mrr = 0;
        for (int i = 0; i < ranked.size(); i++) if (sample.relevant().contains(ranked.get(i))) { mrr = 1.0 / (i + 1); break; }
        return new CaseResult(sample.id(), List.copyOf(documents.values()), (double) matched / sample.relevant().size(),
                ranked.isEmpty() ? 0 : (double) matched / ranked.size(), mrr, matched > 0, latencyMs);
    }

    /** 使用宏平均和最近秩百分位；少量样本的P95只是本次观测，不代表生产SLO。 */
    public static Metrics aggregate(List<CaseResult> results) {
        if (results.isEmpty()) throw new IllegalArgumentException("empty evaluation cannot pass");
        long[] latency = results.stream().mapToLong(CaseResult::latencyMs).sorted().toArray();
        return new Metrics(results.stream().mapToDouble(CaseResult::recall).average().orElseThrow(),
                results.stream().mapToDouble(CaseResult::precision).average().orElseThrow(),
                results.stream().mapToDouble(CaseResult::mrr).average().orElseThrow(),
                (double) results.stream().filter(CaseResult::hit).count() / results.size(),
                latency[(int) Math.ceil(latency.length * .5) - 1], latency[(int) Math.ceil(latency.length * .95) - 1]);
    }

    /** 总体与逐题召回同时设门禁，防止新增命中掩盖原有关键题退化。 */
    public static Gate compare(Metrics now, List<CaseResult> results, Run baseline, Settings settings) {
        if (baseline == null) return new Gate(Verdict.BASELINE_REQUIRED, List.of("baseline_required"), null, null, null, null, null);
        var old = baseline.metrics(); var reasons = new ArrayList<String>();
        double recall = now.recall() - old.recall(), precision = now.precision() - old.precision();
        double mrr = now.mrr() - old.mrr(), hit = now.hitRate() - old.hitRate();
        long latency = now.p95Ms() - old.p95Ms();
        double floor = -settings.allowedDrop() - 1e-9;
        if (recall < floor) reasons.add("recall_regression");
        if (precision < floor) reasons.add("precision_regression");
        if (mrr < floor) reasons.add("mrr_regression");
        if (hit < floor) reasons.add("hit_rate_regression");
        if (latency > settings.allowedP95IncreaseMs()) reasons.add("p95_regression");
        Map<String, CaseResult> previous = new HashMap<>(); baseline.results().forEach(row -> previous.put(row.caseId(), row));
        for (CaseResult row : results) {
            CaseResult before = previous.get(row.caseId());
            if (before == null) throw new IllegalArgumentException("baseline cases do not match");
            if (row.recall() - before.recall() < floor) reasons.add("case_recall:" + row.caseId());
        }
        return new Gate(reasons.isEmpty() ? Verdict.PASS : Verdict.REGRESSION, reasons, recall, precision, mrr, hit, latency);
    }
}
