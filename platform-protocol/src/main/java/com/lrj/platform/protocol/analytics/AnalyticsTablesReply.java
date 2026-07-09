package com.lrj.platform.protocol.analytics;

import java.util.List;

/**
 * agent→analytics 探表：列出当前可查询（白名单）的表名。
 * 只含结构元数据（表名），不含租户业务行数据；租户身份随内部 JWT 传播，不进 body。
 */
public record AnalyticsTablesReply(List<String> tables) {

    public AnalyticsTablesReply {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }
}
