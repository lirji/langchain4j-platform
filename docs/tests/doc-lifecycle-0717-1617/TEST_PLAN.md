# TEST PLAN：Document Lifecycle 分页、授权与模型工厂收口

## 1. 被测面与范围

本计划面向 `knowledge-service` 当前工作树中的未提交改动，只提供可落地的测试蓝图，不修改业务代码。

受影响生产面：

- `com.lrj.platform.knowledge.lifecycle.DocumentService`
  - `list()`
  - `list(boolean shared)`
  - `listPaged(boolean shared, int page, int size)`
  - 内部授权过滤 `readable(...)` 与稳定排序 `sorted(...)`
- `com.lrj.platform.knowledge.lifecycle.PagedDocuments`
- `com.lrj.platform.knowledge.controller.DocumentController`
  - `list(String visibility)`
  - `listPaged(String visibility, int page, Integer size)`
- `com.lrj.platform.knowledge.KnowledgeChatModelConfig`
  - `knowledgeChatModel(GatewayChatModelFactory factory, String modelName)`

详细文件、实际签名、现有测试与命令见 [01-scope.md](01-scope.md)。完整逐分支矩阵见 [02-coverage-matrix.md](02-coverage-matrix.md)。

## 2. 六视角综合结论

### 2.1 test-strategist：策略与分层

主风险不在 Spring 容器，而在纯函数式排序/切片和 `DocumentService → DocumentRegistry → KnowledgeAuthz` 的交互次序。因此采用四层快速、确定性的 POJO 测试：

1. **Service 单元 + 边界 + 租户/安全测试**：手动构造 `DocumentService`，注入手动 Mockito mock 或内存 registry，以及测试内确定性 `KnowledgeAuthz`。
2. **Controller 委派 + 映射结构回归**：已有 `DocumentControllerPublicTest` 继续验证非空/空 size 委派；新测试用反射锁定 `params="page"`，不启动 MockMvc。
3. **record JSON 契约测试**：普通 `ObjectMapper` 检查信封字段和值，不启动 Spring。
4. **配置工厂测试**：手动 mock `GatewayChatModelFactory`，验证唯一工厂调用、返回身份和注解结构。

不新增 H2、Redis、Testcontainers、网络 IT 或 Spring context。分页算法在 registry 数据取回之后运行，用 Redis 集成测试不能比确定性 service 测试更有效地锁定本次行为。

### 2.2 验收标准

必须同时满足：

1. `list(false)` 只读取当前 `TenantContext.tenantId()` 分区；`list(true)` 只读取 `PublicKb.TENANT_ID`。
2. 返回顺序严格为 `uploadedAt` 降序；相同时间严格按 `docId` 升序；null 时间严格置尾，null 组仍按 docId 升序。
3. 分页必须在授权过滤与排序之后切片；ENFORCE 的 `total` 是允许子集大小，SHADOW/DISABLED 的 `total` 是分区全集大小。
4. 空结果固定 `items=[]、page=1、total=0、totalPages=1`，size 仍按规则规范化。
5. `page < 1` 到 1，`page > totalPages` 到末页；`Integer.MIN_VALUE/MAX_VALUE` 不溢出。
6. `size <= 0` 变 10，`size == 100` 保持 100，`size > 100` 变 100；`totalPages` 与信封中的规范化 size 使用同一计算口径。
7. 静态数据快照逐页拼接后与完整稳定排序结果完全同序、不重不漏。这里不宣称跨并发写入的快照一致性。
8. shared 分区即使 authz 为 ENFORCE 也不调用租户文档 bulk 判权，符合公共库普通用户可读合同。
9. Controller 只有在请求含 `page` 参数时才具备分页 mapping；旧无 page mapping 保留空 params 条件和数组返回类型。
10. Controller 非空 size 原样传 service、null size 传 0：这两项由当前 `DocumentControllerPublicTest` 已有两例持续锁定。
11. `PagedDocuments` JSON 字段固定为 `items/page/size/total/totalPages`。
12. `KnowledgeChatModelConfig` 只调用一次 `factory.build(modelName, 0.0)`、返回同一对象，并且 bean 方法没有 `@ConditionalOnMissingBean`。

### 2.3 coverage-analyst：本轮要闭合的关键缺口

完整矩阵见 [02-coverage-matrix.md](02-coverage-matrix.md)。本次草案重点闭合：

