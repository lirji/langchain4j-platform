package com.lrj.platform.protocol.analytics;

import java.util.List;
import java.util.Map;

/**
 * NL2SQL 响应契约，字段对齐 analytics-service 的 {@code NlToSqlService.Result}。
 * 后续服务间调用优先依赖该 protocol DTO，避免直接引用对方 controller 内部模型。
 *
 * <p>{@code sql}/{@code rows} 取本轮最后一次成功执行；模型若拒答（没查库）则 {@code sql} 为 null、{@code rows} 为空。
 * {@code sql} 一并回传是刻意的：可审计、前端可"查看/复跑"、一眼区分是生成错还是解读错。
 */
public record AnalyticsSqlReply(String question, String sql, int rowCount,
                                List<Map<String, Object>> rows, String answer, boolean guardBlocked) {

    public AnalyticsSqlReply {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
