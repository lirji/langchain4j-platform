package com.lrj.platform.agent.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

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
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    "/analytics/sql",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("question", question)),
                    new ParameterizedTypeReference<>() {});
            Map<String, Object> body = response.getBody();
            if (body == null) {
                return new Result(question, null, 0, List.of(), null, false, "empty analytics response");
            }
            return toResult(question, body);
        } catch (RestClientException ex) {
            log.warn("agent analytics query failed: {}", ex.toString());
            return new Result(question, null, 0, List.of(), null, false, ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Result toResult(String question, Map<String, Object> body) {
        List<Map<String, Object>> rows = body.get("rows") instanceof List<?> raw
                ? raw.stream()
                    .filter(Map.class::isInstance)
                    .map(row -> (Map<String, Object>) row)
                    .toList()
                : List.of();
        int rowCount = body.get("rowCount") instanceof Number n ? n.intValue() : rows.size();
        return new Result(
                string(body.getOrDefault("question", question)),
                string(body.get("sql")),
                rowCount,
                rows,
                string(body.get("answer")),
                Boolean.TRUE.equals(body.get("guardBlocked")),
                null);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
