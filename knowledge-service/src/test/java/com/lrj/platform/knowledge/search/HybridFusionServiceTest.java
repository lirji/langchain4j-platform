package com.lrj.platform.knowledge.search;

import com.lrj.platform.knowledge.KnowledgeQueryService.Hit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HybridFusionServiceTest：验证 {@link HybridFusionService} 的多路结果融合。WEIGHTED_MAX 分支将向量/关键词/ES
 * 命中同一 chunk 合并为 hybrid 并取最高分、同源保留更高分、graph 结果独立成条；RRF 分支按倒数名次排序、
 * 同源重复 chunk 只计一次最佳名次、多源命中标记为 hybrid；两分支均保留公共库 shared 可见性并在冲突时 fail-safe 取 tenant。
 */
class HybridFusionServiceTest {

    private final HybridFusionService fusion = new HybridFusionService();

    private static RetrievalHit hit(String source, String mergeKey, double score) {
        return hit(source, mergeKey, score, false);
    }

    private static RetrievalHit hit(String source, String mergeKey, double score, boolean shared) {
        String docId = mergeKey.contains("#") ? mergeKey.substring(0, mergeKey.indexOf('#')) : mergeKey;
        String index = mergeKey.contains("#") ? mergeKey.substring(mergeKey.indexOf('#') + 1) : "0";
        return new RetrievalHit(source + ":" + mergeKey, mergeKey, score,
                docId, docId + ".md", "manual", index, "text", source, shared);
    }

    @Test
    void weightedMax_mergesVectorAndKeywordSameChunkIntoHybridWithMaxScore() {
        List<RetrievalHit> vector = List.of(hit("vector", "doc1#0", 0.9));
        List<RetrievalHit> keyword = List.of(hit("keyword", "doc1#0", 0.5));

        List<Hit> out = fusion.fuse(List.of(vector, keyword), FusionStrategy.WEIGHTED_MAX, 60);

        assertThat(out).singleElement().satisfies(h -> {
            assertThat(h.source()).isEqualTo("hybrid");
            assertThat(h.score()).isEqualTo(0.9); // max
            assertThat(h.id()).isEqualTo("vector:doc1#0"); // 保留先到（向量）的 id
        });
    }

    @Test
    void weightedMax_keywordOnlyStaysKeyword() {
        List<Hit> out = fusion.fuse(List.of(List.of(), List.of(hit("keyword", "doc1#0", 0.4))),
                FusionStrategy.WEIGHTED_MAX, 60);
        assertThat(out).singleElement().satisfies(h -> {
            assertThat(h.source()).isEqualTo("keyword");
            assertThat(h.score()).isEqualTo(0.4);
        });
    }

    @Test
    void weightedMax_sameSourceKeepsHigher() {
        List<RetrievalHit> vectorVariants = List.of(hit("vector", "doc1#0", 0.6), hit("vector", "doc1#0", 0.8));
        List<Hit> out = fusion.fuse(List.of(vectorVariants), FusionStrategy.WEIGHTED_MAX, 60);
        assertThat(out).singleElement().satisfies(h -> {
            assertThat(h.source()).isEqualTo("vector");
            assertThat(h.score()).isEqualTo(0.8);
        });
    }

    @Test
    void weightedMax_graphStaysIndependent() {
        List<RetrievalHit> vector = List.of(hit("vector", "doc1#0", 0.9));
        RetrievalHit g = new RetrievalHit("graph:x", "graph:x", 0.75, null, "people.md", "org", "0", "张三-->研发部", "graph", false);
        List<Hit> out = fusion.fuse(List.of(vector, List.of(g)), FusionStrategy.WEIGHTED_MAX, 60);
        assertThat(out).hasSize(2);
        assertThat(out).anySatisfy(h -> assertThat(h.source()).isEqualTo("graph"));
        assertThat(out.get(0).source()).isEqualTo("vector"); // 0.9 > 0.75 排前
    }

    @Test
    void weightedMax_esAndVectorSameChunkBecomeHybrid() {
        List<RetrievalHit> vector = List.of(hit("vector", "doc1#0", 0.82));
        List<RetrievalHit> es = List.of(hit("es", "doc1#0", 0.95));
        List<Hit> out = fusion.fuse(List.of(vector, es), FusionStrategy.WEIGHTED_MAX, 60);
        assertThat(out).singleElement().satisfies(h -> {
            assertThat(h.source()).isEqualTo("hybrid");
            assertThat(h.score()).isEqualTo(0.95);
        });
    }

