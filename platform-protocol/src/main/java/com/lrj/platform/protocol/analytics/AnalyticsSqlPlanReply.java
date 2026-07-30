package com.lrj.platform.protocol.analytics;

import java.util.List;
import java.util.Map;

/** Java guard/executor 对候选 SQL 的确定性结果；不包含模型生成的自然语言答案。 */
public record AnalyticsSqlPlanReply(
        String question,
        String sql,
        int rowCount,
        List<Map<String, Object>> rows,
        boolean executed,
        String rejectionReason
) {
    public AnalyticsSqlPlanReply {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
