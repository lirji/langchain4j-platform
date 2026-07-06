package com.lrj.platform.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class KnowledgeEmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEmbeddingConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.rag.vector-store.provider", havingValue = "in-memory", matchIfMissing = true)
    public EmbeddingStore<TextSegment> embeddingStore() {
        log.info("Knowledge vector store provider: in-memory");
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.vector-store.provider", havingValue = "qdrant")
    public EmbeddingStore<TextSegment> qdrantEmbeddingStore(
            @Value("${app.rag.vector-store.qdrant.host:localhost}") String host,
            @Value("${app.rag.vector-store.qdrant.port:6334}") int port,
            @Value("${app.rag.vector-store.qdrant.collection-name:knowledge_segments}") String collectionName,
            @Value("${app.rag.vector-store.qdrant.use-tls:false}") boolean useTls,
            @Value("${app.rag.vector-store.qdrant.api-key:}") String apiKey,
            @Value("${app.rag.vector-store.qdrant.payload-text-key:text}") String payloadTextKey) {
        QdrantEmbeddingStore.Builder builder = QdrantEmbeddingStore.builder()
                .host(host)
                .port(port)
                .collectionName(collectionName)
                .useTls(useTls)
                .payloadTextKey(payloadTextKey);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.apiKey(apiKey);
        }
        log.info("Knowledge vector store provider: qdrant host={} port={} collection={} tls={}",
                host, port, collectionName, useTls);
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.embedding.provider", havingValue = "hash", matchIfMissing = true)
    public EmbeddingModel hashEmbeddingModel() {
        log.info("Knowledge embedding provider: hash (dimension={})", HashEmbeddingModel.DIMENSION);
        return new HashEmbeddingModel();
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.embedding.provider", havingValue = "openai")
    public EmbeddingModel openAiEmbeddingModel(
            @Value("${app.rag.embedding.base-url:${platform.gateway.base-url:http://localhost:4000/v1}}") String baseUrl,
            @Value("${app.rag.embedding.api-key:${platform.gateway.api-key:sk-litellm-master}}") String apiKey,
            @Value("${app.rag.embedding.model-name:${RAG_EMBEDDING_MODEL:embedding-default}}") String modelName,
            @Value("${app.rag.embedding.dimensions:0}") int dimensions,
            @Value("${app.rag.embedding.timeout:60s}") String timeout,
            @Value("${app.rag.embedding.max-retries:3}") int maxRetries,
            @Value("${app.rag.embedding.max-segments-per-batch:0}") int maxSegmentsPerBatch,
            @Value("${app.rag.embedding.log-requests:false}") boolean logRequests,
            @Value("${app.rag.embedding.log-responses:false}") boolean logResponses) {
        OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(parseDuration(timeout))
                .maxRetries(maxRetries)
                .logRequests(logRequests)
                .logResponses(logResponses);
        if (dimensions > 0) {
            builder.dimensions(dimensions);
        }
        if (maxSegmentsPerBatch > 0) {
            builder.maxSegmentsPerBatch(maxSegmentsPerBatch);
        }
        log.info("Knowledge embedding provider: openai-compatible model={} baseUrl={} dimensions={}",
                modelName, baseUrl, dimensions > 0 ? dimensions : "provider-default");
        return builder.build();
    }

    private static Duration parseDuration(String value) {
        return DurationStyle.detectAndParse(value);
    }

    static class HashEmbeddingModel implements EmbeddingModel {
        static final int DIMENSION = 64;

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> embeddings = new ArrayList<>(segments.size());
            for (TextSegment segment : segments) {
                embeddings.add(Embedding.from(vector(segment.text())));
            }
            return Response.from(embeddings);
        }

        @Override
        public int dimension() {
            return DIMENSION;
        }

        private static float[] vector(String text) {
            float[] v = new float[DIMENSION];
            if (text == null || text.isBlank()) {
                return v;
            }
            String[] tokens = text.toLowerCase().split("\\s+");
            for (String token : tokens) {
                if (token.isBlank()) {
                    continue;
                }
                byte[] hash = sha256(token);
                int idx = Byte.toUnsignedInt(hash[0]) % DIMENSION;
                v[idx] += (hash[1] & 1) == 0 ? 1.0f : -1.0f;
            }
            normalize(v);
            return v;
        }

        private static byte[] sha256(String value) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }

        private static void normalize(float[] v) {
            double sum = 0.0;
            for (float x : v) {
                sum += x * x;
            }
            if (sum == 0.0) {
                return;
            }
            float norm = (float) Math.sqrt(sum);
            for (int i = 0; i < v.length; i++) {
                v[i] = v[i] / norm;
            }
        }
    }
}
