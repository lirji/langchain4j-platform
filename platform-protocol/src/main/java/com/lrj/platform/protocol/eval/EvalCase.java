package com.lrj.platform.protocol.eval;

import java.util.Map;

/**
 * 回归评测中的单条用例契约：定义待打的 {@code endpoint}/{@code method}/{@code body}，以及多档校验方式——
 * 子串包含 ({@code expectedContains}/{@code oracleContains})、JSON path 断言、语义/判官/嵌入相似度
 * 期望值与各自的最小分阈值。eval-service 据此逐条执行并判定通过。
 */
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
