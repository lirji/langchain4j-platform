package com.lrj.platform.protocol.eval;

import java.util.List;

/**
 * 单个检索 case 的评测结果：检索回的有序 id + 标注 id + 四个 IR 指标（便于人肉看哪条召回差）。
 */
public record RetrievalCaseResult(
        String id,
        String question,
        List<String> retrievedIds,
        List<String> relevantIds,
        double recall,
        double precision,
        double mrr,
        boolean hit,
        long durationMs) {
}
