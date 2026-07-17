package com.lrj.platform.protocol.eval;

import java.time.Instant;
import java.util.List;

/**
 * 一次评测套件运行的汇总响应（{@code POST /eval/**}）：总数/通过数/通过率、逐条结果
 * {@link EvalCaseResult} 列表，及运行元信息（{@code runId}、套件名、目标 base-url、起止时间、耗时、报告路径）。
 */
public record EvalRunReply(int total,
                           int passed,
                           double passRate,
                           List<EvalCaseResult> results,
                           String runId,
                           String suiteName,
                           String targetBaseUrl,
                           Instant startedAt,
                           long durationMs,
                           String reportPath,
                           Instant finishedAt) {

    public EvalRunReply(int total,
                        int passed,
                        double passRate,
                        List<EvalCaseResult> results,
                        Instant finishedAt) {
        this(total, passed, passRate, results, null, null, null, null, 0L, null, finishedAt);
    }

    public EvalRunReply {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
