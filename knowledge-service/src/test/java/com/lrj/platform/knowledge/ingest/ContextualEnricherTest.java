package com.lrj.platform.knowledge.ingest;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contextual 入库增强纯逻辑测试（不连模型：上下文生成经 {@link ChunkContextualizer} 桩注入）。
 */
class ContextualEnricherTest {

    @Test void tailSectionUsesNearbyOriginalTextInsteadOfDocumentBeginning() {
        for(String headingKey:List.of("breadcrumb","section")) {
        var windows=new java.util.ArrayList<String>();
        var input=List.of(TextSegment.from("UNRELATED_INTRO",Metadata.from(headingKey,"开篇")),
                TextSegment.from("BILLING_REFERENCE",Metadata.from(headingKey,"结算")),
                TextSegment.from("PAYMENT_WITHIN_30_DAYS",Metadata.from(headingKey,"结算")));
        new LlmContextualEnricher((window,chunk)->{windows.add(window);return "context";},256)
                .enrich("UNRELATED_INTRO".repeat(1000),input);
        assertThat(windows.get(2)).contains("结算","BILLING_REFERENCE","PAYMENT_WITHIN_30_DAYS").doesNotContain("UNRELATED_INTRO");
        assertThat(windows).allSatisfy(w->assertThat(w.length()).isLessThanOrEqualTo(256));
        }
    }

    @Test void entireContextCallPlanIsRejectedBeforeFirstCallAndLongPrefixIsDiscarded() {
        var limits=new com.lrj.platform.knowledge.ingest.job.IngestionResourceProperties();limits.setMaxContextualCalls(1);limits.setMaxContextPrefixTokens(2);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var enricher=new LlmContextualEnricher((d,c)->{calls.incrementAndGet();return "a much longer generated prefix with irrelevant context";},8000);
        var work=new com.lrj.platform.knowledge.ingest.job.IngestionModelWork(limits,()->{});
        org.assertj.core.api.Assertions.assertThatThrownBy(()->enricher.enrich("doc",List.of(seg("a","d"),seg("b","d")),work))
                .isInstanceOf(com.lrj.platform.knowledge.ingest.job.IngestionResourceException.class);
        assertThat(calls).hasValue(0);
        var result=enricher.enrich("doc",List.of(seg("original evidence","d")),work);
        assertThat(result.getFirst().text()).isEqualTo("original evidence");
        assertThat(result.getFirst().metadata().getString(LlmContextualEnricher.STATUS)).isEqualTo("prefix-limit");
    }

    @Test void configuredContextModelReceivesOutputTokenLimit() {
        var model=org.mockito.Mockito.mock(dev.langchain4j.model.chat.ChatModel.class);
        org.mockito.Mockito.when(model.chat(org.mockito.ArgumentMatchers.any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from("prefix")).build());
        var limits=new com.lrj.platform.knowledge.ingest.job.IngestionResourceProperties();limits.setMaxContextPrefixTokens(32);
        new ContextualEnrichmentConfig().llmContextualEnricher(model,8000,limits)
                .enrich("doc",List.of(seg("original","d")),new com.lrj.platform.knowledge.ingest.job.IngestionModelWork(limits,()->{}));
        var capture=org.mockito.ArgumentCaptor.forClass(dev.langchain4j.model.chat.request.ChatRequest.class);
        org.mockito.Mockito.verify(model).chat(capture.capture());
        assertThat(capture.getValue().parameters().maxOutputTokens()).isEqualTo(32);
    }

    private static TextSegment seg(String text, String docId) {
        return TextSegment.from(text, Metadata.from("docId", docId));
    }

    @Test
    void noop_returnsSegmentsUnchanged() {
        List<TextSegment> in = List.of(seg("chunk a", "d1"), seg("chunk b", "d1"));
        assertThat(new NoopContextualEnricher().enrich("doc", in)).isSameAs(in);
    }

    @Test
    void llm_prependsContextPrefix_preservesMetadata() {
        ChunkContextualizer stub = (docText, chunkText) -> "该片段讲：" + chunkText.substring(0, 3);
        List<TextSegment> in = List.of(seg("退款政策说明", "d1"));

        List<TextSegment> out = new LlmContextualEnricher(stub, 8000).enrich("全文...", in);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).text()).startsWith("该片段讲：退款政").contains("退款政策说明");
        assertThat(out.get(0).metadata().getString("docId")).isEqualTo("d1");
    }

    @Test
    void llm_contextualizerThrows_keepsOriginalChunk() {
        ChunkContextualizer boom = (d, c) -> {
            throw new RuntimeException("llm down");
        };
        List<TextSegment> in = List.of(seg("原文不变", "d1"));

        List<TextSegment> out = new LlmContextualEnricher(boom, 8000).enrich("doc", in);

        assertThat(out.get(0).text()).isEqualTo("原文不变");
    }

    @Test
    void llm_blankContext_keepsOriginalChunk() {
        List<TextSegment> in = List.of(seg("原文", "d1"));
        List<TextSegment> out = new LlmContextualEnricher((d, c) -> "   ", 8000).enrich("doc", in);
        assertThat(out.get(0).text()).isEqualTo("原文");
    }
}
