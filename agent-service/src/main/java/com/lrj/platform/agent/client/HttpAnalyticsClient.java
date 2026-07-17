package com.lrj.platform.agent.client;

import com.lrj.platform.protocol.analytics.AnalyticsSqlReply;
import com.lrj.platform.protocol.analytics.AnalyticsSqlRequest;
import com.lrj.platform.protocol.analytics.AnalyticsTableSchemaReply;
import com.lrj.platform.protocol.analytics.AnalyticsTablesReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * {@link AnalyticsClient} 的 HTTP 实现，经 {@code analyticsRestTemplate}（透传租户与 traceId）
 * 调用 analytics-service 的 {@code /analytics/sql} 与 {@code /analytics/schema/**}。RestClient 异常被
 * 捕获并降级为带 error 的结果对象，不向调用方抛出。默认装配（{@code matchIfMissing=true}）。
 */
@Component
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.analytics.enabled"}, havingValue = "true", matchIfMissing = true)
public class HttpAnalyticsClient implements AnalyticsClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAnalyticsClient.class);

    private final RestTemplate restTemplate;

    public HttpAnalyticsClient(@Qualifier("analyticsRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public Result ask(String question) {
        try {
            AnalyticsSqlReply reply = restTemplate.postForObject(
                    "/analytics/sql", new AnalyticsSqlRequest(question), AnalyticsSqlReply.class);
            if (reply == null) {
                return new Result(question, null, 0, List.of(), null, false, "empty analytics response");
            }
            return new Result(
                    reply.question() == null ? question : reply.question(),
                    reply.sql(),
                    reply.rowCount(),
                    reply.rows(),
                    reply.answer(),
                    reply.guardBlocked(),
                    null);
        } catch (RestClientException ex) {
            log.warn("agent analytics query failed: {}", ex.toString());
            return new Result(question, null, 0, List.of(), null, false, ex.getMessage());
        }
    }

    @Override
    public TablesResult listTables() {
        try {
            AnalyticsTablesReply reply = restTemplate.getForObject(
                    "/analytics/schema/tables", AnalyticsTablesReply.class);
            return new TablesResult(reply == null ? List.of() : reply.tables(), null);
        } catch (RestClientException ex) {
            log.warn("agent list-tables failed: {}", ex.toString());
            return new TablesResult(List.of(), ex.getMessage());
        }
    }

    @Override
    public TableSchemaResult describeTable(String table) {
        try {
            AnalyticsTableSchemaReply reply = restTemplate.getForObject(
                    "/analytics/schema/tables/{table}", AnalyticsTableSchemaReply.class, table);
            if (reply == null) {
                return new TableSchemaResult(table, null, "empty schema response");
            }
            return new TableSchemaResult(reply.table(), reply.schema(), null);
        } catch (HttpClientErrorException.NotFound ex) {
            return new TableSchemaResult(table, null, "table not found or not allowed: " + table);
        } catch (RestClientException ex) {
            log.warn("agent describe-table failed: {}", ex.toString());
            return new TableSchemaResult(table, null, ex.getMessage());
        }
    }
}
