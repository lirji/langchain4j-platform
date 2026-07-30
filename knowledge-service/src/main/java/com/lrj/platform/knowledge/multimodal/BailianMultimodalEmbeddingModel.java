package com.lrj.platform.knowledge.multimodal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 百炼原生多模态 embedding 实现。文本与图片都请求同一个 Qwen-VL embedding 模型，保证处于
 * 同一跨模态向量空间。
 */
public class BailianMultimodalEmbeddingModel implements MultimodalEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(BailianMultimodalEmbeddingModel.class);
    private static final String EMBEDDING_PATH =
            "/services/embeddings/multimodal-embedding/multimodal-embedding";

    private final MultimodalEmbeddingProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public BailianMultimodalEmbeddingModel(MultimodalEmbeddingProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, props.getTimeoutSeconds())))
                .build();
        log.info("BailianMultimodalEmbeddingModel ready: base-url={} model={} dim={}",
                trimSlash(props.getBaseUrl()), props.getModelName(), props.getDimension());
    }

    @Override
    public float[] embedText(String text) {
        Map<String, Object> content = Map.of("text", text == null ? "" : text);
        return requestEmbedding(content, "text");
    }

    @Override
    public float[] embedImage(byte[] image, String mimeType) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("image is empty");
        }
        if (image.length > props.getMaxImageBytes()) {
            throw new IllegalArgumentException(
                    "image too large: " + image.length + " > " + props.getMaxImageBytes() + " bytes");
        }
        String mime = mimeType != null && mimeType.startsWith("image/") ? mimeType : "image/png";
        String dataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image);
        return requestEmbedding(Map.of("image", dataUri), "image");
    }

    @Override
    public int dimension() {
        return props.getDimension();
    }

    private float[] requestEmbedding(Map<String, Object> content, String modality) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", props.getModelName());
        payload.put("input", Map.of("contents", List.of(content)));
        if (props.getDimension() > 0 && !"multimodal-embedding-v1".equals(props.getModelName())) {
            payload.put("parameters", Map.of("dimension", props.getDimension()));
        }
        try {
            String request = mapper.writeValueAsString(payload);
            if (props.isLogRequests()) {
                log.info("Bailian multimodal embedding request: modality={} model={} payloadBytes={}",
                        modality, props.getModelName(), request.getBytes(StandardCharsets.UTF_8).length);
            }
            return parseEmbedding(post(request));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize Bailian embedding request: " + e.getMessage(), e);
        }
    }

    protected String post(String jsonBody) {
        RuntimeException last = null;
        int attempts = Math.max(1, props.getMaxRetries() + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(trimSlash(props.getBaseUrl()) + EMBEDDING_PATH))
                        .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                        .header("Authorization", "Bearer " + props.getApiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = http.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() / 100 != 2) {
                    String message = "Bailian multimodal embedding failed: HTTP "
                            + response.statusCode() + " " + bounded(response.body());
                    if (response.statusCode() != 429 && response.statusCode() < 500) {
                        throw new NonRetryableRequestException(message);
                    }
                    throw new IllegalStateException(message);
                }
                return response.body();
            } catch (NonRetryableRequestException e) {
                throw e;
            } catch (Exception e) {
                last = e instanceof RuntimeException runtime
                        ? runtime
                        : new RuntimeException("Bailian multimodal embedding request failed: "
                        + e.getMessage(), e);
                log.warn("Bailian multimodal embedding attempt {}/{} failed: {}",
                        attempt, attempts, e.getMessage());
            }
        }
        throw last != null ? last : new RuntimeException("Bailian multimodal embedding request failed");
    }

    private float[] parseEmbedding(String body) {
        try {
            JsonNode embedding = mapper.readTree(body)
                    .path("output").path("embeddings").path(0).path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw new IllegalStateException(
                        "no embedding in Bailian response: " + bounded(body));
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            if (props.getDimension() > 0 && vector.length != props.getDimension()) {
                throw new IllegalStateException("Bailian multimodal embedding dimension mismatch: got "
                        + vector.length + " but configured " + props.getDimension());
            }
            return vector;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("failed to parse Bailian embedding response: " + e.getMessage(), e);
        }
    }

    private static String bounded(String body) {
        if (body == null) return "";
        return body.length() > 500 ? body.substring(0, 500) + "…" : body;
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static final class NonRetryableRequestException extends IllegalStateException {
        private NonRetryableRequestException(String message) {
            super(message);
        }
    }
}
