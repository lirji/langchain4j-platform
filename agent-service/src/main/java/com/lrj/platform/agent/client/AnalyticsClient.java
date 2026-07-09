package com.lrj.platform.agent.client;

import java.util.List;
import java.util.Map;

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
