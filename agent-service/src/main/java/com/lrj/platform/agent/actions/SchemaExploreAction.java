package com.lrj.platform.agent.actions;

import com.lrj.platform.agent.AgentAction;
import com.lrj.platform.agent.client.AnalyticsClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 探查业务库结构的 agent 动作：留空=列出所有可查表；填表名=查看该表字段/类型/枚举取值。
 * 让数据分析智能体「先探后查」——先确认真实列名与中文枚举，再用 {@code analytics_sql} 取数，
 * 避免凭空猜列名。依赖 {@link AnalyticsClient}（Http/Noop 兜底），故只门控 {@code app.agent.enabled}。
 */
@Component
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class SchemaExploreAction implements AgentAction {

    private final AnalyticsClient analyticsClient;

    public SchemaExploreAction(AnalyticsClient analyticsClient) {
        this.analyticsClient = analyticsClient;
    }

    @Override
    public String name() {
        return "schema_explore";
    }

    @Override
    public String description() {
        return "探查业务库结构：actionInput 留空=列出所有可查的表；填表名=查看该表的字段、类型与枚举取值。先探后查，再用 analytics_sql 取数。";
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            AnalyticsClient.TablesResult result = analyticsClient.listTables();
            if (result.error() != null && !result.error().isBlank()) {
                return "列出表失败：" + result.error();
            }
            return result.tables().isEmpty()
                    ? "没有可查询的表。"
                    : "可查询的表：" + String.join(", ", result.tables());
        }
        AnalyticsClient.TableSchemaResult result = analyticsClient.describeTable(input.trim());
        if (result.error() != null && !result.error().isBlank()) {
            return "查看表结构失败：" + result.error();
        }
        return result.schema() == null || result.schema().isBlank()
                ? "（该表无结构信息）"
                : result.schema();
    }
}
