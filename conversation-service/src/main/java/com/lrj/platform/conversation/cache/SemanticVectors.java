package com.lrj.platform.conversation.cache;

/**
 * 语义缓存用的向量相似度工具。embedder 产出的向量已归一化，这里用余弦相似度做最近邻打分。
 */
final class SemanticVectors {

    private SemanticVectors() {
    }

    /**
     * 余弦相似度，范围 [-1, 1]。长度不一致或任一向量为零向量时返回 0（视为不相似），避免误命中。
     */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