- 同时间戳 `docId` tiebreak 和 `uploadedAt=null`。
- ENFORCE/SHADOW/DISABLED 三态与 `total/totalPages/page/items` 的联动。
- shared 在 ENFORCE 下的明确 bypass。
- tenant A、tenant B、public 三分区的独立 total 和切片。
- size 负数、0、100、101 与 int 极值 page 的信封一致性。
- Controller mapping 条件，而不重复已有的两个直接委派用例。
- record JSON 字段名。
- `@ConditionalOnMissingBean` 删除的结构性回归。

### 2.4 interaction-logic-reviewer：疑似问题摘要

完整证据、复现与建议见 [03-suspected-issues.md](03-suspected-issues.md)。

1. **ISSUE-01，高：失败上传留下已提交内容/registry。** 预期是 403/异常不改变分页数据源；现状是向量、镜像、ES/图谱和 `registry.put` 先发生，enforce department 校验和授权关系写入后发生。复现：无 department 的 ENFORCE 新建 `ghost.md` 返回 403，随后 registry 仍可见。建议前置可判定校验，并为授权写失败设计事务/补偿/outbox。修复前只写 `TODO(issue-01)`，不锁定幽灵状态。
2. **ISSUE-02，中：offset 分页没有跨请求快照。** 预期若产品承诺翻页期间不重不漏，应有 cursor/snapshot；现状每页重取、重排、offset 切片。复现：第一页 `[d3,d2]` 后插入更新的 `d4`，第二页变 `[d2,d1]`。建议明确“静态快照稳定”合同，或改 keyset/cursor。草案只断言静态数据集。
3. **ISSUE-03，中低：授权故障表现为空库。** 预期 fail-closed 同时可观测/可区分；现状 ENFORCE bulk 异常返回空集，最终是 HTTP 正常的 `total=0`，前端提示暂无文档。建议确认 503/降级标记或明确 200 空集合同；确认前不写正确行为断言。

### 2.5 edge-case-hunter：边缘场景

| 编号 | 触发方式 | 期望/处置 |
|---|---|---|
| E-01 | `page=0`、负数、`Integer.MIN_VALUE` | clamp 为 1，不抛算术异常 |
| E-02 | `page=Integer.MAX_VALUE` | clamp 末页，from/to 不溢出 |
| E-03 | `size=0`、负数、`Integer.MIN_VALUE` | 统一使用 10 |
| E-04 | `size=100`、101、`Integer.MAX_VALUE` | 100 保持，超过截断 100；页数同步使用 100 |
| E-05 | total=0 且请求高页 | `page=1,totalPages=1,items=[]`；不调用 authz bulk |
| E-06 | total 恰好整除 size / 有余数 | 分别无空尾页 / ceil 且末页为余数 |
| E-07 | 时间相同 | docId 升序，不能依赖 registry 迭代顺序 |
| E-08 | `uploadedAt=null` | nullsLast，且不能在比较时 NPE |
| E-09 | `docId=null` | `DocumentInfo` 未校验而 comparator 会 NPE；合法数据是否允许 null **待验证**。在契约明确前不写断言 |
| E-10 | 分区文档极多 | 当前全量拉取、全量 bulk、O(n log n) 排序后才切片，存在内存/延迟风险；本轮无正式容量 SLA，不造随机器性能测试 |
| E-11 | authz 返回未知额外 docId | service 最终按原列表过滤，额外 id 不会注入响应；属于防御性行为，无需单列 |
| E-12 | `?page=abc` 或超 int 文本 | Spring binder 应返回 400，service 不执行；本轮禁止容器测试，不重复验证框架 |
| E-13 | TenantContext 未设置 | 平台会使用 `anonymous`；不是本次新增合同，不锁定为正常业务租户 |
| E-14 | 列表同时上传/删除 | 见 ISSUE-02，先定快照合同 |
| E-15 | authz bulk 超时/异常 | 安全上 fail-closed；API 结果合同见 ISSUE-03 |

### 2.6 flaky-risk-reviewer：脆弱性与稳健写法