    @Test
    void weightedMax_preservesSharedVisibilityForPublicHit() {
        // 公共库命中（shared=true）单独存在时，visibility 不丢。
        List<RetrievalHit> vector = List.of(hit("vector", "pub1#0", 0.9, true));
        List<Hit> out = fusion.fuse(List.of(vector), FusionStrategy.WEIGHTED_MAX, 60);
        assertThat(out).singleElement().satisfies(h -> assertThat(h.shared()).isTrue());
    }

    @Test
    void weightedMax_conflictingVisibilitySameChunkFailsSafeToTenant() {
        // 极端情形：同一 mergeKey 一源标 tenant、一源标 public → AND fail-safe 取 tenant（不误标共享）。
        List<RetrievalHit> vector = List.of(hit("vector", "doc1#0", 0.9, false));
        List<RetrievalHit> es = List.of(hit("es", "doc1#0", 0.8, true));
        List<Hit> out = fusion.fuse(List.of(vector, es), FusionStrategy.WEIGHTED_MAX, 60);
        assertThat(out).singleElement().satisfies(h -> {
            assertThat(h.source()).isEqualTo("hybrid");
            assertThat(h.shared()).isFalse();
        });
    }

    @Test
    void rrf_preservesSharedAndFailsSafeOnConflict() {
        // RRF 分支同样：纯 public 命中保 public；tenant+public 冲突取 tenant。
        List<Hit> pub = fusion.fuse(List.of(List.of(hit("vector", "pub1#0", 0.9, true))), FusionStrategy.RRF, 60);
        assertThat(pub).singleElement().satisfies(h -> assertThat(h.shared()).isTrue());

        List<RetrievalHit> vector = List.of(hit("vector", "doc1#0", 0.9, false));
        List<RetrievalHit> es = List.of(hit("es", "doc1#0", 12.0, true));
        List<Hit> mixed = fusion.fuse(List.of(vector, es), FusionStrategy.RRF, 60);
        assertThat(mixed).singleElement().satisfies(h -> {
            assertThat(h.source()).isEqualTo("hybrid");
            assertThat(h.shared()).isFalse();
        });
    }

    @Test
    void rrf_dedupsSameSourceDuplicateChunk() {
        // #7：向量多变体命中同一 chunk（doc1#0 出现两次）→ RRF 该源对 doc1 只记一次最佳名次，不重复加权。
        List<RetrievalHit> vectorVariants = List.of(
                hit("vector", "doc1#0", 0.6),
                hit("vector", "doc2#0", 0.5),
                hit("vector", "doc1#0", 0.9)); // 同 chunk 再次命中（另一变体）
        List<Hit> out = fusion.fuse(List.of(vectorVariants), FusionStrategy.RRF, 60);

        assertThat(out).hasSize(2);
        Hit top = out.get(0);
        assertThat(top.docId()).isEqualTo("doc1");
        assertThat(top.source()).isEqualTo("vector");
        assertThat(top.score()).isEqualTo(1.0 / 61.0); // rank1 一次，而非 2/61
        assertThat(out.get(1).docId()).isEqualTo("doc2");
        assertThat(out.get(1).score()).isEqualTo(1.0 / 62.0);
    }

    @Test
    void rrf_ranksByReciprocalRankAndMarksMultiSourceHybrid() {
        // doc1 命中 vector(rank1) + es(rank1) → 2/(60+1)；doc2 只命中 vector(rank2) → 1/(60+2)
        List<RetrievalHit> vector = List.of(hit("vector", "doc1#0", 0.9), hit("vector", "doc2#0", 0.7));
        List<RetrievalHit> es = List.of(hit("es", "doc1#0", 12.0));

        List<Hit> out = fusion.fuse(List.of(vector, es), FusionStrategy.RRF, 60);

        assertThat(out).hasSize(2);
        Hit top = out.get(0);
        assertThat(top.docId()).isEqualTo("doc1");
        assertThat(top.source()).isEqualTo("hybrid"); // 命中两源
        assertThat(top.score()).isEqualTo(2.0 / 61.0);
        assertThat(out.get(1).source()).isEqualTo("vector");
        assertThat(out.get(1).score()).isEqualTo(1.0 / 62.0);
    }
}
