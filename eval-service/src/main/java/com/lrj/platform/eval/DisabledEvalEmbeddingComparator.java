package com.lrj.platform.eval;

/**
 * 默认关闭的 embedding 比较实现。未开启 {@code app.eval.embedding.enabled} 时装配，保证零依赖
 * dev/test，用例即使带 {@code embeddingExpected} 也会被 {@link EvalRunner} 跳过。
 */
public class DisabledEvalEmbeddingComparator implements EvalEmbeddingComparator {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public double similarity(String expected, String actual) {
        return 0.0D;
    }
}
