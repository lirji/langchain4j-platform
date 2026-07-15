package com.lrj.platform.knowledge;

import com.lrj.platform.knowledge.authz.AuthzMode;
import com.lrj.platform.knowledge.authz.KnowledgeAuthz;
import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.hybrid.SimpleKeywordTokenizer;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KnowledgeQueryService 融合后读授权过滤的单测（融合后、重排前）。覆盖 Phase 2 完成标准：
 * 一文档多 chunk 只产生一个 checkBulk 资源（去重）、enforce 丢弃判否与无 docId 命中、
 * disabled 不调用授权、shadow 不拦截。
 */
class KnowledgeQueryServiceAuthzTest {

    private final EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
    private final EmbeddingModel model = new KnowledgeEmbeddingConfig.HashEmbeddingModel();
    private final KeywordSearchService keywordSearch =
            new KeywordSearchService(new DocumentMirror(), new SimpleKeywordTokenizer());
    private final KnowledgeQueryService queries =
            new KnowledgeQueryService(store, model, keywordSearch, 5, 0.0, true, 5);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void enforce_dedupsMultiChunkSameDoc_andFiltersToReadable() {
        RecordingAuthz authz = new RecordingAuthz(AuthzMode.ENFORCE, Set.of("d1"));
        queries.setKnowledgeAuthz(authz);
        // 3 条命中：d1 的两个 chunk（不同 mergeKey，融合不合并）+ d2 一个 chunk。
        queries.setExtraSources(List.of(sourceOf(hit("d1", "0"), hit("d1", "1"), hit("d2", "0"))));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        var result = queries.query("q", 10, 0.0, null);

        // 去重：3 条命中 → 只对 {d1, d2} 两个 docId 判权（多 chunk 同 doc 合成一个资源）。
        assertThat(authz.lastDocIds).containsExactlyInAnyOrder("d1", "d2");
        assertThat(authz.filterCalls).isEqualTo(1);
        // enforce：d2 判否被丢弃，只剩 d1 的命中。
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits()).allSatisfy(h -> assertThat(h.docId()).isEqualTo("d1"));
    }

    @Test
    void enforce_dropsHitsWithoutDocId() {
        RecordingAuthz authz = new RecordingAuthz(AuthzMode.ENFORCE, Set.of("d1"));
        queries.setKnowledgeAuthz(authz);
        queries.setExtraSources(List.of(sourceOf(hit("d1", "0"), graphHitNoDocId())));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        var result = queries.query("q", 10, 0.0, null);

        // 无 docId 的命中（图谱三元组）无法资源级判权 → enforce fail-closed 丢弃。
        assertThat(result.hits()).allSatisfy(h -> assertThat(h.docId()).isEqualTo("d1"));
    }

    @Test
    void disabled_doesNotInvokeAuthz() {
        RecordingAuthz authz = new RecordingAuthz(AuthzMode.DISABLED, Set.of());
        queries.setKnowledgeAuthz(authz);
        queries.setExtraSources(List.of(sourceOf(hit("d1", "0"), hit("d2", "0"))));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        var result = queries.query("q", 10, 0.0, null);

        assertThat(authz.filterCalls).as("disabled 不调用授权/AP").isZero();
        assertThat(result.hits()).extracting(KnowledgeQueryService.Hit::docId)
                .containsExactlyInAnyOrder("d1", "d2");
    }

    @Test
    void shadow_invokesAuthzButDoesNotFilter() {
        RecordingAuthz authz = new RecordingAuthz(AuthzMode.SHADOW, Set.of("d1"));
        queries.setKnowledgeAuthz(authz);
        queries.setExtraSources(List.of(sourceOf(hit("d1", "0"), hit("d2", "0"))));
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));

        var result = queries.query("q", 10, 0.0, null);

        assertThat(authz.filterCalls).as("shadow 触发判权观测").isEqualTo(1);
        assertThat(result.hits()).as("shadow 不拦截，返回全集")
                .extracting(KnowledgeQueryService.Hit::docId).containsExactlyInAnyOrder("d1", "d2");
    }

    // —— fakes ——

    private static RetrievalSource sourceOf(RetrievalHit... hits) {
        return new RetrievalSource() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public List<RetrievalHit> retrieve(RetrievalRequest request) {
                return List.of(hits);
            }
        };
    }

    private static RetrievalHit hit(String docId, String index) {
        return new RetrievalHit("es:" + docId + "#" + index, docId + "#" + index, 0.9,
                docId, docId + ".md", "manual", index, "text-" + docId, "es", false);
    }

    private static RetrievalHit graphHitNoDocId() {
        return new RetrievalHit("graph:t1", "graph:t1", 0.8,
                null, null, null, null, "triple", "graph", false);
    }

    /** 记录传入 docIds 的 fake：enforce 返回配置的可读子集，shadow 返回全集，disabled 不会被调用。 */
    private static final class RecordingAuthz implements KnowledgeAuthz {
        private final AuthzMode mode;
        private final Set<String> readable;
        private Set<String> lastDocIds;
        private int filterCalls;

        RecordingAuthz(AuthzMode mode, Set<String> readable) {
            this.mode = mode;
            this.readable = readable;
        }

        @Override
        public AuthzMode mode() {
            return mode;
        }

        @Override
        public Set<String> filterReadable(String tenantId, String userId, Set<String> docIds) {
            filterCalls++;
            lastDocIds = new LinkedHashSet<>(docIds);
            return mode == AuthzMode.ENFORCE ? readable : docIds;
        }

        @Override
        public void onDocumentCreated(String tenantId, String docId, String ownerUserId) {
        }

        @Override
        public void onDocumentDeleted(String tenantId, String docId) {
        }

        @Override
        public void grantDocumentViewer(String tenantId, String docId, String userId) {
        }

        @Override
        public void revokeDocumentViewer(String tenantId, String docId, String userId) {
        }

        @Override
        public boolean checkDocument(String tenantId, String userId, String docId, String permission) {
            return true;
        }
    }
}
