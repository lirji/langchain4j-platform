package com.lrj.platform.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.eval.EvalCase;
import com.lrj.platform.protocol.eval.EvalCaseResult;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class EvalRunner {

    private final RestTemplate restTemplate;
    private final EvalProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public EvalRunner(RestTemplate restTemplate, EvalProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public EvalCaseResult execute(String targetBaseUrl, EvalCase evalCase) {
        Instant startedAt = Instant.now();
        if (evalCase.id() == null || evalCase.id().isBlank()) {
            return failure("unknown", 0, "id is required", null, startedAt);
        }
        if (evalCase.endpoint() == null || evalCase.endpoint().isBlank()) {
            return failure(evalCase.id(), 0, "endpoint is required", null, startedAt);
        }

        try {
            URI uri = resolveUri(targetBaseUrl, evalCase.endpoint());
            HttpMethod method = resolveMethod(evalCase);
            ResponseEntity<String> response = restTemplate.exchange(
                    uri,
                    method,
                    new HttpEntity<>(bodyFor(method, evalCase), headers()),
                    String.class);
            return evaluate(evalCase, response.getStatusCode().value(), response.getBody(), startedAt);
        } catch (IllegalArgumentException ex) {
            return failure(evalCase.id(), 0, ex.getMessage(), null, startedAt);
        } catch (HttpStatusCodeException ex) {
            String body = ex.getResponseBodyAsString();
            return failure(evalCase.id(), ex.getStatusCode().value(), statusError(ex.getStatusCode().value(), body), body, startedAt);
        } catch (RestClientException ex) {
            return failure(evalCase.id(), 0, ex.getMessage(), null, startedAt);
        }
    }

    private EvalCaseResult evaluate(EvalCase evalCase, int status, String body, Instant startedAt) {
        String responseBody = body == null ? "" : body;
        if (status < 200 || status >= 300) {
            return failure(evalCase.id(), status, statusError(status, responseBody), responseBody, startedAt);
        }
        String expected = evalCase.expectedContains();
        if (expected != null && !expected.isBlank() && !responseBody.contains(expected)) {
            return failure(evalCase.id(), status, "response did not contain expected text", responseBody, startedAt);
        }
        if (!evalCase.expectedJsonPaths().isEmpty()) {
            EvalCaseResult jsonPathResult = evaluateJsonPaths(evalCase, status, responseBody, startedAt);
            if (jsonPathResult != null) {
                return jsonPathResult;
            }
        }
        String oracle = evalCase.oracleContains();
        if (oracle != null && !oracle.isBlank() && !responseBody.contains(oracle)) {
            return oracleFailure(evalCase.id(), status, oracle, responseBody, startedAt);
        }
        return new EvalCaseResult(evalCase.id(), true, status, null, snippet(responseBody), elapsedMs(startedAt));
    }

    private EvalCaseResult evaluateJsonPaths(EvalCase evalCase, int status, String responseBody, Instant startedAt) {
        Object document;
        try {
            document = mapper.readValue(responseBody, Object.class);
        } catch (Exception ex) {
            return failure(evalCase.id(), status, "response was not valid JSON", responseBody, startedAt);
        }
        for (Map.Entry<String, Object> assertion : evalCase.expectedJsonPaths().entrySet()) {
            PathResult actual = readPath(document, assertion.getKey());
            if (!actual.found() || !Objects.equals(normalize(assertion.getValue()), normalize(actual.value()))) {
                return failure(evalCase.id(), status, "json path assertion failed: " + assertion.getKey(), responseBody, startedAt);
            }
        }
        return null;
    }

    private URI resolveUri(String targetBaseUrl, String endpoint) {
        if (targetBaseUrl == null || targetBaseUrl.isBlank()) {
            throw new IllegalArgumentException("targetBaseUrl is required");
        }
        String base = targetBaseUrl.endsWith("/") ? targetBaseUrl.substring(0, targetBaseUrl.length() - 1) : targetBaseUrl;
        String path = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return UriComponentsBuilder.fromHttpUrl(base + path).build(true).toUri();
    }

    private HttpMethod resolveMethod(EvalCase evalCase) {
        String method = evalCase.method();
        if (method == null || method.isBlank()) {
            return evalCase.body().isEmpty() ? HttpMethod.GET : HttpMethod.POST;
        }
        try {
            return HttpMethod.valueOf(method.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unsupported method: " + method);
        }
    }

    private Object bodyFor(HttpMethod method, EvalCase evalCase) {
        if (List.of(HttpMethod.GET, HttpMethod.DELETE, HttpMethod.HEAD, HttpMethod.OPTIONS).contains(method)) {
            return null;
        }
        return evalCase.body();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.ALL));
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            headers.set(properties.getApiKeyHeader(), properties.getApiKey());
        }
        return headers;
    }

    private EvalCaseResult failure(String id, int status, String error, String body, Instant startedAt) {
        return new EvalCaseResult(id, false, status, error, snippet(body), elapsedMs(startedAt));
    }

    private EvalCaseResult oracleFailure(String id, int status, String oracle, String body, Instant startedAt) {
        return new EvalCaseResult(
                id,
                false,
                status,
                "response did not match monolith oracle",
                snippet(body),
                elapsedMs(startedAt),
                false,
                oracle);
    }

    private String statusError(int status, String body) {
        if (body == null || body.isBlank()) {
            return "target returned HTTP " + status;
        }
        return "target returned HTTP " + status;
    }

    private String snippet(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        int limit = Math.max(32, properties.getResponseSnippetLimit());
        return body.length() <= limit ? body : body.substring(0, limit);
    }

    private long elapsedMs(Instant startedAt) {
        return Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis());
    }

    @SuppressWarnings("unchecked")
    private static PathResult readPath(Object document, String path) {
        if (path == null || path.isBlank() || !path.startsWith("$")) {
            return new PathResult(false, null);
        }
        Object current = document;
        int index = 1;
        while (index < path.length()) {
            char marker = path.charAt(index);
            if (marker == '.') {
                int next = nextTokenEnd(path, index + 1);
                if (next == index + 1 || !(current instanceof Map<?, ?> map)) {
                    return new PathResult(false, null);
                }
                current = map.get(path.substring(index + 1, next));
                if (current == null) {
                    return new PathResult(false, null);
                }
                index = next;
            } else if (marker == '[') {
                int close = path.indexOf(']', index);
                if (close < 0 || !(current instanceof List<?> list)) {
                    return new PathResult(false, null);
                }
                int itemIndex;
                try {
                    itemIndex = Integer.parseInt(path.substring(index + 1, close));
                } catch (NumberFormatException ex) {
                    return new PathResult(false, null);
                }
                if (itemIndex < 0 || itemIndex >= list.size()) {
                    return new PathResult(false, null);
                }
                current = list.get(itemIndex);
                index = close + 1;
            } else {
                return new PathResult(false, null);
            }
        }
        return new PathResult(true, current);
    }

    private static int nextTokenEnd(String path, int start) {
        int dot = path.indexOf('.', start);
        int bracket = path.indexOf('[', start);
        if (dot < 0) {
            return bracket < 0 ? path.length() : bracket;
        }
        if (bracket < 0) {
            return dot;
        }
        return Math.min(dot, bracket);
    }

    private static Object normalize(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value;
    }

    private record PathResult(boolean found, Object value) {
    }
}
