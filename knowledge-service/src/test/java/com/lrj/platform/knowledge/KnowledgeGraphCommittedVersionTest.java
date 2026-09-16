package com.lrj.platform.knowledge;

import com.lrj.platform.knowledge.graph.GraphSearchService;
import com.lrj.platform.knowledge.graph.InMemoryGraphStore;
import com.lrj.platform.knowledge.graph.TokenEntityLinker;
import com.lrj.platform.knowledge.graph.Triple;
import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.hybrid.SimpleKeywordTokenizer;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 图命中带上版本 provenance 之后，必须和向量/ES 命中一样以 Registry 当前版本为权威：旧版本三元组
 * 在文档升版后不再可见。这是 query 角色能开图检索的前提——此前图命中无版本，只能整体禁用。
 */
class KnowledgeGraphCommittedVersionTest {

    private final InMemoryGraphStore graphStore = new InMemoryGraphStore();
    private final InMemoryDocumentRegistry registry = new InMemoryDocumentRegistry();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void staleGraphTriplesDisappearOnceRegistryCommitsANewerVersion() {
        graphStore.add(List.of(
                new Triple("张三", "隶属于", "研发部", "doc/v1/people.md#0", "acme", null),
                new Triple("张三", "隶属于", "平台组", "doc/v2/people.md#0", "acme", null)));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        try (var queries = graphOnlyQueryService()) {
            registry.commitVersion(info(1));
            assertThat(queries.query("张三", 5, 0.0, null).hits())
                    .extracting(KnowledgeQueryService.Hit::text)
                    .containsExactly("张三 --隶属于-> 研发部");

            registry.commitVersion(info(2));
            assertThat(queries.query("张三", 5, 0.0, null).hits())
                    .extracting(KnowledgeQueryService.Hit::text)
                    .containsExactly("张三 --隶属于-> 平台组");
        }
    }

    @Test
    void unregisteredDocumentGraphTriplesAreNotServed() {
        // 文档已从 Registry 删除（或从未提交）时，其派生三元组不能继续可见——GC 之前也不行
        graphStore.add(List.of(
                new Triple("张三", "隶属于", "研发部", "doc/v1/people.md#0", "acme", null)));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        try (var queries = graphOnlyQueryService()) {
            assertThat(queries.query("张三", 5, 0.0, null).hits()).isEmpty();
        }
    }

    @Test
    void legacyTriplesWithoutProvenanceAreDroppedUnderRequireProvenance() {
        graphStore.add(List.of(
                new Triple("张三", "隶属于", "研发部", "people.md#0", "acme", null)));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        try (var queries = graphOnlyQueryService()) {
            registry.commitVersion(info(1));
            assertThat(queries.query("张三", 5, 0.0, null).hits()).isEmpty();
        }
    }

    /** 只启用图源（向量库为空、关键词 mock、hybrid 关）的检索编排器，require-provenance 打开。 */
    private KnowledgeQueryService graphOnlyQueryService() {
        var model = new KnowledgeEmbeddingConfig.HashEmbeddingModel();
        var graphSearch = new GraphSearchService(
                graphStore, new TokenEntityLinker(graphStore, new SimpleKeywordTokenizer()), 2, 20);
        var queries = new KnowledgeQueryService(
                new com.lrj.platform.knowledge.store.SingleEmbeddingStoreRouter(
                        new InMemoryEmbeddingStore<TextSegment>(), model.dimension()),
                model,
                mock(KeywordSearchService.class),
                5,
                0.0,
                false,
                5,
                graphSearch,
                true,
                20,
                1.0,
                1.0,
                1.0,
                true);
        queries.setDocumentRegistry(registry);
        return queries;
    }

    private DocumentInfo info(int version) {
        return new DocumentInfo("doc", "acme", "people.md", "text/markdown", 5, 1, version, Instant.now(), null);
    }
}
