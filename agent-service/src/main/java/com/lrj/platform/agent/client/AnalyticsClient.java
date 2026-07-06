package com.lrj.platform.agent.client;

import java.util.List;
import java.util.Map;

public interface AnalyticsClient {

    Result ask(String question);

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
}
