package com.lrj.platform.knowledge;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.hybrid.SimpleKeywordTokenizer;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import com.lrj.platform.knowledge.lifecycle.DocumentService;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import com.lrj.platform.knowledge.search.FusionStrategy;
import com.lrj.platform.knowledge.search.RetrievalHit;
import com.lrj.platform.knowledge.search.RetrievalRequest;
import com.lrj.platform.knowledge.search.RetrievalSource;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ES 额外源并入编排的集成测试（es-hybrid-rerank 阶段4）。用 fake RetrievalSource 模拟 ES，
 * 验证额外源真的进入融合、两种策略端到端可用。同 chunk 的 hybrid 合并语义已在 HybridFusionServiceTest 单测覆盖。
 */
class KnowledgeQueryServiceEsFusionTest {

    private final EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
    private final EmbeddingModel model = new KnowledgeEmbeddingConfig.HashEmbeddingModel();
    private final DocumentMirror mirror = new DocumentMirror();
    private final DocumentRegistry registry = new InMemoryDocumentRegistry();
    private final DocumentService documents = new DocumentService(
            store, model, mirror, splitterFactory(), registry, mock(AuditLogger.class));
    private final KeywordSearchService keywordSearch = new KeywordSearchService(mirror, new SimpleKeywordTokenizer());
    private final KnowledgeQueryService queries = new KnowledgeQueryService(
            store, model, keywordSearch, 5, 0.0, true, 5);

    /** 模拟 ES 源，固定返回一条 esdoc 命中。 */
    private static RetrievalSource esFake() {
        return new RetrievalSource() {
            @Override
            public String name() {
                return "es";
            }

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public List<RetrievalHit> retrieve(RetrievalRequest request) {
                return List.of(new RetrievalHit("es:esdoc#0", "esdoc#0", 0.99,
                        "esdoc", "es-only.md", "manual", "0", "退款政策 phoenix", "es", false));
            }
        };
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void esExtraSource_participatesInFusion_weightedMax() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        var info = documents.upload("manual.md", "text/markdown", "hybrid keyword phoenix marker", "manual");

        queries.setExtraSources(List.of(esFake()));
        queries.setFusionStrategy(FusionStrategy.WEIGHTED_MAX);

        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        var result = queries.query("phoenix", 10, 0.0, null);

        // ES 源命中被融合进来
        assertThat(result.hits()).anySatisfy(h -> {
            assertThat(h.docId()).isEqualTo("esdoc");
            assertThat(h.source()).isEqualTo("es");
        });
        // 本地向量/关键词命中仍在
        assertThat(result.hits()).anySatisfy(h -> assertThat(h.docId()).isEqualTo(info.docId()));
    }

    @Test
    void esExtraSource_participatesInFusion_rrf() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        documents.upload("manual.md", "text/markdown", "hybrid keyword phoenix marker", "manual");

        queries.setExtraSources(List.of(esFake()));
        queries.setFusionStrategy(FusionStrategy.RRF);

        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        var result = queries.query("phoenix", 10, 0.0, null);

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits()).anySatisfy(h -> assertThat(h.docId()).isEqualTo("esdoc"));
    }

    private static DocumentSplitterFactory splitterFactory() {
        return new DocumentSplitterFactory(
                "recursive", "chars", 80, 0, 0, "gpt-4o-mini", "recursive",
                300, 0, 1, 95, 200, 0, new KnowledgeEmbeddingConfig.HashEmbeddingModel());
    }
}
