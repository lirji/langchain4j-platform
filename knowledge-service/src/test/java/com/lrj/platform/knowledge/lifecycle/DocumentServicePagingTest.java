package com.lrj.platform.knowledge.lifecycle;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.knowledge.DocumentMirror;
import com.lrj.platform.knowledge.DocumentSplitterFactory;
import com.lrj.platform.knowledge.PublicKb;
import com.lrj.platform.knowledge.authz.AuthzMode;
import com.lrj.platform.knowledge.authz.KnowledgeAuthz;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文档列表分页（{@link DocumentService#listPaged}）与稳定排序（{@link DocumentService#list(boolean)}）：
 * 固定时间戳锁定「uploadedAt 降序 + docId 升序 tiebreak + nullsLast」，切片/越界 clamp/size 归一化，
 * 授权 DISABLED/SHADOW/ENFORCE 三态与分页 envelope 的联动，共享库对文档级 ReBAC 的 bypass，跨租户独立分页。
 *
 * <p>以下疑似交互逻辑问题（详见 docs/tests/doc-lifecycle-0717-1617/03-suspected-issues.md）当前<strong>只上报、不写成通过型断言</strong>：
 * <ul>
 *   <li>TODO(issue-01): failedEnforceCreation_doesNotChangeRegistryOrPagedTotal —— enforce 新建失败前 registry/sink 已写入，
 *       留下幽灵文档并被 listPaged.total 计入；修复 upload 校验前置/补偿后再补此回归。</li>
 *   <li>TODO(issue-02): 并发翻页快照/cursor 语义未定 —— offset 分页在两页请求间发生增删会重/漏；先定契约再测，不锁当前行为。</li>
 *   <li>TODO(issue-03): enforce 授权依赖故障当前呈现为 200 空信封（total=0）；返回 503/降级标记还是空信封待定，暂不断言。</li>
 * </ul>
 */
class DocumentServicePagingTest {

    private static final Instant OLD = Instant.parse("2026-07-17T08:00:00Z");
    private static final Instant SAME = Instant.parse("2026-07-17T09:00:00Z");
    private static final Instant NEWEST = Instant.parse("2026-07-17T10:00:00Z");

    @BeforeEach
    void setTenant() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void sortsByUploadedAtDescendingThenDocIdAscendingWithNullsLast() {
        DocumentRegistry registry = mock(DocumentRegistry.class);
        when(registry.list("acme")).thenReturn(List.of(
                info("acme", "null-z", null),
                info("acme", "old", OLD),
                info("acme", "tie-z", SAME),
                info("acme", "null-a", null),
                info("acme", "newest", NEWEST),
                info("acme", "tie-a", SAME)));
        DocumentService service = service(registry, new StubKnowledgeAuthz(AuthzMode.DISABLED, Set.of()));

        assertThat(service.list(false))
                .extracting(DocumentInfo::docId)
                .containsExactly("newest", "tie-a", "tie-z", "old", "null-a", "null-z");
    }

    @Test
    void staticSnapshot_pagesAreExactOrderedPartitionWithoutDuplicatesOrGaps() {
        InMemoryDocumentRegistry registry = new InMemoryDocumentRegistry();
        List<String> expected = IntStream.range(0, 20)
                .mapToObj(DocumentServicePagingTest::id)
                .toList();
        for (int i = 19; i >= 0; i--) {
            registry.put(info("acme", id(i), SAME));
        }
        DocumentService service = service(registry, new StubKnowledgeAuthz(AuthzMode.DISABLED, Set.of()));

        List<String> collected = new ArrayList<>();
        for (int requestedPage = 1; requestedPage <= 4; requestedPage++) {
            PagedDocuments page = service.listPaged(false, requestedPage, 5);
            assertThat(page.page()).isEqualTo(requestedPage);
            assertThat(page.size()).isEqualTo(5);
            assertThat(page.total()).isEqualTo(20);
            assertThat(page.totalPages()).isEqualTo(4);
            assertThat(page.items()).hasSize(5);
            page.items().forEach(item -> collected.add(item.docId()));
        }

        assertThat(collected).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    @Test
    void sizeAndPageIntegerBoundaries_keepEnvelopeInternallyConsistent() {
        InMemoryDocumentRegistry registry = registry("acme", 101);
        DocumentService service = service(registry, new StubKnowledgeAuthz(AuthzMode.DISABLED, Set.of()));

        PagedDocuments negativeSize = service.listPaged(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
        assertThat(negativeSize.page()).isEqualTo(1);
        assertThat(negativeSize.size()).isEqualTo(DocumentService.DEFAULT_PAGE_SIZE);
        assertThat(negativeSize.total()).isEqualTo(101);
        assertThat(negativeSize.totalPages()).isEqualTo(11);
        assertThat(negativeSize.items()).extracting(DocumentInfo::docId)
                .containsExactlyElementsOf(IntStream.range(0, 10).mapToObj(DocumentServicePagingTest::id).toList());

        assertThat(service.listPaged(false, 1, 0).size()).isEqualTo(DocumentService.DEFAULT_PAGE_SIZE);

        PagedDocuments exactMax = service.listPaged(false, Integer.MAX_VALUE, 100);
        assertThat(exactMax.page()).isEqualTo(2);
        assertThat(exactMax.size()).isEqualTo(DocumentService.MAX_PAGE_SIZE);
        assertThat(exactMax.total()).isEqualTo(101);
        assertThat(exactMax.totalPages()).isEqualTo(2);
        assertThat(exactMax.items()).extracting(DocumentInfo::docId).containsExactly(id(100));

        PagedDocuments aboveMax = service.listPaged(false, Integer.MAX_VALUE, 101);
        assertThat(aboveMax.page()).isEqualTo(2);
        assertThat(aboveMax.size()).isEqualTo(DocumentService.MAX_PAGE_SIZE);
        assertThat(aboveMax.totalPages()).isEqualTo(2);
        assertThat(aboveMax.items()).extracting(DocumentInfo::docId).containsExactly(id(100));
    }

    @Test
    void emptyResult_clampsPageAndSkipsAuthzBulkCheck() {
        StubKnowledgeAuthz authz = new StubKnowledgeAuthz(AuthzMode.ENFORCE, Set.of());
        DocumentService service = service(new InMemoryDocumentRegistry(), authz);

        PagedDocuments page = service.listPaged(false, Integer.MAX_VALUE, 0);

        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(DocumentService.DEFAULT_PAGE_SIZE);
        assertThat(page.total()).isZero();
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(authz.filterCalls).isZero();
    }

    @Test
    void enforceAuthz_filtersBeforeTotalsPageClampAndSlice() {
        InMemoryDocumentRegistry registry = registry("acme", 6);
        StubKnowledgeAuthz authz = new StubKnowledgeAuthz(
                AuthzMode.ENFORCE, new LinkedHashSet<>(List.of(id(0), id(2), id(5))));
        DocumentService service = service(registry, authz);

        PagedDocuments page = service.listPaged(false, 99, 2);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(DocumentInfo::docId).containsExactly(id(5));
        assertThat(authz.filterCalls).isEqualTo(1);
        assertThat(authz.lastTenantId).isEqualTo("acme");
        assertThat(authz.lastUserId).isEqualTo("alice");
        assertThat(authz.lastCandidates)
                .containsExactlyInAnyOrderElementsOf(IntStream.range(0, 6)
                        .mapToObj(DocumentServicePagingTest::id).toList());
    }

    @Test
    void shadowAuthz_observesAllCandidatesButDoesNotReduceTotalOrItems() {
        InMemoryDocumentRegistry registry = registry("acme", 4);
        StubKnowledgeAuthz authz = new StubKnowledgeAuthz(AuthzMode.SHADOW, Set.of(id(0)));
        DocumentService service = service(registry, authz);

        PagedDocuments page = service.listPaged(false, 2, 2);

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(DocumentInfo::docId).containsExactly(id(2), id(3));
        assertThat(authz.filterCalls).isEqualTo(1);
        assertThat(authz.lastCandidates).hasSize(4);
    }

    @Test
    void disabledAuthz_skipsFilteringAndKeepsVisibleTotal() {
        InMemoryDocumentRegistry registry = registry("acme", 3);
        StubKnowledgeAuthz authz = new StubKnowledgeAuthz(AuthzMode.DISABLED, Set.of());
        DocumentService service = service(registry, authz);

        PagedDocuments page = service.listPaged(false, 1, 2);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(DocumentInfo::docId).containsExactly(id(0), id(1));
        assertThat(authz.filterCalls).isZero();
    }

    @Test
    void sharedLibrary_bypassesTenantDocumentAuthz() {
        InMemoryDocumentRegistry registry = new InMemoryDocumentRegistry();
        registry.put(info(PublicKb.TENANT_ID, "public-a", SAME));
        registry.put(info(PublicKb.TENANT_ID, "public-b", SAME));
        registry.put(info("acme", "tenant-secret", SAME));
        StubKnowledgeAuthz authz = new StubKnowledgeAuthz(AuthzMode.ENFORCE, Set.of());
        DocumentService service = service(registry, authz);

        PagedDocuments page = service.listPaged(true, 1, 1);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(DocumentInfo::docId).containsExactly("public-a");
        assertThat(page.items()).allSatisfy(item ->
                assertThat(item.tenantId()).isEqualTo(PublicKb.TENANT_ID));
        assertThat(authz.filterCalls).isZero();
    }

    @Test
    void tenantAndSharedPartitions_haveIndependentTotalsAndSlices() {
        InMemoryDocumentRegistry registry = new InMemoryDocumentRegistry();
        put(registry, "acme", "acme-a", "acme-b", "acme-c");
        put(registry, "beta", "beta-a", "beta-b");
        put(registry, PublicKb.TENANT_ID, "public-a", "public-b", "public-c", "public-d");
        DocumentService service = service(registry, new StubKnowledgeAuthz(AuthzMode.DISABLED, Set.of()));

        PagedDocuments acme = service.listPaged(false, 2, 2);
        assertThat(acme.total()).isEqualTo(3);
        assertThat(acme.items()).extracting(DocumentInfo::docId).containsExactly("acme-c");

        TenantContext.set(new TenantContext.Tenant("beta", "bob", Set.of("chat")));
        PagedDocuments beta = service.listPaged(false, 1, 2);
        assertThat(beta.total()).isEqualTo(2);
        assertThat(beta.items()).extracting(DocumentInfo::docId).containsExactly("beta-a", "beta-b");

        PagedDocuments shared = service.listPaged(true, 2, 2);
        assertThat(shared.total()).isEqualTo(4);
        assertThat(shared.items()).extracting(DocumentInfo::docId).containsExactly("public-c", "public-d");
    }

    private static DocumentService service(DocumentRegistry registry, KnowledgeAuthz authz) {
        DocumentService service = new DocumentService(
                mock(EmbeddingStoreRouter.class),
                mock(EmbeddingModel.class),
                mock(DocumentMirror.class),
                mock(DocumentSplitterFactory.class),
                registry,
                mock(AuditLogger.class),
                null,
                null);
        service.setKnowledgeAuthz(authz);
        return service;
    }

    private static InMemoryDocumentRegistry registry(String tenantId, int count) {
        InMemoryDocumentRegistry registry = new InMemoryDocumentRegistry();
        for (int i = count - 1; i >= 0; i--) {
            registry.put(info(tenantId, id(i), SAME));
        }
        return registry;
    }

    private static void put(InMemoryDocumentRegistry registry, String tenantId, String... docIds) {
        for (String docId : docIds) {
            registry.put(info(tenantId, docId, SAME));
        }
    }

    private static String id(int value) {
        return String.format("doc-%03d", value);
    }

    private static DocumentInfo info(String tenantId, String docId, Instant uploadedAt) {
        return new DocumentInfo(
                docId,
                tenantId,
                docId + ".md",
                "text/markdown",
                10L,
                1,
                1,
                uploadedAt,
                "manual");
    }

    private static final class StubKnowledgeAuthz implements KnowledgeAuthz {

        private final AuthzMode mode;
        private final Set<String> readable;
        private int filterCalls;
        private String lastTenantId;
        private String lastUserId;
        private Set<String> lastCandidates = Set.of();

        private StubKnowledgeAuthz(AuthzMode mode, Set<String> readable) {
            this.mode = mode;
            this.readable = new LinkedHashSet<>(readable);
        }

        @Override
        public AuthzMode mode() {
            return mode;
        }

        @Override
        public void onDocumentCreated(String tenantId, String docId, String ownerUserId, String departmentId) {
            // no-op: listing tests do not exercise writes
        }

        @Override
        public void onDocumentDeleted(String tenantId, String docId) {
            // no-op
        }

        @Override
        public void grantDocumentViewer(String tenantId, String docId, String userId) {
            // no-op
        }

        @Override
        public void revokeDocumentViewer(String tenantId, String docId, String userId) {
            // no-op
        }

        @Override
        public boolean checkDocument(String tenantId, String userId, String docId, String permission) {
            return true;
        }

        @Override
        public Set<String> filterReadable(String tenantId, String userId, Set<String> docIds) {
            filterCalls++;
            lastTenantId = tenantId;
            lastUserId = userId;
            lastCandidates = new LinkedHashSet<>(docIds);
            return new LinkedHashSet<>(readable);
        }
    }
}
