package com.lrj.platform.eval;

import com.lrj.platform.gateway.GatewayChatModelFactory;
import com.lrj.platform.security.OutboundServiceTokenForwarder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * eval-service 的 Spring 装配。提供评测用的 {@link org.springframework.web.client.RestTemplate}，
 * 并按 {@code app.eval.judge.enabled} / {@code app.eval.embedding.enabled} 条件装配 {@link EvalJudge}
 * 与 {@link EvalEmbeddingComparator}：默认注入禁用实现，开启后分别走 platform-gateway-client 的确定性
 * {@code ChatModel}（LLM 判官）和网关 OpenAI 兼容 embedding（向量相似度）。
 */
@Configuration
@EnableConfigurationProperties(EvalProperties.class)
public class EvalConfig {

    private static final Logger log = LoggerFactory.getLogger(EvalConfig.class);

    @Bean
    RestTemplate evalRestTemplate(RestTemplateBuilder builder,
                                  OutboundServiceTokenForwarder serviceTokenForwarder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(30))
                .additionalInterceptors(serviceTokenForwarder)
                .build();
    }

    // ---- LLM judge：默认关闭，开启后走 platform-gateway-client 确定性 ChatModel ----

    @Bean
    @ConditionalOnProperty(name = "app.eval.judge.enabled", havingValue = "false", matchIfMissing = true)
    EvalJudge disabledEvalJudge() {
        log.info("Eval LLM judge: disabled (set app.eval.judge.enabled=true to enable)");
        return new DisabledEvalJudge();
    }

    @Bean
    @ConditionalOnProperty(name = "app.eval.judge.enabled", havingValue = "true")
    EvalJudge llmEvalJudge(GatewayChatModelFactory factory) {
        log.info("Eval LLM judge: enabled (deterministic gateway ChatModel)");
        return new LlmEvalJudge(factory);
    }

    // ---- Embedding 相似度：默认关闭，开启后走网关 OpenAI 兼容 embedding ----

    @Bean
    @ConditionalOnProperty(name = "app.eval.embedding.enabled", havingValue = "false", matchIfMissing = true)
    EvalEmbeddingComparator disabledEvalEmbeddingComparator() {
        log.info("Eval embedding comparator: disabled (set app.eval.embedding.enabled=true to enable)");
        return new DisabledEvalEmbeddingComparator();
    }

    @Bean
    @ConditionalOnProperty(name = "app.eval.embedding.enabled", havingValue = "true")
    EvalEmbeddingComparator gatewayEvalEmbeddingComparator(
            @Value("${app.eval.embedding.base-url:${platform.gateway.base-url:http://localhost:4000/v1}}") String baseUrl,
            @Value("${app.eval.embedding.api-key:${platform.gateway.api-key:sk-litellm-master}}") String apiKey,
            @Value("${app.eval.embedding.model-name:${EVAL_EMBEDDING_MODEL:embedding-default}}") String modelName,
            @Value("${app.eval.embedding.dimensions:0}") int dimensions,
            @Value("${app.eval.embedding.timeout:60s}") String timeout,
            @Value("${app.eval.embedding.max-retries:3}") int maxRetries,
            @Value("${app.eval.embedding.log-requests:false}") boolean logRequests,
            @Value("${app.eval.embedding.log-responses:false}") boolean logResponses) {
        OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(DurationStyle.detectAndParse(timeout))
                .maxRetries(maxRetries)
                .logRequests(logRequests)
                .logResponses(logResponses);
        if (dimensions > 0) {
            builder.dimensions(dimensions);
        }
        EmbeddingModel embeddingModel = builder.build();
        log.info("Eval embedding comparator: enabled model={} baseUrl={} dimensions={}",
                modelName, baseUrl, dimensions > 0 ? dimensions : "provider-default");
        return new GatewayEvalEmbeddingComparator(embeddingModel);
    }
}
