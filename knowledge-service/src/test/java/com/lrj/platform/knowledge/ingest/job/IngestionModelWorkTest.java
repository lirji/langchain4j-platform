package com.lrj.platform.knowledge.ingest.job;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static com.lrj.platform.knowledge.ingest.job.IngestionModelWork.Stage.*;

/** 确认不同阶段共享额度、失败不返还、预检查零调用及父块放大限制。 */
class IngestionModelWorkTest {
    @Test void semanticBatchesConsumeTheSameLogicalCallBudgetAsFinalEmbedding() {
        var limits=new IngestionResourceProperties();limits.setMaxBatchSegments(2);limits.setMaxModelCalls(3);
        var work=new IngestionModelWork(limits,()->{});var calls=new AtomicInteger();
        var segments=java.util.stream.IntStream.range(0,5).mapToObj(i->TextSegment.from("short "+i)).toList();
        work.embed(model(calls),segments,SEMANTIC);
        assertThat(calls).hasValue(3);
        assertThatThrownBy(()->work.embed(model(calls),List.of(TextSegment.from("final")),FINAL_EMBEDDING))
                .isInstanceOfSatisfying(IngestionResourceException.class,e->assertThat(e.error()).isEqualTo(IngestionResourceError.MODEL_WORK_LIMIT));
        assertThat(calls).hasValue(3);assertThat(work.snapshot().usage().getFirst().logicalCalls()).isEqualTo(3);
    }
    @Test void aggregateTokensAndContextCallsRejectBeforeTheNextStage() {
        var limits=new IngestionResourceProperties();limits.setMaxModelInputTokens(3);limits.setMaxContextualCalls(1);
        var work=new IngestionModelWork(limits,()->{});var calls=new AtomicInteger();
        work.embed(model(calls),List.of(TextSegment.from("one two")),SEMANTIC);
        assertThatThrownBy(()->work.preflightContext(2,1)).isInstanceOf(IngestionResourceException.class);
        assertThatThrownBy(()->work.preflightContext(0,2)).isInstanceOf(IngestionResourceException.class);
        assertThat(calls).hasValue(1);
    }
    @Test void failedRequestStillConsumesAllowanceAndBudgetsDoNotLeakAcrossDocuments() {
        var limits=new IngestionResourceProperties();limits.setMaxModelCalls(1);
        var work=new IngestionModelWork(limits,()->{});var calls=new AtomicInteger();
        EmbeddingModel failure=batch->{calls.incrementAndGet();throw new IllegalStateException("temporary model failure");};
        assertThatThrownBy(()->work.embed(failure,List.of(TextSegment.from("input")),SEMANTIC)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->work.embed(model(calls),List.of(TextSegment.from("input")),FINAL_EMBEDDING)).isInstanceOf(IngestionResourceException.class);
        new IngestionModelWork(limits,()->{}).embed(model(calls),List.of(TextSegment.from("another doc")),FINAL_EMBEDDING);
        assertThat(calls).hasValue(2);
    }
    @Test void metadataLimitPreservesParentContractAndMeasuresRepeatedBytes() {
        var limits=new IngestionResourceProperties();
        var parent="parent context";
        var segments=List.of(TextSegment.from("a",Metadata.from("parent_text",parent)),TextSegment.from("b",Metadata.from("parent_text",parent)));
        var work=new IngestionModelWork(limits,()->{});work.validateMetadata(segments);
        assertThat(work.snapshot().repeatedParentBytes()).isEqualTo(parent.length()*2);
        limits.setMaxMetadataBytes(20);
        assertThatThrownBy(()->new IngestionModelWork(limits,()->{}).validateMetadata(segments))
                .isInstanceOfSatisfying(IngestionResourceException.class,e->assertThat(e.error()).isEqualTo(IngestionResourceError.METADATA_LIMIT));
        assertThat(segments).allSatisfy(s->assertThat(s.metadata().getString("parent_text")).isEqualTo(parent));
    }
    @Test void leaseLossAtNextBatchDoesNotReachAnotherModelCall() {
        var limits=new IngestionResourceProperties();limits.setMaxBatchSegments(1);var calls=new AtomicInteger();
        var work=new IngestionModelWork(limits,()->{if(calls.get()>0)throw new IngestionJobConflictException("lost");});
        assertThatThrownBy(()->work.embed(model(calls),List.of(TextSegment.from("a"),TextSegment.from("b")),SEMANTIC))
                .isInstanceOf(IngestionJobConflictException.class);
        assertThat(calls).hasValue(1);
    }
    private static EmbeddingModel model(AtomicInteger calls) {
        return batch->{calls.incrementAndGet();return Response.from(batch.stream().map(s->Embedding.from(new float[]{1,0})).toList());};
    }
}
