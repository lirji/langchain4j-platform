package com.lrj.platform.knowledge;

import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import com.lrj.platform.knowledge.store.InMemoryEmbeddingStoreRouter;
import com.lrj.platform.knowledge.store.QdrantClientCollectionManager;
import com.lrj.platform.knowledge.store.QdrantEmbeddingStoreRouter;
import com.lrj.platform.knowledge.store.QdrantHealthIndicator;
import com.lrj.platform.knowledge.store.SingleEmbeddingStoreRouter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

    // ---------------------------------------------------------------------
    // 向量 store（单例 —— 供 shared 隔离模式与 collection 探测复用；per-tenant 走 router）
    // ---------------------------------------------------------------------

    @Bean
    @ConditionalOnProperty(name = "app.rag.vector-store.provider", havingValue = "in-memory", matchIfMissing = true)
    public EmbeddingStore<TextSegment> embeddingStore() {
        log.info("Knowledge vector store provider: in-memory");
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.vector-store.provider", havingValue = "qdrant")
    public QdrantClient knowledgeQdrantClient(
            @Value("${app.rag.vector-store.qdrant.host:localhost}") String host,
            @Value("${app.rag.vector-store.qdrant.port:6334}") int port,
            @Value("${app.rag.vector-store.qdrant.use-tls:false}") boolean useTls,
            @Value("${app.rag.vector-store.qdrant.api-key:}") String apiKey,
            @Value("${app.rag.vector-store.qdrant.timeout:10s}") String timeout) {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, port, useTls)
                .withTimeout(parseDuration(timeout));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.withApiKey(apiKey);
        }
        log.info("Knowledge Qdrant client host={} port={} tls={} timeout={}", host, port, useTls, timeout);
        return new QdrantClient(builder.build());
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.vector-store.provider", havingValue = "qdrant")
    public EmbeddingStore<TextSegment> qdrantEmbeddingStore(
            QdrantClient knowledgeQdrantClient,
            @Value("${app.rag.vector-store.qdrant.collection-name:knowledge_segments}") String collectionName,
            @Value("${app.rag.vector-store.qdrant.payload-text-key:text}") String payloadTextKey) {
        log.info("Knowledge vector store provider: qdrant collection={} (shared-mode base)", collectionName);
        return QdrantEmbeddingStore.builder()
                .client(knowledgeQdrantClient)
                .collectionName(collectionName)
                .payloadTextKey(payloadTextKey)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.vector-store.provider", havingValue = "qdrant")
    public QdrantHealthIndicator qdrantHealthIndicator(
            QdrantClient knowledgeQdrantClient,
            @Value("${app.rag.vector-store.qdrant.health-timeout:3s}") String healthTimeout) {
        return new QdrantHealthIndicator(knowledgeQdrantClient, parseDuration(healthTimeout));
    }

    // ---------------------------------------------------------------------
    // 按租户路由器（collection-per-tenant 强隔离，默认开启）
    // ---------------------------------------------------------------------

    @Bean
    public EmbeddingStoreRouter embeddingStoreRouter(
            EmbeddingModel embeddingModel,
            ObjectProvider<EmbeddingStore<TextSegment>> baseStoreProvider,
            ObjectProvider<QdrantClient> qdrantClientProvider,
            @Value("${app.rag.vector-store.provider:in-memory}") String provider,
            @Value("${app.rag.vector-store.isolation:collection-per-tenant}") String isolation,
            @Value("${app.rag.vector-store.qdrant.collection-name:knowledge_segments}") String baseCollection,
            @Value("${app.rag.vector-store.qdrant.payload-text-key:text}") String payloadTextKey,
            @Value("${app.rag.vector-store.qdrant.auto-create-payload-index:true}") boolean autoCreatePayloadIndex,
            @Value("${app.rag.vector-store.qdrant.timeout:10s}") String timeout) {
        boolean shared = "shared".equalsIgnoreCase(isolation);
        if (shared) {
            log.info("Knowledge tenant isolation: shared (single store + metadata filter)");
            return new SingleEmbeddingStoreRouter(baseStoreProvider.getObject(), embeddingModel.dimension());
        }
        if ("qdrant".equalsIgnoreCase(provider)) {
            log.info("Knowledge tenant isolation: collection-per-tenant (qdrant base collection={})", baseCollection);
            QdrantClientCollectionManager manager = new QdrantClientCollectionManager(
                    qdrantClientProvider.getObject(), payloadTextKey, parseDuration(timeout), autoCreatePayloadIndex);
            return new QdrantEmbeddingStoreRouter(manager, baseCollection);
        }
        log.info("Knowledge tenant isolation: collection-per-tenant (in-memory, one store per tenant)");
        return new InMemoryEmbeddingStoreRouter();
    }

    // ---------------------------------------------------------------------
    // Embedding provider（默认 hash，零外部依赖）
    // ---------------------------------------------------------------------

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

    @Bean
    @ConditionalOnProperty(name = "app.rag.embedding.provider", havingValue = "ollama")
    public EmbeddingModel ollamaEmbeddingModel(
            @Value("${app.rag.embedding.ollama.base-url:${RAG_EMBEDDING_OLLAMA_BASE_URL:http://localhost:11434}}") String baseUrl,
            @Value("${app.rag.embedding.ollama.model-name:${RAG_EMBEDDING_MODEL:nomic-embed-text}}") String modelName,
            @Value("${app.rag.embedding.timeout:60s}") String timeout,
            @Value("${app.rag.embedding.max-retries:3}") int maxRetries,
            @Value("${app.rag.embedding.log-requests:false}") boolean logRequests,
            @Value("${app.rag.embedding.log-responses:false}") boolean logResponses) {
        log.info("Knowledge embedding provider: ollama model={} baseUrl={}", modelName, baseUrl);
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(parseDuration(timeout))
                .maxRetries(maxRetries)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }

    private static Duration parseDuration(String value) {
        return DurationStyle.detectAndParse(value);
    }

    public static class HashEmbeddingModel implements EmbeddingModel {
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
