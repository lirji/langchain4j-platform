package com.lrj.platform.knowledge.rerank;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * 百炼 {@code qwen3-rerank} OpenAI-compatible HTTP 客户端。
 *
 * <p>注意 rerank 使用 {@code /compatible-api/v1/reranks}，与 Embedding 的
 * {@code /compatible-mode/v1/embeddings} 不是同一个 base URL。
 */
final class HttpBailianRerankClient implements BailianRerankClient {

    private final RestClient restClient;
    private final URI endpoint;
    private final String model;
    private final String instruct;

    HttpBailianRerankClient(String baseUrl,
                            String apiKey,
                            String model,
                            String instruct,
                            Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("app.rag.rerank.bailian.base-url is required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("app.rag.rerank.bailian.api-key is required");
        }
        this.model = model == null || model.isBlank() ? "qwen3-rerank" : model;
        this.instruct = instruct == null ? "" : instruct;
        this.endpoint = URI.create(stripTrailingSlash(baseUrl) + "/reranks");

        Duration effectiveTimeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        int timeoutMillis = (int) Math.min(Integer.MAX_VALUE, Math.max(1, effectiveTimeout.toMillis()));
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    @Override
    public List<RankedResult> rerank(String query, List<String> documents, int topN) {
        Request request = new Request(model, query, documents, topN, instruct.isBlank() ? null : instruct);
        Response response = restClient.post()
                .uri(endpoint)
                .body(request)
                .retrieve()
                .body(Response.class);
        if (response == null || response.results() == null) {
            throw new IllegalStateException("Bailian rerank returned no results");
        }
        return response.results().stream()
                .map(result -> new RankedResult(result.index(), result.relevanceScore()))
                .toList();
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private record Request(
            String model,
            String query,
            List<String> documents,
            @JsonProperty("top_n") int topN,
            String instruct) {
    }

    private record Response(List<Result> results) {
    }

    private record Result(
            int index,
            @JsonProperty("relevance_score") double relevanceScore) {
    }
}
