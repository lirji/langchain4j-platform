package com.lrj.platform.protocol.analytics;

/**
 * agent→analytics 探表：单张白名单表的结构文本（列名 / 类型 / 注释 / 枚举取值）。
 * {@code schema} 为一段紧凑可读文本，直接喂给数据分析智能体决定如何取数。
 */
public record AnalyticsTableSchemaReply(String table, String schema) {
}
