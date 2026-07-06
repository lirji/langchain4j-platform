package com.lrj.platform.protocol.eval;

import java.util.Map;

public record EvalCase(String id,
                       String endpoint,
                       String method,
                       Map<String, Object> body,
                       String expectedContains,
                       String oracleContains) {

    public EvalCase(String id,
                    String endpoint,
                    String method,
                    Map<String, Object> body,
                    String expectedContains) {
        this(id, endpoint, method, body, expectedContains, null);
    }

    public EvalCase {
        body = body == null ? Map.of() : Map.copyOf(body);
    }
}
