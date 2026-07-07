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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class EvalRunner {

    private final RestTemplate restTemplate;
    private final EvalProperties properties;
    private final EvalJudge judge;
    private final EvalEmbeddingComparator embeddingComparator;
    private final ObjectMapper mapper = new ObjectMapper();

    public EvalRunner(RestTemplate restTemplate,
                      EvalProperties properties,
                      EvalJudge judge,
                      EvalEmbeddingComparator embeddingComparator) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.judge = judge == null ? new DisabledEvalJudge() : judge;
        this.embeddingComparator = embeddingComparator == null
                ? new DisabledEvalEmbeddingComparator()
                : embeddingComparator;
    }

    /** 便捷构造：judge / embedding 默认关闭，供纯 POJO 单测复用既有断言路径。 */
    public EvalRunner(RestTemplate restTemplate, EvalProperties properties) {
        this(restTemplate, properties, new DisabledEvalJudge(), new DisabledEvalEmbeddingComparator());
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
        String semanticExpected = evalCase.semanticExpected();
        if (semanticExpected != null && !semanticExpected.isBlank()) {
            double score = semanticSimilarity(responseBody, semanticExpected);
            double minScore = evalCase.semanticMinScore() == null ? 0.75D : evalCase.semanticMinScore();
            if (score < minScore) {
                return failure(evalCase.id(), status,
                        "semantic similarity below threshold: " + round(score) + " < " + round(minScore),
                        responseBody,
                        startedAt);
            }
        }
        EvalCaseResult judgeResult = evaluateJudge(evalCase, status, responseBody, startedAt);
        if (judgeResult != null) {
            return judgeResult;
        }
        EvalCaseResult embeddingResult = evaluateEmbedding(evalCase, status, responseBody, startedAt);
        if (embeddingResult != null) {
            return embeddingResult;
        }
        String oracle = evalCase.oracleContains();
        if (oracle != null && !oracle.isBlank() && !responseBody.contains(oracle)) {
            return oracleFailure(evalCase.id(), status, oracle, responseBody, startedAt);
        }
        return new EvalCaseResult(evalCase.id(), true, status, null, snippet(responseBody), elapsedMs(startedAt));
    }

    /**
     * 可选 LLM-judge 断言。用例未带 {@code judgeExpected} 或 judge 未配置时跳过（不影响现有确定性断言）。
     */
    private EvalCaseResult evaluateJudge(EvalCase evalCase, int status, String responseBody, Instant startedAt) {
        String judgeExpected = evalCase.judgeExpected();
        if (judgeExpected == null || judgeExpected.isBlank() || !judge.enabled()) {
            return null;
        }
        double score = judge.score(judgeExpected, responseBody);
        double minScore = evalCase.judgeMinScore() == null
                ? properties.getJudgeMinScore()
                : evalCase.judgeMinScore();
        if (score < minScore) {
            return failure(evalCase.id(), status,
                    "llm judge score below threshold: " + round(score) + " < " + round(minScore),
                    responseBody,
                    startedAt);
        }
        return null;
    }

    /**
     * 可选 embedding 相似度断言。用例未带 {@code embeddingExpected} 或 comparator 未配置时跳过。
     */
    private EvalCaseResult evaluateEmbedding(EvalCase evalCase, int status, String responseBody, Instant startedAt) {
        String embeddingExpected = evalCase.embeddingExpected();
        if (embeddingExpected == null || embeddingExpected.isBlank() || !embeddingComparator.enabled()) {
            return null;
        }
        double score = embeddingComparator.similarity(embeddingExpected, responseBody);
        double minScore = evalCase.embeddingMinScore() == null
                ? properties.getEmbeddingMinScore()
                : evalCase.embeddingMinScore();
        if (score < minScore) {
            return failure(evalCase.id(), status,
                    "embedding similarity below threshold: " + round(score) + " < " + round(minScore),
                    responseBody,
                    startedAt);
        }
        return null;
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

    static double semanticSimilarity(String actual, String expected) {
        Map<String, Integer> actualTokens = tokenCounts(actual);
        Map<String, Integer> expectedTokens = tokenCounts(expected);
        if (actualTokens.isEmpty() || expectedTokens.isEmpty()) {
            return 0D;
        }
        double dot = 0D;
        for (Map.Entry<String, Integer> entry : expectedTokens.entrySet()) {
            dot += entry.getValue() * actualTokens.getOrDefault(entry.getKey(), 0);
        }
        double actualNorm = norm(actualTokens);
        double expectedNorm = norm(expectedTokens);
        if (actualNorm == 0D || expectedNorm == 0D) {
            return 0D;
        }
        return dot / (actualNorm * expectedNorm);
    }

    private static Map<String, Integer> tokenCounts(String value) {
        Map<String, Integer> counts = new HashMap<>();
        if (value == null || value.isBlank()) {
            return counts;
        }
        StringBuilder token = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                if (isCjk(codePoint)) {
                    flushToken(counts, token);
                    counts.merge(new String(Character.toChars(codePoint)), 1, Integer::sum);
                } else {
                    token.appendCodePoint(Character.toLowerCase(codePoint));
                }
            } else {
                flushToken(counts, token);
            }
        });
        flushToken(counts, token);
        return counts;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static void flushToken(Map<String, Integer> counts, StringBuilder token) {
        if (!token.isEmpty()) {
            counts.merge(token.toString(), 1, Integer::sum);
            token.setLength(0);
        }
    }

    private static double norm(Map<String, Integer> counts) {
        double sum = 0D;
        for (int count : counts.values()) {
            sum += count * count;
        }
        return Math.sqrt(sum);
    }

    private static double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }

    private record PathResult(boolean found, Object value) {
    }
}
