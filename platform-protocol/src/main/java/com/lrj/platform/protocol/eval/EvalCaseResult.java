package com.lrj.platform.protocol.eval;

/**
 * 单条评测用例的执行结果：是否通过、HTTP 状态、错误信息、响应片段、耗时，及 oracle 断言是否命中
 * ({@code oracleMatched}) 与其期望值。见 {@link EvalCase}、{@link EvalRunReply}。
 */
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
