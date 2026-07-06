package com.lrj.platform.protocol.eval;

import java.util.List;

public record EvalSuiteDefinition(String name,
                                  List<EvalCase> cases) {

    public EvalSuiteDefinition {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