- **时间**：现有分页测试通过连续 `upload()` + `Instant.now()` 验证时间序，不能强制制造相同时间。草案直接构造固定 `Instant.parse(...)` 的 `DocumentInfo`，不 sleep。
- **顺序**：不依赖 `ConcurrentHashMap.values()` 或 Redis HVALS 顺序；排序单测让 mock registry 返回明确乱序列表，跨页测试断言完整精确序列。
- **TenantContext 泄漏**：每个涉及 service 的类在 `@BeforeEach` 设置 tenant，在 `@AfterEach` 无条件 `clear()`。测试中切 tenant 也由同一个 after 清理。
- **共享静态状态**：没有静态可变 registry/authz；固定时间是不可变常量。
- **外部依赖**：不使用真实 auth-platform/Redis/向量数据库。现有 `KnowledgeAuthzIntegrationTest` 会探测网络并 assumption skip，且命名不是 `*IT`；它不应被算作本计划的确定性验收。后续可另行按仓库铁律改名、加 tag/profile，本任务不改它。
- **H2**：本被测面无需 DB，不创建 H2；因此不存在库名冲突。若未来 registry 下沉 JDBC，必须每测试唯一 `jdbc:h2:mem:<name>;MODE=MySQL;DB_CLOSE_DELAY=-1`。
- **Mockito**：全部 `mock(...)` 手动创建，不使用 extension、字段 `@Mock` 或 Spring mock。关键调用用精确参数和 `verifyNoMoreInteractions`，避免只断言非 null。
- **容器**：不使用 `@SpringBootTest/@WebMvcTest/MockMvc`。Controller 路由用反射锁定声明，实际 Spring 参数绑定行为留给已有应用启动冒烟。

## 3. 完整可编译测试代码草案

以下代码块是交给后续开发 Agent 落地的完整类。除明确写“新增”外，现有文件应以草案内容合并/替换；本轮没有把它们写进 `src/`。

### 3.1 `DocumentServicePagingTest`（替换/增强现有类）

精确放置路径：

`knowledge-service/src/test/java/com/lrj/platform/knowledge/lifecycle/DocumentServicePagingTest.java`

```java
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
```

锁定行为与关键断言：

- 固定时间 + 明确乱序输入，使测试真正区分“时间排序”“同刻 docId tiebreak”“nullsLast”，不会因为三次 `Instant.now()` 恰好递增而虚绿。
- ENFORCE 用 6 个候选只允许 3 个，再请求超高页；`total=3、totalPages=2、page=2、items=[doc-005]` 同时证明先过滤、再算页数、再 clamp、再切片。只断 `items.size()` 无法证明这个次序。
- SHADOW 的桩故意返回子集，但 service 仍必须返回全集；同时检查 filter 被调用，防止实现错误地把 shadow 当 disabled。
- disabled/shared 检查 `filterCalls==0`，不是只看结果碰巧相同。
- 三分区测试在同一 registry 中切换 `TenantContext`，直接证明没有租户串味。
- `Integer.MIN/MAX_VALUE` 和 101 条数据同时检查规范化 size、页数、末页内容，能发现 size 字段与 totalPages 使用不同口径的 bug。

待修复后再补、当前不要加入通过型断言：

```text
// TODO(issue-01): failedEnforceCreation_doesNotChangeRegistryOrPagedTotal
// TODO(issue-02): define snapshot/cursor contract before testing concurrent page mutations
// TODO(issue-03): decide whether authz dependency failure returns 503/degraded metadata or an empty envelope
```

### 3.2 `DocumentControllerPagingMappingTest`（新增）

精确放置路径：

`knowledge-service/src/test/java/com/lrj/platform/knowledge/controller/DocumentControllerPagingMappingTest.java`

```java
package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.lifecycle.PagedDocuments;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentControllerPagingMappingTest {

    @Test
    void pageParameterIsTheOnlyConditionSelectingPagedEnvelope() throws NoSuchMethodException {
        Method legacy = DocumentController.class.getDeclaredMethod("list", String.class);
        Method paged = DocumentController.class.getDeclaredMethod(
                "listPaged", String.class, int.class, Integer.class);

        GetMapping legacyMapping = legacy.getAnnotation(GetMapping.class);
        GetMapping pagedMapping = paged.getAnnotation(GetMapping.class);

        assertThat(legacyMapping).isNotNull();
        assertThat(legacyMapping.params()).isEmpty();
        assertThat(legacy.getReturnType()).isEqualTo(List.class);

        assertThat(pagedMapping).isNotNull();
        assertThat(pagedMapping.params()).containsExactly("page");
        assertThat(paged.getReturnType()).isEqualTo(PagedDocuments.class);

        RequestParam pageParam = paged.getParameters()[1].getAnnotation(RequestParam.class);
        RequestParam sizeParam = paged.getParameters()[2].getAnnotation(RequestParam.class);
        assertThat(pageParam).isNotNull();
        assertThat(pageParam.defaultValue()).isEqualTo("1");
        assertThat(sizeParam).isNotNull();
        assertThat(sizeParam.required()).isFalse();
    }
}
```

锁定行为与关键断言：

