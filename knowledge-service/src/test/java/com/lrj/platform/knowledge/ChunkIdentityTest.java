package com.lrj.platform.knowledge;

import com.lrj.platform.knowledge.es.EsSegmentDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证最终编号能形成唯一 ES 主键，防止内层 splitter 从零编号造成静默覆盖。 */
class ChunkIdentityTest {

    @Test
    void longMarkdownSectionsHaveUniqueFinalIndices() {
        assertUnique(new MarkdownHeaderSplitter(80, DocumentSplitters.recursive(80, 0)),
                "## Alpha\n" + "alpha beta gamma delta ".repeat(15)
                        + "\n## Beta\n" + "epsilon zeta eta theta ".repeat(15));
    }

    @Test
    void childrenOfDifferentParentsHaveUniqueFinalIndices() {
        List<TextSegment> chunks = assertUnique(new ParentChildSplitter(
                        DocumentSplitters.recursive(140, 0), DocumentSplitters.recursive(45, 0)),
                "alpha beta gamma delta epsilon zeta eta theta ".repeat(14));
        assertThat(chunks.stream().map(s -> s.metadata().getString(ParentChildSplitter.PARENT_ID)).distinct())
                .hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(s -> assertThat(s.metadata().getString(ParentChildSplitter.PARENT_TEXT))
                .contains(s.text()));
    }

    @Test
    void recursiveFallbackInsideDifferentSemanticGroupsHasUniqueFinalIndices() {
        EmbeddingModel model = segments -> Response.from(segments.stream()
                .map(s -> Embedding.from(s.text().contains("猫") ? new float[]{1, 0} : new float[]{0, 1}))
                .toList());
        assertUnique(new SemanticChunkingSplitter(model, 0, 95, 80, 0,
                        DocumentSplitters.recursive(80, 0), null),
                "这是关于猫的一句话。".repeat(25) + "这是关于股票的一句话。".repeat(25));
    }

    private static List<TextSegment> assertUnique(DocumentSplitter splitter, String text) {
        Document doc = Document.from(text);
        doc.metadata().put("tenantId", "tenant-a").put("docId", "manual")
                .put("version", "2").put("file_name", "manual.md");
        List<TextSegment> chunks = splitter.split(doc);
        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks.stream().map(s -> s.metadata().getString("index")))
                .containsExactlyElementsOf(IntStream.range(0, chunks.size()).mapToObj(Integer::toString).toList());
        assertThat(chunks).allSatisfy(s -> {
            assertThat(s.metadata().getString("tenantId")).isEqualTo("tenant-a");
            assertThat(s.metadata().getString("docId")).isEqualTo("manual");
            assertThat(s.metadata().getString("version")).isEqualTo("2");
        });
        assertThat(chunks.stream().map(s -> new EsSegmentDocument("tenant-a", "manual", "manual.md", null,
                        s.metadata().getString("index"), "2", s.text(), 0).id()))
                .doesNotHaveDuplicates();
        return chunks;
    }
}
