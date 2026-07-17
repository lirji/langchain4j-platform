package com.lrj.platform.agent.actions;

import com.lrj.platform.agent.AgentAction;
import com.lrj.platform.agent.client.AnalyticsClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code analytics_sql} 动作：把自然语言业务统计问题委托给 analytics-service 的 NL2SQL（只读、带安全护栏），
 * 通过 {@link AnalyticsClient} 调用并把生成的 SQL、行数、前若干行数据与解读拼成观察文本返回给 ReAct 循环。
 * 是 {@link AgentAction} 的可插拔实现之一，由 {@code app.agent.enabled} 门控（默认开）。
 */
@Component
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsSqlAction implements AgentAction {

    private static final int MAX_ROWS_ECHOED = 10;

    private final AnalyticsClient analyticsClient;

    public AnalyticsSqlAction(AnalyticsClient analyticsClient) {
        this.analyticsClient = analyticsClient;
    }

    @Override
    public String name() {
        return "analytics_sql";
    }

    @Override
    public String description() {
        return "用自然语言查业务数据库（只读、带安全护栏）；actionInput 填业务统计问题。文档类问题用 rag_search。";
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "查询为空：actionInput 请填要查的业务问题。";
        }
        AnalyticsClient.Result result = analyticsClient.ask(input.trim());
        if (result.error() != null && !result.error().isBlank()) {
            return "查询失败：" + result.error();
        }
        if (result.guardBlocked()) {
            return "查询被安全护栏拦截，未执行。请换一个只读、限定本租户数据的问法。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("SQL: ").append(result.sql() == null ? "(未生成)" : result.sql()).append('\n');
        sb.append("行数: ").append(result.rowCount()).append('\n');
        List<Map<String, Object>> rows = result.rows();
        if (!rows.isEmpty()) {
            sb.append("数据: ");
            for (int i = 0; i < rows.size() && i < MAX_ROWS_ECHOED; i++) {
                sb.append(rows.get(i));
                if (i < rows.size() - 1 && i < MAX_ROWS_ECHOED - 1) {
                    sb.append("; ");
                }
            }
            if (rows.size() > MAX_ROWS_ECHOED) {
                sb.append(" ...(共 ").append(rows.size()).append(" 行)");
            }
            sb.append('\n');
        }
        if (result.answer() != null && !result.answer().isBlank()) {
            sb.append("解读: ").append(result.answer());
        }
        return sb.toString().stripTrailing();
    }
}
