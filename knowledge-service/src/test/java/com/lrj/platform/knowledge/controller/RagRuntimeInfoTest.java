package com.lrj.platform.knowledge.controller;

import com.lrj.platform.protocol.knowledge.KnowledgeRuntimeView.RagRuntime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RagRuntimeInfoTest：验证 {@link RagRuntimeInfo} 及其 {@code view()} 到
 * {@link com.lrj.platform.protocol.knowledge.KnowledgeRuntimeView.RagRuntime} 协议视图的映射。
 * 覆盖 embedding provider 的语义/降级判定（ollama、openai 为语义，hash 为降级占位）、模型名选取，
 * 以及融合策略随 ES 开关的默认值（开→rrf、关→weighted_max）与显式配置优先。
 */
class RagRuntimeInfoTest {

    @Test
    void ollamaProvider_isSemantic_andUsesOllamaModel() {
        var info = new RagRuntimeInfo("ollama", "embedding-default", "nomic-embed-text",
                "qdrant", true, "", true, true, false);

        RagRuntime view = info.view();

        assertThat(view.semantic()).isTrue();
        assertThat(view.embeddingProvider()).isEqualTo("ollama");
        assertThat(view.embeddingModel()).isEqualTo("nomic-embed-text");
        assertThat(view.vectorStoreProvider()).isEqualTo("qdrant");
    }

    @Test
    void hashProvider_isDegraded_andModelIsPlaceholder() {
        var info = new RagRuntimeInfo("hash", "embedding-default", "nomic-embed-text",
                "in-memory", false, "", false, true, false);

        RagRuntime view = info.view();

        assertThat(view.semantic()).isFalse();
        assertThat(view.embeddingModel()).isEqualTo("hash");
        assertThat(view.vectorStoreProvider()).isEqualTo("in-memory");
    }

    @Test
    void openAiProvider_usesModelName() {
        var info = new RagRuntimeInfo("openai", "text-embedding-3-small", "nomic-embed-text",
                "pgvector", false, "weighted_max", false, true, false);

        RagRuntime view = info.view();

        assertThat(view.semantic()).isTrue();
        assertThat(view.embeddingModel()).isEqualTo("text-embedding-3-small");
    }

    @Test
    void fusionStrategy_blankDefaultsByEsSwitch() {
        var esOn = new RagRuntimeInfo("ollama", "m", "nomic-embed-text",
                "qdrant", true, "", true, true, false);
        var esOff = new RagRuntimeInfo("hash", "m", "nomic-embed-text",
                "in-memory", false, "", false, true, false);

        assertThat(esOn.view().fusionStrategy()).isEqualTo("rrf");
        assertThat(esOff.view().fusionStrategy()).isEqualTo("weighted_max");
    }

    @Test
    void fusionStrategy_explicitValueWins() {
        var info = new RagRuntimeInfo("ollama", "m", "nomic-embed-text",
                "qdrant", true, "weighted_max", true, true, false);

        assertThat(info.view().fusionStrategy()).isEqualTo("weighted_max");
    }
}
