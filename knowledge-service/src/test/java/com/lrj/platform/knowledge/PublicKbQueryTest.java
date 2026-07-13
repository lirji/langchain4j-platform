package com.lrj.platform.knowledge;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.hybrid.SimpleKeywordTokenizer;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import com.lrj.platform.knowledge.lifecycle.DocumentService;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 公共/共享知识库：写入受 public-ingest 控制（在 controller 层），查询时并入公共分区且不破坏隔离。
 * 用 SingleEmbeddingStoreRouter（单 store + metadata 过滤）的测试构造器，隔离由 tenantId 过滤保证。
 */
class PublicKbQueryTest {

    private final EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
    private final EmbeddingModel model = new KnowledgeEmbeddingConfig.HashEmbeddingModel();
    private final DocumentMirror mirror = new DocumentMirror();
    private final DocumentRegistry registry = new InMemoryDocumentRegistry();
    private final DocumentService documents = new DocumentService(
            store, model, mirror, splitterFactory(), registry, mock(AuditLogger.class));
    private final KeywordSearchService keywordSearch = new KeywordSearchService(mirror, new SimpleKeywordTokenizer());
    private final KnowledgeQueryService queries = new KnowledgeQueryService(
            store, model, keywordSearch, 5, 0.0, true, 5);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** 灌一份公共文档 + 各租户私有文档。公共写用 shared=true（tenantId=__public__，与 TenantContext 无关）。 */
    private String seedPublicAndPrivate() {
        // 公共库（shared=true → __public__）
        documents.upload("refund.md", "text/markdown", "refund policy shared marker", "manual", -1, true);
        // acme 私有（含唯一词 yankee）
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        var acme = documents.upload("acme.md", "text/markdown", "acme private yankee", "manual");
        // globex 私有
        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("ingest")));
        documents.upload("globex.md", "text/markdown", "globex private note", "manual");
        return acme.docId();
    }

    @Test
    void publicDoc_isVisibleToAnyTenant_whenEnabled() {
        seedPublicAndPrivate();
        queries.setPublicKb(true, PublicKb.TENANT_ID);

        // globex 用户能查到公共库里的退款文档
        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("chat")));
        var result = queries.query("refund policy shared marker", 10, 0.0, null);
        assertThat(result.hits()).anySatisfy(h -> assertThat(h.displayName()).isEqualTo("refund.md"));

        // tenantA（从没灌过任何东西）也能查到公共文档
        TenantContext.set(new TenantContext.Tenant("tenantA", "carol", Set.of("chat")));
        var result2 = queries.query("refund policy shared marker", 10, 0.0, null);
        assertThat(result2.hits()).anySatisfy(h -> assertThat(h.displayName()).isEqualTo("refund.md"));
    }

    @Test
    void publicDoc_isInvisible_whenDisabled() {
        seedPublicAndPrivate();
        // 默认关闭：globex 查不到公共文档（向后兼容——与引入前一致）
        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("chat")));
        var result = queries.query("refund policy shared marker", 10, 0.0, null);
        assertThat(result.hits()).noneSatisfy(h -> assertThat(h.displayName()).isEqualTo("refund.md"));
    }

    @Test
    void tenantIsolation_isPreserved_evenWithPublicEnabled() {
        String acmeDocId = seedPublicAndPrivate();
        queries.setPublicKb(true, PublicKb.TENANT_ID);

        // globex 查 acme 的私有唯一词 → 绝不能命中 acme 的文档
        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("chat")));
        var result = queries.query("yankee", 10, 0.0, null);
        assertThat(result.hits()).noneSatisfy(h -> assertThat(h.docId()).isEqualTo(acmeDocId));
    }

    @Test
    void visibility_isPublicForSharedHit_andTenantForPrivateHit() {
        seedPublicAndPrivate();
        queries.setPublicKb(true, PublicKb.TENANT_ID);

        // acme 查自己的私有唯一词 → 命中标 tenant（shared=false）
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        var priv = queries.query("yankee", 10, 0.0, null);
        assertThat(priv.hits())
                .filteredOn(h -> "acme.md".equals(h.displayName()))
                .isNotEmpty()
                .allSatisfy(h -> assertThat(h.shared()).isFalse());

        // acme 查公共退款文档 → 命中标 public（shared=true）
        var pub = queries.query("refund policy shared marker", 10, 0.0, null);
        assertThat(pub.hits())
                .filteredOn(h -> "refund.md".equals(h.displayName()))
                .isNotEmpty()
                .allSatisfy(h -> assertThat(h.shared()).isTrue());
    }

    @Test
    void sharedList_andDelete_targetPublicPartition() {
        documents.upload("refund.md", "text/markdown", "refund policy shared marker", "manual", -1, true);

        // 共享分区列出该文档（tenantId=__public__）
        assertThat(documents.list(true))
                .extracting(DocumentInfo::displayName).contains("refund.md");
        assertThat(documents.list(true))
                .allSatisfy(d -> assertThat(d.tenantId()).isEqualTo(PublicKb.TENANT_ID));

        // 从没灌过东西的新租户，其私有列表看不到共享文档
        TenantContext.set(new TenantContext.Tenant("tenantA", "carol", Set.of("chat")));
        assertThat(documents.list(false)).isEmpty();

        // 共享删除定位到 __public__ 分区
        String docId = documents.list(true).get(0).docId();
        assertThat(documents.delete(docId, true)).isTrue();
        assertThat(documents.list(true)).isEmpty();
    }

    @Test
    void categoryFilter_stillApplies_onPublicMerge() {
        documents.upload("refund.md", "text/markdown", "refund policy shared marker", "客服", -1, true);
        queries.setPublicKb(true, PublicKb.TENANT_ID);

        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("chat")));
        // 用不匹配的 category 过滤 → 公共文档也被过滤掉
        var miss = queries.query("refund policy shared marker", 10, 0.0, "faq");
        assertThat(miss.hits()).noneSatisfy(h -> assertThat(h.displayName()).isEqualTo("refund.md"));
        // 匹配 category → 命中
        var hit = queries.query("refund policy shared marker", 10, 0.0, "客服");
        assertThat(hit.hits()).anySatisfy(h -> assertThat(h.displayName()).isEqualTo("refund.md"));
    }

    private static DocumentSplitterFactory splitterFactory() {
        return new DocumentSplitterFactory(
                "recursive", "chars", 80, 0, 0, "gpt-4o-mini", "recursive",
                300, 0, 1, 95, 200, 0, new KnowledgeEmbeddingConfig.HashEmbeddingModel());
    }
}
