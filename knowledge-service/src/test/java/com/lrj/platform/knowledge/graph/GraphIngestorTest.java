package com.lrj.platform.knowledge.graph;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class GraphIngestorTest {

    private final InMemoryGraphStore store = new InMemoryGraphStore();

    @Test
    void ingestsPipeTriplesWithAliasesAndWhitelist() {
        GraphIngestor ingestor = new GraphIngestor(
                new RuleBasedGraphExtractor(),
                store,
                10,
                Set.of("隶属于"),
                Map.of("张三经理", "张三"),
                Runnable::run,
                false);
        TextSegment segment = TextSegment.from(
                "张三经理|隶属于|研发部\n张三|喜欢|咖啡",
                Metadata.from(Map.of(
                        "tenantId", "acme",
                        "file_name", "people.md",
                        "index", "0",
                        "category", "org")));

        ingestor.ingest(List.of(segment));

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.neighbors(Set.of("张三"), 1, "acme", "org"))
                .extracting(Triple::subject, Triple::relation, Triple::object, Triple::sourceId)
                .containsExactly(tuple("张三", "隶属于", "研发部", "people.md#0"));
    }

    @Test
    void removeBySourcePrefixClearsDocumentTriples() {
        GraphIngestor ingestor = new GraphIngestor(
                new RuleBasedGraphExtractor(),
                store,
                10,
                Set.of(),
                Map.of(),
                Runnable::run,
                false);
        TextSegment segment = TextSegment.from(
                "张三|隶属于|研发部",
                Metadata.from(Map.of("tenantId", "acme", "file_name", "people.md", "index", "0")));

        ingestor.ingest(List.of(segment));
        int removed = ingestor.removeBySourcePrefix("acme", "people.md#");

        assertThat(removed).isEqualTo(1);
        assertThat(store.size()).isZero();
    }
}
