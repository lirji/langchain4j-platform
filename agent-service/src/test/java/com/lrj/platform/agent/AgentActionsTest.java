package com.lrj.platform.agent;

import com.lrj.platform.agent.actions.AnalyticsSqlAction;
import com.lrj.platform.agent.actions.RagSearchAction;
import com.lrj.platform.agent.actions.SchemaExploreAction;
import com.lrj.platform.agent.client.AnalyticsClient;
import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentActionsTest {

    @Test
    void ragSearchRendersKnowledgeHitsWithSourceIds() {
        RagSearchAction action = new RagSearchAction(
                request -> new KnowledgeQueryReply(
                        request.query(),
                        "acme",
                        List.of(new KnowledgeHit("h1", 0.9, "d1", "guide.md", "manual", "3",
                                "退款审批需要主管确认。", "hybrid"))),
                5,
                0.0,
                "manual");

        String output = action.run("退款");

        assertThat(output).contains("[doc=guide.md#3]");
        assertThat(output).contains("(hybrid)");
        assertThat(output).contains("退款审批需要主管确认。");
    }

    @Test
    void analyticsSqlRendersRowsAndAnswer() {
        AnalyticsSqlAction action = new AnalyticsSqlAction(new StubAnalyticsClient() {
            @Override
            public Result ask(String question) {
                return new Result(question, "select count(*) from orders", 1,
                        List.of(Map.of("count", 7)), "一共 7 单。", false, null);
            }
        });

        String output = action.run("订单数");

        assertThat(output).contains("SQL: select count(*) from orders");
        assertThat(output).contains("行数: 1");
        assertThat(output).contains("{count=7}");
        assertThat(output).contains("解读: 一共 7 单。");
    }

    @Test
    void schemaExploreListsTablesWhenInputBlank() {
        SchemaExploreAction action = new SchemaExploreAction(new StubAnalyticsClient() {
            @Override
            public TablesResult listTables() {
                return new TablesResult(List.of("orders", "customers", "refunds"), null);
            }
        });

        String output = action.run("");

        assertThat(output).contains("可查询的表：");
        assertThat(output).contains("orders", "customers", "refunds");
    }

    @Test
    void schemaExploreDescribesTableWhenInputGiven() {
        SchemaExploreAction action = new SchemaExploreAction(new StubAnalyticsClient() {
            @Override
            public TableSchemaResult describeTable(String table) {
                return new TableSchemaResult(table, "Table orders\n  - id BIGINT\n  - status VARCHAR", null);
            }
        });

        String output = action.run("orders");

        assertThat(output).contains("Table orders");
        assertThat(output).contains("status VARCHAR");
    }

    @Test
    void schemaExploreReturnsErrorText() {
        SchemaExploreAction action = new SchemaExploreAction(new StubAnalyticsClient() {
            @Override
            public TableSchemaResult describeTable(String table) {
                return new TableSchemaResult(table, null, "table not found or not allowed: secret");
            }
        });

        String output = action.run("secret");

        assertThat(output).contains("查看表结构失败：");
        assertThat(output).contains("secret");
    }

    /** 默认对三方法抛异常的 {@link AnalyticsClient} 桩，各测试按需只覆盖用到的方法。 */
    private abstract static class StubAnalyticsClient implements AnalyticsClient {
        @Override
        public Result ask(String question) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TablesResult listTables() {
            throw new UnsupportedOperationException();
        }

        @Override
        public TableSchemaResult describeTable(String table) {
            throw new UnsupportedOperationException();
        }
    }
}