- 当前 `DocumentControllerPublicTest` 已完整验证 `listPaged(null,2,10)` 原样传非空 size，以及 public + null size 传 0，本计划不复制它们。
- 新测试验证的是直接调用无法覆盖的 mapping 声明：旧方法没有 params 条件、新方法必须有且只有 `page` 条件，同时返回类型分别为数组/信封。
- 这不是完整 Spring MVC 解析测试；“更具体 mapping 优先”和非法数字 400 属于框架合同。若未来项目允许极少量容器冒烟，可再补 HTTP 级契约，当前标记为**待验证**而非伪装已覆盖。

### 3.3 `PagedDocumentsTest`（新增）

精确放置路径：

`knowledge-service/src/test/java/com/lrj/platform/knowledge/lifecycle/PagedDocumentsTest.java`

```java
package com.lrj.platform.knowledge.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PagedDocumentsTest {

    @Test
    void serializesStableEnvelopeFieldNamesAndValues() {
        PagedDocuments envelope = new PagedDocuments(List.of(), 2, 20, 21L, 2);

        JsonNode json = new ObjectMapper().valueToTree(envelope);
        Set<String> fields = new LinkedHashSet<>();
        json.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrder(
                "items", "page", "size", "total", "totalPages");
        assertThat(json.get("items").isArray()).isTrue();
        assertThat(json.get("items").isEmpty()).isTrue();
        assertThat(json.get("page").asInt()).isEqualTo(2);
        assertThat(json.get("size").asInt()).isEqualTo(20);
        assertThat(json.get("total").asLong()).isEqualTo(21L);
        assertThat(json.get("totalPages").asInt()).isEqualTo(2);
    }
}
```

锁定行为与关键断言：

- 不只验证 record accessor，而是验证实际 JSON 字段名和值，能发现字段重命名、遗漏或类型映射回归。
- `items` 使用空列表是刻意的：本测试只验证分页信封，不重复 `DocumentInfo` 的 `Instant` 序列化配置，也不把普通 `ObjectMapper` 与 Spring 的 JavaTime module 混为一谈。
- record 本身没有合法性校验，草案不虚构“非法 total 应抛异常”的需求；合法 envelope 由 service 测试负责。

### 3.4 `KnowledgeChatModelConfigTest`（增强现有类）

精确放置路径：

`knowledge-service/src/test/java/com/lrj/platform/knowledge/KnowledgeChatModelConfigTest.java`

```java
package com.lrj.platform.knowledge;

import com.lrj.platform.gateway.GatewayChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class KnowledgeChatModelConfigTest {

    @Test
    void knowledgeChatModel_builtViaFactory_withDeterministicTemperature() {
        GatewayChatModelFactory factory = mock(GatewayChatModelFactory.class);
        ChatModel built = mock(ChatModel.class);
        when(factory.build("rerank-mini", 0.0)).thenReturn(built);

        ChatModel result = new KnowledgeChatModelConfig()
                .knowledgeChatModel(factory, "rerank-mini");

        assertThat(result).isSameAs(built);
        verify(factory).build("rerank-mini", 0.0);
        verifyNoMoreInteractions(factory);
    }

    @Test
    void knowledgeChatModel_isUnconditionalApplicationBeanWithExpectedSignature()
            throws NoSuchMethodException {
        Method method = KnowledgeChatModelConfig.class.getDeclaredMethod(
                "knowledgeChatModel", GatewayChatModelFactory.class, String.class);

        assertThat(method.isAnnotationPresent(Bean.class)).isTrue();
        assertThat(method.isAnnotationPresent(ConditionalOnMissingBean.class)).isFalse();
        assertThat(method.getReturnType()).isEqualTo(ChatModel.class);
        assertThat(method.getParameterTypes())
                .containsExactly(GatewayChatModelFactory.class, String.class);
    }
}
```

锁定行为与关键断言：

- `isSameAs` 证明配置没有包裹或另建本地模型；精确 `verify` + `verifyNoMoreInteractions` 防止同时调用默认 `build()` 或创建第二模型。
- 反射测试直接锁定明确改动目标“删除 `@ConditionalOnMissingBean`”，否则原行为测试即使注解被误加回来也会虚绿。
- 属性占位符的实际解析和自动配置排序需要 Spring 容器。本轮铁律禁止为此启动 context，故该项明确为**待验证：应用启动冒烟**。

## 4. 为什么不新增其它测试

