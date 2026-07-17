package com.lrj.platform.protocol.eval;

import java.util.List;

/**
 * 触发一次评测运行的请求（{@code POST /eval/**}）：指定被测服务的 {@code targetBaseUrl}
 * 与一组评测用例 {@link EvalCase}。
 */
public record EvalRunRequest(String targetBaseUrl,
                             List<EvalCase> cases) {

    public EvalRunRequest {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
