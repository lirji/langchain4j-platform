package com.lrj.platform.protocol.eval;

import java.util.List;

/**
 * 评测套件定义：一个命名 {@code name} 下聚合的一组评测用例 {@link EvalCase}，供 eval-service 加载执行。
 */
public record EvalSuiteDefinition(String name,
                                  List<EvalCase> cases) {

    public EvalSuiteDefinition {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
