package com.lrj.platform.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrefixingEmbeddingModelTest {

    /** 捕获委托收到的文本,便于断言前缀。 */
    static class CapturingEmbeddingModel implements EmbeddingModel {
        final List<String> stringsSeen = new ArrayList<>();
        final List<String> segmentTextsSeen = new ArrayList<>();

        @Override
        public Response<Embedding> embed(String text) {
            stringsSeen.add(text);
            return Response.from(Embedding.from(new float[]{1f}));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> out = new ArrayList<>();
            for (TextSegment s : segments) {
                segmentTextsSeen.add(s.text());
                out.add(Embedding.from(new float[]{1f}));
            }
            return Response.from(out);
        }

        @Override
        public int dimension() {
            return 768;
        }
    }

    @Test
    void embedString_getsQueryPrefix() {
        CapturingEmbeddingModel delegate = new CapturingEmbeddingModel();
        EmbeddingModel model = new PrefixingEmbeddingModel(delegate, "search_query: ", "search_document: ");

        model.embed("退款要几天");

        assertThat(delegate.stringsSeen).containsExactly("search_query: 退款要几天");
    }

    @Test
    void embedAll_getsDocumentPrefix_andDoesNotMutateInput() {
        CapturingEmbeddingModel delegate = new CapturingEmbeddingModel();
        EmbeddingModel model = new PrefixingEmbeddingModel(delegate, "search_query: ", "search_document: ");
        TextSegment original = TextSegment.from("退款政策正文");

        List<Embedding> embeddings = model.embedAll(List.of(original)).content();

        // 送去 embed 的是带前缀的副本
        assertThat(delegate.segmentTextsSeen).containsExactly("search_document: 退款政策正文");
        // 调用方持有的原始 segment 不被改动(存储用它,不能带前缀)
        assertThat(original.text()).isEqualTo("退款政策正文");
        assertThat(embeddings).hasSize(1);
    }

    @Test
    void emptyPrefixes_passThroughUnchanged() {
        CapturingEmbeddingModel delegate = new CapturingEmbeddingModel();
        EmbeddingModel model = new PrefixingEmbeddingModel(delegate, "", "");

        model.embed("hi");
        model.embedAll(List.of(TextSegment.from("doc")));

        assertThat(delegate.stringsSeen).containsExactly("hi");
        assertThat(delegate.segmentTextsSeen).containsExactly("doc");
        assertThat(model.dimension()).isEqualTo(768);
    }
}
