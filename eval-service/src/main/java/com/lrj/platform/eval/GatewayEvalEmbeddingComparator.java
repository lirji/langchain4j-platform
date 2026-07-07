package com.lrj.platform.eval;

import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 经网关 OpenAI 兼容 embedding 端点计算余弦相似度的比较器。
 *
 * <p>构造方式参考 knowledge-service 的 {@code KnowledgeEmbeddingConfig}：默认复用
 * {@code platform.gateway.*} 的 base-url / api-key 指向 LiteLLM。{@link EmbeddingModel} 藏在本类后，
 * {@link EvalRunner} 只依赖 {@link EvalEmbeddingComparator} 接口，便于单测 mock。
 */
public class GatewayEvalEmbeddingComparator implements EvalEmbeddingComparator {

    private static final Logger log = LoggerFactory.getLogger(GatewayEvalEmbeddingComparator.class);

    private final EmbeddingModel embeddingModel;

    public GatewayEvalEmbeddingComparator(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public double similarity(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) {
            return 0.0D;
        }
        try {
            float[] expectedVector = embeddingModel.embed(expected).content().vector();
            float[] actualVector = embeddingModel.embed(actual).content().vector();
            return cosine(expectedVector, actualVector);
        } catch (RuntimeException ex) {
            log.warn("Embedding similarity failed, treating as 0: {}", ex.getMessage());
            return 0.0D;
        }
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0D;
        }
        double dot = 0.0D;
        double normA = 0.0D;
        double normB = 0.0D;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0D || normB == 0.0D) {
            return 0.0D;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
