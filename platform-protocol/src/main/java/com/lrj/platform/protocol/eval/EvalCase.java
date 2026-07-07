package com.lrj.platform.protocol.eval;

import java.util.Map;

public record EvalCase(String id,
                       String endpoint,
                       String method,
                       Map<String, Object> body,
                       String expectedContains,
                       String oracleContains,
                       Map<String, Object> expectedJsonPaths,
                       String semanticExpected,
                       Double semanticMinScore,
                       String judgeExpected,
                       Double judgeMinScore,
                       String embeddingExpected,
                       Double embeddingMinScore) {

    public EvalCase(String id,
                    String endpoint,
                    String method,
                    Map<String, Object> body,
                    String expectedContains,
                    String oracleContains) {
        this(id, endpoint, method, body, expectedContains, oracleContains, null, null, null);
    }

    public EvalCase(String id,
                    String endpoint,
                    String method,
                    Map<String, Object> body,
                    String expectedContains,
                    String oracleContains,
                    Map<String, Object> expectedJsonPaths) {
        this(id, endpoint, method, body, expectedContains, oracleContains, expectedJsonPaths, null, null);
    }

    public EvalCase(String id,
                    String endpoint,
                    String method,
                    Map<String, Object> body,
                    String expectedContains,
                    String oracleContains,
                    Map<String, Object> expectedJsonPaths,
                    String semanticExpected,
                    Double semanticMinScore) {
        this(id, endpoint, method, body, expectedContains, oracleContains, expectedJsonPaths,
                semanticExpected, semanticMinScore, null, null, null, null);
    }

    public EvalCase(String id,
                    String endpoint,
                    String method,
                    Map<String, Object> body,
                    String expectedContains) {
        this(id, endpoint, method, body, expectedContains, null);
    }

    public EvalCase {
        body = body == null ? Map.of() : Map.copyOf(body);
        expectedJsonPaths = expectedJsonPaths == null ? Map.of() : Map.copyOf(expectedJsonPaths);
    }
}
