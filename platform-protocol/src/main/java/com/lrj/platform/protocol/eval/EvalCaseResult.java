package com.lrj.platform.protocol.eval;

public record EvalCaseResult(String id,
                             boolean passed,
                             int status,
                             String error,
                             String responseSnippet,
                             long durationMs,
                             boolean oracleMatched,
                             String oracleExpected) {

    public EvalCaseResult(String id,
                          boolean passed,
                          int status,
                          String error,
                          String responseSnippet,
                          long durationMs) {
        this(id, passed, status, error, responseSnippet, durationMs, true, null);
    }
}
