package com.lrj.platform.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.multimodal.MultimodalEmbeddingConfig;
import com.lrj.platform.knowledge.multimodal.MultimodalRetrievalService;
import com.lrj.platform.knowledge.store.CollectionManager;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import com.lrj.platform.knowledge.store.InMemoryEmbeddingStoreRouter;
import com.lrj.platform.knowledge.store.ManagedEmbeddingStoreRouter;
import com.lrj.platform.knowledge.store.SingleEmbeddingStoreRouter;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeEmbeddingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeEmbeddingConfig.class);

    @Test
    void defaultEmbeddingProvider_isHash() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EmbeddingStore.class);
            assertThat(context).hasSingleBean(EmbeddingModel.class);
            assertThat(context.getBean(EmbeddingModel.class))
                    .isInstanceOf(KnowledgeEmbeddingConfig.HashEmbeddingModel.class);
        });
    }

    @Test
    void openAiEmbeddingProvider_isConfiguredFromProperties() {
        contextRunner
                .withPropertyValues(
                        "app.rag.embedding.provider=openai",
                        "app.rag.embedding.base-url=http://litellm:4000/v1",
                        "app.rag.embedding.api-key=sk-test",
                        "app.rag.embedding.model-name=embedding-default",
                        "app.rag.embedding.dimensions=128",
                        "app.rag.embedding.max-segments-per-batch=16")
                .run(context -> {
                    assertThat(context).hasSingleBean(EmbeddingModel.class);
                    assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(OpenAiEmbeddingModel.class);
                    OpenAiEmbeddingModel model = context.getBean(OpenAiEmbeddingModel.class);
                    assertThat(model.modelName()).isEqualTo("embedding-default");
                });
    }

    @Test
    void ollamaEmbeddingProvider_isSelectableWithoutStrongDependency() {
        contextRunner
                .withPropertyValues(
                        "app.rag.embedding.provider=ollama",
                        "app.rag.embedding.ollama.base-url=http://localhost:11434",
                        "app.rag.embedding.ollama.model-name=nomic-embed-text")
                .run(context -> {
                    assertThat(context).hasSingleBean(EmbeddingModel.class);
                    assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(OllamaEmbeddingModel.class);
                });
    }

    @Test
    void defaultRouter_isCollectionPerTenantInMemory() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EmbeddingStoreRouter.class);
            assertThat(context.getBean(EmbeddingStoreRouter.class))
                    .isInstanceOf(InMemoryEmbeddingStoreRouter.class);
        });
    }

    @Test
    void sharedIsolation_fallsBackToSingleStoreRouter() {
        contextRunner
                .withPropertyValues("app.rag.vector-store.isolation=shared")
                .run(context -> assertThat(context.getBean(EmbeddingStoreRouter.class))
                        .isInstanceOf(SingleEmbeddingStoreRouter.class));
    }

    @Test
    void qdrantVectorStore_isConfiguredFromProperties() {
        contextRunner
                .withPropertyValues(
                        "app.rag.vector-store.provider=qdrant",
                        "app.rag.vector-store.qdrant.host=qdrant",
                        "app.rag.vector-store.qdrant.port=6334",
                        "app.rag.vector-store.qdrant.collection-name=knowledge_test",
                        "app.rag.vector-store.qdrant.payload-text-key=text")
                .run(context -> {
                    assertThat(context).hasSingleBean(EmbeddingStore.class);
                    assertThat(context.getBean(EmbeddingStore.class)).isInstanceOf(QdrantEmbeddingStore.class);
                });
    }

    @Test
    void newProviders_wireCollectionManagerAndManagedRouter() {
        // pgvector / milvus / chroma / doris：各注册一个 CollectionManager，路由走通用 ManagedEmbeddingStoreRouter。
        // 构造这些 manager bean 不建连（仅存参数/解析枚举），可在无外部服务时装配。
        for (String provider : new String[]{"pgvector", "milvus", "chroma", "doris"}) {
            contextRunner
                    .withPropertyValues("app.rag.vector-store.provider=" + provider)
                    .run(context -> {
                        assertThat(context).hasSingleBean(CollectionManager.class);
                        assertThat(context.getBean(EmbeddingStoreRouter.class))
                                .isInstanceOf(ManagedEmbeddingStoreRouter.class);
                    });
        }
    }

    @Test
    void multimodalEnabled_wiresSeparateImageRouter_andPrimaryTextRouterResolves() {
        new ApplicationContextRunner()
                .withUserConfiguration(KnowledgeEmbeddingConfig.class, MultimodalEmbeddingConfig.class)
                .withBean(ObjectMapper.class)
                .withPropertyValues("app.rag.multimodal-embedding.enabled=true")
                .run(context -> {
                    // 文本 + 图片两个 EmbeddingStoreRouter 共存；@Primary 让按类型注入解析到文本 router。
                    assertThat(context.getBeansOfType(EmbeddingStoreRouter.class)).hasSize(2);
                    assertThat(context.containsBean("imageEmbeddingStoreRouter")).isTrue();
                    assertThat(context.getBean(EmbeddingStoreRouter.class))
                            .isSameAs(context.getBean("embeddingStoreRouter"));
                    assertThat(context).hasSingleBean(MultimodalRetrievalService.class);
                });
    }
}
