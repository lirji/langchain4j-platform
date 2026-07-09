package com.lrj.platform.agent.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

// 与 HttpAnalyticsClient 互补：Http 在 agent.enabled && analytics.enabled 时注册，
// 本兜底覆盖 agent.enabled && !analytics.enabled 的缺口（agent.enabled=false 时两者都不注册，
// 此时唯一消费者 AnalyticsSqlAction 也不注册，无人需要）。
// 不用 @ConditionalOnMissingBean：其在组件扫描下对 @Component 注册顺序敏感、不可靠，
// analytics 关闭时会随机导致 AnalyticsClient bean 缺失、agent-service 启动失败。
@Component
@ConditionalOnExpression("${app.agent.enabled:true} and !${app.agent.analytics.enabled:true}")
public class NoopAnalyticsClient implements AnalyticsClient {

    private static final String DISABLED = "analytics action disabled";

    @Override
    public Result ask(String question) {
        return new Result(question, null, 0, List.of(), null, false, DISABLED);
    }

    @Override
    public TablesResult listTables() {
        return new TablesResult(List.of(), DISABLED);
    }

    @Override
    public TableSchemaResult describeTable(String table) {
        return new TableSchemaResult(table, null, DISABLED);
    }
}
