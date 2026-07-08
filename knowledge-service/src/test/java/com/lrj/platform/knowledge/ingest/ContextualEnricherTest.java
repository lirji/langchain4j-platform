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
