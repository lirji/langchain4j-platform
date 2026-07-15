package com.lrj.platform.knowledge.authz;

import com.lrj.authz.sdk.RemoteAuthzEngine;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.knowledge.DocumentMirror;
import com.lrj.platform.knowledge.DocumentSplitterFactory;
import com.lrj.platform.knowledge.KnowledgeEmbeddingConfig;
import com.lrj.platform.knowledge.KnowledgeQueryService;
import com.lrj.platform.knowledge.hybrid.KeywordSearchService;
import com.lrj.platform.knowledge.hybrid.SimpleKeywordTokenizer;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import com.lrj.platform.knowledge.lifecycle.DocumentService;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import com.lrj.platform.security.TenantContext;
import org.springframework.web.server.ResponseStatusException;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 知识库细粒度授权端到端集成测试 (Phase 2 试点核心验证)。
 * 需要 auth-platform-server(:8200) + SpiceDB 在跑 (灌好 knowledge.zed); 否则自动跳过。
 * 场景: alice 传私有文档 -> alice(owner) 可见、同租户 bob 不可见 -> 授权 bob -> bob 可见。
 */
class KnowledgeAuthzIntegrationTest {

    private static final String SERVER_URL = System.getProperty("authz.server.url", "http://localhost:8200");
    private static final String TENANT = "itg";

    private final EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
    private final EmbeddingModel model = new KnowledgeEmbeddingConfig.HashEmbeddingModel();
    private final DocumentMirror mirror = new DocumentMirror();
    private final DocumentRegistry registry = new InMemoryDocumentRegistry();
    private final RealKnowledgeAuthz authz = new RealKnowledgeAuthz(new RemoteAuthzEngine(SERVER_URL));
    private final DocumentService documents = new DocumentService(
            store, model, mirror, splitterFactory(), registry, mock(AuditLogger.class));
    private final KeywordSearchService keywordSearch = new KeywordSearchService(mirror, new SimpleKeywordTokenizer());
    private final KnowledgeQueryService queries = new KnowledgeQueryService(
            store, model, keywordSearch, 5, 0.0, true, 5);

    @BeforeEach
    void wireAndRequireServer() {
        Assumptions.assumeTrue(serverUp(), "auth-platform-server(" + SERVER_URL + ") 未启动，跳过集成测试");
        documents.setKnowledgeAuthz(authz);
        queries.setKnowledgeAuthz(authz);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void perUserVisibility_ownerSees_othersDont_untilGranted() {
        // 1) alice 上传私有文档（触发双写 owner + parent_space）
        TenantContext.set(new TenantContext.Tenant(TENANT, "alice", Set.of("ingest")));
        DocumentInfo info = documents.upload("secret.md", "text/markdown", "top secret zulu marker", "manual");
        String docId = info.docId();

        // 2) alice 是 owner → 查得到
        TenantContext.set(new TenantContext.Tenant(TENANT, "alice", Set.of("chat")));
        assertThat(queries.query("zulu", 10, 0.0, null).hits())
                .as("owner alice 应能看到自己的文档")
                .anySatisfy(h -> assertThat(h.docId()).isEqualTo(docId));

        // 3) 同租户 bob 未被授权 → 查不到（这正是 tenant 隔离之上新增的“行内/逐文档”粒度）
        TenantContext.set(new TenantContext.Tenant(TENANT, "bob", Set.of("chat")));
        assertThat(queries.query("zulu", 10, 0.0, null).hits())
                .as("未授权的同租户 bob 不应看到")
                .noneSatisfy(h -> assertThat(h.docId()).isEqualTo(docId));

        // 4) 把文档分享给 bob（写 viewer 并更新水位）→ bob 立即可见（AT_LEAST_AS_FRESH）
        authz.grantDocumentViewer(TENANT, docId, "bob");
        assertThat(queries.query("zulu", 10, 0.0, null).hits())
                .as("授权后 bob 应立即看到")
                .anySatisfy(h -> assertThat(h.docId()).isEqualTo(docId));

        // 5) 撤销 → bob 又看不到
        authz.revokeDocumentViewer(TENANT, docId, "bob");
        assertThat(queries.query("zulu", 10, 0.0, null).hits())
                .as("撤销后 bob 立即失去可见")
                .noneSatisfy(h -> assertThat(h.docId()).isEqualTo(docId));

        // 清理关系元组
        TenantContext.set(new TenantContext.Tenant(TENANT, "alice", Set.of("ingest")));
        documents.delete(docId);
    }

    @Test
    void documentApiAuthz_getAndDelete_enforced() {
        // alice 上传（写 owner + parent_space）
        TenantContext.set(new TenantContext.Tenant(TENANT, "alice", Set.of("ingest")));
        DocumentInfo info = documents.upload("gamma.md", "text/markdown", "gamma delta echo", "manual");
        String docId = info.docId();

        // alice(owner) 能 get
        TenantContext.set(new TenantContext.Tenant(TENANT, "alice", Set.of("chat")));
        assertThat(documents.get(docId)).as("owner alice 应能 get 自己的文档").isPresent();

        // 未授权同租户 bob：get 不到（enforce → empty → 上层 404）、删不掉（enforce → false）
        TenantContext.set(new TenantContext.Tenant(TENANT, "bob", Set.of("chat")));
        assertThat(documents.get(docId)).as("未授权 bob 不应 get 到").isEmpty();
        assertThat(documents.delete(docId)).as("未授权 bob 不应能删").isFalse();

        // owner alice 能删自己的
        TenantContext.set(new TenantContext.Tenant(TENANT, "alice", Set.of("ingest")));
        assertThat(documents.delete(docId)).as("owner alice 应能删").isTrue();
    }

    @Test
    void listAndOverwrite_enforced() {
        TenantContext.set(new TenantContext.Tenant(TENANT, "alice", Set.of("ingest")));
        String d1 = documents.upload("li1.md", "text/markdown", "list one alpha", "manual").docId();
        String d2 = documents.upload("li2.md", "text/markdown", "list two beta", "manual").docId();

        // alice(owner) 能 list 到自己的两篇
        TenantContext.set(new TenantContext.Tenant(TENANT, "alice", Set.of("chat")));
        assertThat(documents.list()).extracting(DocumentInfo::docId).contains(d1, d2);

        // 同租户 bob（未授权）list 不到（enforce 过滤，不泄露元数据）
        TenantContext.set(new TenantContext.Tenant(TENANT, "bob", Set.of("chat")));
        assertThat(documents.list()).as("未授权 bob list 不到 alice 的文档").isEmpty();

        // bob 有 ingest 但无 edit：覆盖 alice 同名文档被拒（403），防夺权
        TenantContext.set(new TenantContext.Tenant(TENANT, "bob", Set.of("ingest")));
        assertThatThrownBy(() -> documents.upload("li1.md", "text/markdown", "bob overwrite", "manual"))
                .as("bob 无 edit 不能覆盖 alice 同名文档").isInstanceOf(ResponseStatusException.class);

        // 清理
        TenantContext.set(new TenantContext.Tenant(TENANT, "alice", Set.of("ingest")));
        documents.delete(d1);
        documents.delete(d2);
    }

    private static boolean serverUp() {
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(SERVER_URL + "/actuator/health"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static DocumentSplitterFactory splitterFactory() {
        return new DocumentSplitterFactory(
                "recursive", "chars", 80, 0, 0, "gpt-4o-mini", "recursive",
                300, 0, 1, 95, 200, 0, new KnowledgeEmbeddingConfig.HashEmbeddingModel());
    }
}
