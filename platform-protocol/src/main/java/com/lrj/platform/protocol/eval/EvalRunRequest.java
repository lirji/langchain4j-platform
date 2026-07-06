package com.lrj.platform.protocol.eval;

import java.util.List;

public record EvalRunRequest(String targetBaseUrl,
                             List<EvalCase> cases) {

    public EvalRunRequest {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
