package com.lrj.platform.knowledge.es;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticsearchSegmentIndexerTest {

    private final FakeEsGateway gateway = new FakeEsGateway();

    private static EsRagProperties props(boolean enabled, boolean failFast) {
        EsRagProperties p = new EsRagProperties();
        p.setEnabled(enabled);
        p.setIndexEnabled(true);
        p.setFailFast(failFast);
        return p;
    }

    private static TextSegment segment(String tenantId, String docId, String index, String text) {
        Metadata md = new Metadata();
        if (tenantId != null) md.put("tenantId", tenantId);
        if (docId != null) md.put("docId", docId);
        if (index != null) md.put("index", index);
        md.put("displayName", "doc.md").put("category", "manual").put("version", "1");
        return TextSegment.from(text, md);
    }

    @Test
    void index_mapsSegmentsToEsDocs() {
        var indexer = new ElasticsearchSegmentIndexer(gateway, props(true, false));
        indexer.index(List.of(segment("acme", "d1", "0", "hello"), segment("acme", "d1", "1", "world")));

        assertThat(gateway.indexed).hasSize(2);
        assertThat(gateway.indexed.get(0).id()).isEqualTo("acme/d1/0");
        assertThat(gateway.indexed.get(0).tenantId()).isEqualTo("acme");
        assertThat(gateway.indexed.get(0).text()).isEqualTo("hello");
    }

    @Test
    void index_skipsSegmentsMissingRequiredMetadata() {
        var indexer = new ElasticsearchSegmentIndexer(gateway, props(true, false));
        indexer.index(List.of(segment("acme", null, "0", "no docId"), segment("acme", "d1", "0", "ok")));
        assertThat(gateway.indexed).hasSize(1);
        assertThat(gateway.indexed.get(0).docId()).isEqualTo("d1");
    }

    @Test
    void index_isNoopWhenIndexInactive() {
        var indexer = new ElasticsearchSegmentIndexer(gateway, props(false, false));
        indexer.index(List.of(segment("acme", "d1", "0", "x")));
        assertThat(gateway.indexed).isEmpty();
        assertThat(gateway.ensureIndexCalls).isZero(); // 关闭时连 ensureIndex 都不碰 ES
    }

    @Test
    void index_ensuresIndexLazilyOnceAcrossCalls() {
        // #3：建索引在首次写时惰性触发且只一次（不在 bean 创建/启动期连 ES）。
        var indexer = new ElasticsearchSegmentIndexer(gateway, props(true, false));
        indexer.index(List.of(segment("acme", "d1", "0", "a")));
        indexer.index(List.of(segment("acme", "d1", "1", "b")));
        assertThat(gateway.ensureIndexCalls).isEqualTo(1);
        assertThat(gateway.indexed).hasSize(2);
    }

    @Test
    void index_bestEffortSwallowsBulkError() {
        gateway.bulkError = new IllegalStateException("boom");
        var indexer = new ElasticsearchSegmentIndexer(gateway, props(true, false));
        // 不抛（best-effort）
        indexer.index(List.of(segment("acme", "d1", "0", "x")));
        assertThat(gateway.indexed).isEmpty();
    }

    @Test
    void index_failFastRethrows() {
        gateway.bulkError = new IllegalStateException("boom");
        var indexer = new ElasticsearchSegmentIndexer(gateway, props(true, true));
        assertThatThrownBy(() -> indexer.index(List.of(segment("acme", "d1", "0", "x"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void deleteByDoc_delegatesToGateway() {
        var indexer = new ElasticsearchSegmentIndexer(gateway, props(true, false));
        indexer.deleteByDoc("acme", "d1");
        assertThat(gateway.deleted).hasSize(1);
        assertThat(gateway.deleted.get(0)).containsExactly("acme", "d1");
    }
}
