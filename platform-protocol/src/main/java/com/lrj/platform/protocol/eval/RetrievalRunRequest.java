package com.lrj.platform.protocol.eval;

import java.util.List;

/**
 * 检索评测请求：一组 case + 检索参数。{@code targetBaseUrl} 留空用服务端默认（edge-gateway），
 * 检索经其 {@code /rag/query} 打到 knowledge-service。
 */
public record RetrievalRunRequest(
        List<RetrievalCase> cases,
        Integer topK,
        String category,
        String targetBaseUrl) {
}
