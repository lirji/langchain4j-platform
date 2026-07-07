package com.lrj.platform.agent.client;

import com.lrj.platform.protocol.analytics.AnalyticsSqlReply;
import com.lrj.platform.protocol.analytics.AnalyticsSqlRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

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
}
