package com.lrj.platform.knowledge.ingest.job;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** 验证预算拒绝发生在远程副作用前，分批不改变顺序且失败不会自动放大模型调用。 */
class IngestionBatcherTest {
    @Test void splitsByCountTokensAndBytesWithoutDroppingContent() {
        var limits = new IngestionResourceProperties(); limits.setMaxBatchSegments(2);
        var calls = new ArrayList<List<TextSegment>>(); var checks = new AtomicInteger();
        EmbeddingModel model = segments -> { calls.add(List.copyOf(segments));
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{s.text().length()})).toList()); };
        var segments = List.of("a", "bb", "ccc", "dddd", "eeeee").stream().map(TextSegment::from).toList();
        assertThat(new IngestionBatcher(limits).embed(model, segments, checks::incrementAndGet))
                .extracting(e -> e.vector()[0]).containsExactly(1f,2f,3f,4f,5f);
        assertThat(calls).extracting(List::size).containsExactly(2,2,1);
        assertThat(checks).hasValue(6);
        limits.setMaxBatchSegments(32); limits.setMaxBatchBytes(3);
        assertThat(new IngestionBatcher(limits).embeddingBatches(segments.subList(0,3)))
                .containsExactly(new IngestionBatcher.Batch(0,2),new IngestionBatcher.Batch(2,3));
        limits.setMaxBatchBytes(100); limits.setMaxBatchTokens(2);
        assertThat(new IngestionBatcher(limits).embeddingBatches(List.of(TextSegment.from("hello"),TextSegment.from("hello"),TextSegment.from("hello"))))
                .hasSize(2);
    }
    @Test void wholeDocumentAndSingleChunkLimitsRejectBeforeFirstCall() {
        var limits = new IngestionResourceProperties(); limits.setMaxBatchSegments(1); limits.setMaxDocumentBytes(3);
        var calls = new AtomicInteger(); EmbeddingModel model = segments -> { calls.incrementAndGet(); return null; };
        assertThatThrownBy(() -> new IngestionBatcher(limits).embed(model,List.of(TextSegment.from("aa"),TextSegment.from("bb")),()->{}))
                .isInstanceOfSatisfying(IngestionResourceException.class, e -> assertThat(e.error()).isEqualTo(IngestionResourceError.MODEL_INPUT_LIMIT));
        assertThat(calls).hasValue(0);
        limits.setMaxDocumentBytes(10000); limits.setMaxSegmentTokens(2);
        assertThatThrownBy(() -> new IngestionBatcher(limits).embed(model,List.of(TextSegment.from("word ".repeat(10))),()->{}))
                .isInstanceOf(IngestionResourceException.class);
        assertThat(calls).hasValue(0);
    }
    @Test void rejectsWrongCountsDimensionsAndNonFiniteVectors() {
        var limits = new IngestionResourceProperties(); limits.setMaxBatchSegments(1);
        var inputs = List.of(TextSegment.from("a"), TextSegment.from("b"));
        for (var bad : List.of(List.<Embedding>of(),List.of(Embedding.from(new float[]{Float.NaN})),
                List.of(Embedding.from(new float[]{Float.POSITIVE_INFINITY})))) {
            assertThatThrownBy(() -> new IngestionBatcher(limits).embed(s -> Response.from(bad), inputs, () -> {}))
                    .isInstanceOfSatisfying(IngestionResourceException.class,e -> assertThat(e.error()).isEqualTo(IngestionResourceError.MODEL_RESPONSE));
        }
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> new IngestionBatcher(limits).embed(s -> Response.from(List.of(
                Embedding.from(new float[calls.incrementAndGet()]))),inputs,()->{}))
                .isInstanceOf(IngestionResourceException.class);
    }
    @Test void vectorBudgetStopsAfterDimensionProbeAndRemoteFailureIsNotRetriedLocally() {
        var limits = new IngestionResourceProperties(); limits.setMaxBatchSegments(1); limits.setMaxVectorBytes(8);
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> new IngestionBatcher(limits).embed(s -> { calls.incrementAndGet(); return Response.from(List.of(Embedding.from(new float[4]))); },
                List.of(TextSegment.from("a"),TextSegment.from("b")),()->{}))
                .isInstanceOfSatisfying(IngestionResourceException.class,e -> assertThat(e.error()).isEqualTo(IngestionResourceError.VECTOR_LIMIT));
        assertThat(calls).hasValue(1);
        limits.setMaxVectorBytes(100); calls.set(0); var remote = new RuntimeException("429");
        assertThatThrownBy(() -> new IngestionBatcher(limits).embed(s -> { if(calls.incrementAndGet()==2) throw remote;
                    return Response.from(List.of(Embedding.from(new float[]{1}))); },
                List.of(TextSegment.from("a"),TextSegment.from("b"),TextSegment.from("c")),()->{})).isSameAs(remote);
        assertThat(calls).hasValue(2);
    }
    @Test void lostLeaseAfterResponsePreventsNextBatch() {
        var limits = new IngestionResourceProperties(); limits.setMaxBatchSegments(1); var calls = new AtomicInteger();
        assertThatThrownBy(() -> new IngestionBatcher(limits).embed(s -> { calls.incrementAndGet();
                    return Response.from(List.of(Embedding.from(new float[]{1}))); }, List.of(TextSegment.from("a"),TextSegment.from("b")),
                () -> {if(calls.get()==1) throw new IngestionJobConflictException("lost");}))
                .isInstanceOf(IngestionJobConflictException.class);
        assertThat(calls).hasValue(1);
    }
}