- 不为 `readable(...)`、`sorted(...)` 私有方法做反射测试；通过公共 `list/listPaged` 观察真实调用链，避免锁实现细节。
- 不重复 `DocumentControllerPublicTest` 已有的两个分页委派用例；只补它无法证明的 mapping 条件。
- 不增加 Redis 测试。service 排序已主动消除 Redis HVALS 顺序差异；mock/内存输入更可控。
- 不增加真实 auth-platform IT。`RealKnowledgeAuthzTest` 已覆盖 bulk 行为，本轮缺的是与分页 envelope 的组合，确定性桩更合适。
- 不测试 `PagedDocuments` 的任意非法组合，因为当前 record 没有验证合同；强加异常断言会虚构需求。

## 5. 运行与验证命令

前置说明：本计划中的四个类都不经过内部 JWT；完整模块测试按仓库统一约定仍提供至少 32 字节 secret。

完整模块及上游共享库：

```bash
INTERNAL_JWT_SECRET=test-secret-at-least-32-bytes-long \
  mvn -pl knowledge-service -am test
```

聚焦单类：

```bash
mvn -pl knowledge-service -Dtest=DocumentServicePagingTest test
mvn -pl knowledge-service -Dtest=DocumentControllerPagingMappingTest test
mvn -pl knowledge-service -Dtest=PagedDocumentsTest test
mvn -pl knowledge-service -Dtest=KnowledgeChatModelConfigTest test
```

建议落地顺序：先单类编译运行，再运行 `knowledge-service -am` 全套，最后检查没有测试顺序依赖。不得从 reactor 根省略 `-pl` 直接使用单类 `-Dtest`，否则其它模块会因无匹配测试报错。

本次设计阶段没有执行这些命令，因为任务硬约束只允许写 `docs/tests/doc-lifecycle-0717-1617/`，而 Maven 会写模块 `target/`。

## 6. test-judge 最终审查结论

已按“不能只为通过”反向检查草案：

- 所有测试同被测类包名，类名均以 `Test` 结尾。
- 没有 `@SpringBootTest/@WebMvcTest/@DataJpaTest/@MockBean/@ExtendWith/@Mock`。
- 协作者使用直接 `new`、手动 `mock(...)` 或测试内确定性实现。
- 断言使用 AssertJ；草案没有需要异常断言的通过型用例。
- 所有涉及 TenantContext 的测试均有 `@BeforeEach set` 和 `@AfterEach clear`。
- 没有 DB、H2、外部服务、随机数、系统当前时间或 sleep。
- 关键测试断言 envelope 的多个互相关联字段和精确 items，而不是只断非空/size。
- authz 测试同时验证调用/不调用、候选、租户、用户与最终 envelope，避免“返回结果碰巧一样”的确认偏差。
- 已删除现有测试中“稳定排序天然保证并发翻页不重不漏”的过强结论，只锁静态快照。
- 三项疑似 bug 已单列，代码草案只保留 TODO，不把错误现状固化。

仍不确定、必须如实保留为“待验证”的点：

1. `DocumentInfo.docId` 是否允许 null；当前生产 comparator 对 null docId 会 NPE。
2. 授权依赖故障应返回 503/降级标记还是 200 空信封。
3. 产品是否要求并发变更期间的跨页快照一致性。
4. Spring 容器中的属性占位符解析、auto-configuration backoff 与 HTTP 参数 binder 行为；纯 POJO 计划不声称覆盖这些框架集成。
5. 草案尚未由 Maven 实际编译；落地 Agent 必须执行第 5 节命令。这里的“可直接编译”基于已核对的仓库实际类名、包名、构造器和方法签名，不等同于已运行证明。

## 7. 最终验收清单

- [ ] 只把测试实现落到上述 `knowledge-service/src/test/java` 精确路径；本设计阶段仍只改文档目录。
- [ ] 四个聚焦测试类全部编译并通过。
- [ ] `INTERNAL_JWT_SECRET` 满足长度后，`mvn -pl knowledge-service -am test` 全绿。
- [ ] 02 矩阵中同刻 tiebreak、nullsLast、授权三态、shared bypass、跨租户分区、size/page 极值、Controller mapping、JSON 信封、无条件工厂 Bean 缺口闭合。
- [ ] 没有新增 Spring context、MockMvc、Testcontainers、外部网络依赖或顺序/时间脆弱测试。
- [ ] 每个使用 TenantContext 的测试都执行 `TenantContext.clear()`。
- [ ] ISSUE-01/02/03 已单列上报；修复/定约前没有被写成“当前行为正确”的断言。
- [ ] 对待验证项形成后续决定：docId null 契约、授权故障响应、并发分页快照语义、框架级启动冒烟。
