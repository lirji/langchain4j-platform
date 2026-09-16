package com.lrj.platform.knowledge.search;

import com.lrj.platform.knowledge.graph.GraphSearchService;
import com.lrj.platform.knowledge.graph.InMemoryGraphStore;
import com.lrj.platform.knowledge.graph.TokenEntityLinker;
import com.lrj.platform.knowledge.graph.Triple;
import com.lrj.platform.knowledge.hybrid.SimpleKeywordTokenizer;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GraphRetrievalSourceTest：图命中必须带上从 sourceId 还原的文档版本 provenance，否则它无法参与
 * 「按 Registry 当前版本过滤」与 enforce 档的文档级判权——这正是 query 角色此前整体禁用图检索的原因。
 */
class GraphRetrievalSourceTest {

    private final InMemoryGraphStore store = new InMemoryGraphStore();
    private final GraphSearchService searchService = new GraphSearchService(
            store, new TokenEntityLinker(store, new SimpleKeywordTokenizer()), 2, 20);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void versionedTripleCarriesDocumentAndVersionIntoTheHit() {
        store.add(List.of(new Triple("张三", "隶属于", "研发部",
                "a1b2c3d4e5f60718/v3/people.md#7", "acme", "org")));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        List<RetrievalHit> hits = source(false).retrieve(request("张三", "org"));

        assertThat(hits).singleElement()
                .satisfies(hit -> {
                    assertThat(hit.docId()).isEqualTo("a1b2c3d4e5f60718");
                    assertThat(hit.version()).isEqualTo("3");
                    assertThat(hit.displayName()).isEqualTo("people.md");
                    assertThat(hit.index()).isEqualTo("7");
                    assertThat(hit.source()).isEqualTo("graph");
                    assertThat(hit.shared()).isFalse();
                });
    }

    @Test
    void legacyTripleIsServedWithoutProvenanceWhenNotRequired() {
        // combined 默认口径：没有 provenance 的历史三元组照旧召回，展示名解析与引入版本前一致
        store.add(List.of(new Triple("张三", "隶属于", "研发部", "people.md#0", "acme", "org")));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        List<RetrievalHit> hits = source(false).retrieve(request("张三", "org"));

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.docId()).isNull();
            assertThat(hit.version()).isNull();
            assertThat(hit.displayName()).isEqualTo("people.md");
            assertThat(hit.index()).isEqualTo("0");
        });
    }

    @Test
    void requireProvenanceDropsLegacyTriplesAndKeepsVersionedOnes() {
        store.add(List.of(
                new Triple("张三", "隶属于", "研发部", "people.md#0", "acme", "org"),
                new Triple("张三", "隶属于", "平台组", "a1b2c3d4e5f60718/v3/people.md#7", "acme", "org")));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        List<RetrievalHit> hits = source(true).retrieve(request("张三", "org"));

        assertThat(hits).singleElement()
                .satisfies(hit -> assertThat(hit.docId()).isEqualTo("a1b2c3d4e5f60718"));
    }

    @Test
    void mergeKeyStaysUniquePerTripleSoGraphNeverMergesIntoChunkHits() {
        // provenance 引入后图命中有了 docId/index，但融合去重键仍必须是三元组自身 id，
        // 否则同文档同 chunk 的向量命中会把图命中吞掉（原 putIfAbsent 语义）。
        store.add(List.of(
                new Triple("张三", "隶属于", "研发部", "a1b2c3d4e5f60718/v3/people.md#7", "acme", "org"),
                new Triple("张三", "汇报给", "李四", "a1b2c3d4e5f60718/v3/people.md#7", "acme", "org")));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        List<RetrievalHit> hits = source(true).retrieve(request("张三", "org"));

        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(RetrievalHit::mergeKey).doesNotHaveDuplicates();
        assertThat(hits).allSatisfy(hit -> assertThat(hit.mergeKey()).isEqualTo(hit.id()));
    }

    @Test
    void disabledSourceStaysDisabledRegardlessOfProvenanceRequirement() {
        assertThat(new GraphRetrievalSource(searchService, 1.0, 5, false, true).enabled()).isFalse();
        assertThat(new GraphRetrievalSource(null, 1.0, 5, true, true).enabled()).isFalse();
        assertThat(source(true).enabled()).isTrue();
    }

    private GraphRetrievalSource source(boolean requireProvenance) {
        return new GraphRetrievalSource(searchService, 1.0, 5, true, requireProvenance);
    }

    private static RetrievalRequest request(String query, String category) {
        return new RetrievalRequest(query, List.of(query), "acme", category, 5, 0.0);
    }
}
