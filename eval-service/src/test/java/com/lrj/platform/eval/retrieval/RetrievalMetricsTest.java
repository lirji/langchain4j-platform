package com.lrj.platform.eval.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * RetrievalMetricsTest：验证 {@link RetrievalMetrics} 的 IR 指标计算——recall/precision/MRR/hit、
 * 文件级匹配对 chunk 漂移鲁棒而精确级需完全命中、相关标注去重、以及无检索时全零。
 */
class RetrievalMetricsTest {

    @Test
    void allRelevantRetrievedInOrder_perfectMetrics() {
        var m = RetrievalMetrics.compute(List.of("a.md#0", "b.md#1"), List.of("a.md", "b.md"));
        assertThat(m.recall()).isEqualTo(1.0);
        assertThat(m.precision()).isEqualTo(1.0);
        assertThat(m.mrr()).isEqualTo(1.0);
        assertThat(m.hit()).isTrue();
    }

    @Test
    void fileLevelMatch_ignoresChunkIndex() {
        // 标注文件级 project-faq.md；检索回 project-faq.md#3 → 命中（对 chunk 漂移鲁棒）
        var m = RetrievalMetrics.compute(List.of("project-faq.md#3"), List.of("project-faq.md"));
        assertThat(m.hit()).isTrue();
        assertThat(m.recall()).isEqualTo(1.0);
    }

    @Test
    void preciseLevelMatch_requiresExactId() {
        // 标注精确级 faq.md#2；检索回 faq.md#5 → 不命中
        var miss = RetrievalMetrics.compute(List.of("faq.md#5"), List.of("faq.md#2"));
        assertThat(miss.hit()).isFalse();
        var hit = RetrievalMetrics.compute(List.of("faq.md#2"), List.of("faq.md#2"));
        assertThat(hit.hit()).isTrue();
    }

    @Test
    void mrr_reflectsFirstRelevantRank() {
        // 首个相关在第 2 位 → MRR = 1/2
        var m = RetrievalMetrics.compute(List.of("noise.md#0", "a.md#1", "b.md#2"), List.of("a.md"));
        assertThat(m.mrr()).isEqualTo(0.5);
    }

    @Test
    void precision_countsNoiseInRetrieved() {
        // 检索 4 条，2 条相关 → precision = 0.5
        var m = RetrievalMetrics.compute(
                List.of("a.md#0", "noise1.md#0", "b.md#1", "noise2.md#0"), List.of("a.md", "b.md"));
        assertThat(m.precision()).isEqualTo(0.5);
        assertThat(m.recall()).isEqualTo(1.0);
    }

    @Test
    void partialRecall() {
        // 标注 3 个，命中 1 个 → recall = 1/3
        var m = RetrievalMetrics.compute(List.of("a.md#0"), List.of("a.md", "b.md", "c.md"));
        assertThat(m.recall()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(m.hit()).isTrue();
    }

    @Test
    void noRetrieval_zeroMetrics() {
        var m = RetrievalMetrics.compute(List.of(), List.of("a.md"));
        assertThat(m.recall()).isZero();
        assertThat(m.precision()).isZero();
        assertThat(m.mrr()).isZero();
        assertThat(m.hit()).isFalse();
    }

    @Test
    void duplicateRelevant_dedupedInDenominator() {
        // 相关标注去重后分母为 1（同文件重复标注不抬高 recall 分母）
        var m = RetrievalMetrics.compute(List.of("a.md#0"), List.of("a.md", "a.md"));
        assertThat(m.relevantTotal()).isEqualTo(1);
        assertThat(m.recall()).isEqualTo(1.0);
    }

    @Test
    void filePart_stripsChunkIndex() {
        assertThat(RetrievalMetrics.filePart("file.md#3")).isEqualTo("file.md");
        assertThat(RetrievalMetrics.filePart("file.md")).isEqualTo("file.md");
    }
}
