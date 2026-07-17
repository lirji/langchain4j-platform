package com.lrj.platform.agent.client;

import java.util.List;
import java.util.Map;

/**
 * agent-service 访问 analytics-service（NL2SQL）的客户端抽象。供数据分析智能体「先探后查」：
 * {@link #listTables()} 列白名单表、{@link #describeTable(String)} 看表结构、{@link #ask(String)}
 * 提自然语言问题并拿回生成的 SQL/结果行/答案。默认实现 {@link HttpAnalyticsClient}，各返回值以内嵌
 * record（error 非空表示失败）承载，不抛异常上抛。
 */
public interface AnalyticsClient {

    Result ask(String question);

    /** 列出当前可查询（白名单）的表名，用于数据分析智能体「先探后查」。error 非空表示失败/已禁用。 */
    TablesResult listTables();

    /** 查看单张表的结构文本（列/类型/注释/枚举取值）。表不存在或非白名单时 error 非空、schema 为 null。 */
    TableSchemaResult describeTable(String table);

    record Result(String question,
                  String sql,
                  int rowCount,
                  List<Map<String, Object>> rows,
                  String answer,
                  boolean guardBlocked,
                  String error) {

        public Result {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    record TablesResult(List<String> tables, String error) {

        public TablesResult {
            tables = tables == null ? List.of() : List.copyOf(tables);
        }
    }

    record TableSchemaResult(String table, String schema, String error) {
    }
}
