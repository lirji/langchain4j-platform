package com.lrj.platform.conversation.cache;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 可选的经网关 embedder（{@code app.conversation.semantic-cache.embedding.provider=openai}）。
 *
 * <p>走 LiteLLM/OpenAI-compatible embedding 端点，默认复用 {@code platform.gateway.*} 的 base-url/api-key，
 * 也可用 {@code app.conversation.semantic-cache.embedding.*} 单独覆盖。构造期不发网络请求，只在首次 embed 时调用。
 */
@Component
@ConditionalOnProperty(name = "app.conversation.semantic-cache.embedding.provider", havingValue = "openai")
public class GatewaySemanticCacheEmbedder implements SemanticCacheEmbedder {

    private static final Logger log = LoggerFactory.getLogger(GatewaySemanticCacheEmbedder.class);

    private final EmbeddingModel delegate;

    public GatewaySemanticCacheEmbedder(
            @Value("${app.conversation.semantic-cache.embedding.base-url:${platform.gateway.base-url:http://localhost:4000/v1}}") String baseUrl,
            @Value("${app.conversation.semantic-cache.embedding.api-key:${platform.gateway.api-key:sk-litellm-master}}") String apiKey,
            @Value("${app.conversation.semantic-cache.embedding.model-name:embedding-default}") String modelName,
            @Value("${app.conversation.semantic-cache.embedding.dimensions:0}") int dimensions,
            @Value("${app.conversation.semantic-cache.embedding.timeout:60s}") String timeout,
            @Value("${app.conversation.semantic-cache.embedding.max-retries:3}") int maxRetries) {
        OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(parseDuration(timeout))
                .maxRetries(maxRetries);
        if (dimensions > 0) {
            builder.dimensions(dimensions);
        }
        this.delegate = builder.build();
        log.info("Semantic cache embedder: openai-compatible model={} baseUrl={} dimensions={}",
                modelName, baseUrl, dimensions > 0 ? dimensions : "provider-default");
    }

    /** 供测试注入 mock/确定性 {@link EmbeddingModel}。 */
    GatewaySemanticCacheEmbedder(EmbeddingModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        Embedding embedding = delegate.embed(text).content();
        return embedding.vector();
    }

    private static Duration parseDuration(String value) {
        return DurationStyle.detectAndParse(value);
    }
}
