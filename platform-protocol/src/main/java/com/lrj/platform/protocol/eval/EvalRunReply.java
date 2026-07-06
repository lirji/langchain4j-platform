package com.lrj.platform.protocol.eval;

import java.time.Instant;
import java.util.List;

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
