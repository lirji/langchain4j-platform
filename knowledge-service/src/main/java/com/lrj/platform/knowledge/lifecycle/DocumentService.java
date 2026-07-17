package com.lrj.platform.knowledge.lifecycle;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.knowledge.DocumentMirror;
import com.lrj.platform.knowledge.DocumentSplitterFactory;
import com.lrj.platform.knowledge.cache.SemanticCacheInvalidator;
import com.lrj.platform.knowledge.graph.GraphIngestor;
import com.lrj.platform.knowledge.es.NoopSegmentIndexer;
import com.lrj.platform.knowledge.es.SegmentIndexer;
import com.lrj.platform.knowledge.ingest.ContextualEnricher;
import com.lrj.platform.knowledge.ingest.NoopContextualEnricher;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import com.lrj.platform.knowledge.store.SingleEmbeddingStoreRouter;
import com.lrj.platform.knowledge.PublicKb;
import com.lrj.platform.knowledge.authz.KnowledgeAuthz;
import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.lrj.platform.knowledge.authz.AuthzMode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 知识文档全生命周期服务：负责上传（切分 {@link DocumentSplitterFactory} → 可选 Contextual 增强 → 嵌入
 * {@link EmbeddingModel} → 写向量库 {@link EmbeddingStoreRouter}、词法镜像 {@link DocumentMirror}、可选 ES 索引与
 * GraphRAG {@link GraphIngestor}）、列举、读取与删除，并维护 {@code DocumentRegistry} 元数据、审计与语义缓存失效。
 * 所有操作按 {@link TenantContext} 租户隔离，{@code shared=true} 时落公共/共享库保留分区（{@link PublicKb#TENANT_ID}）；
 * 文档级授权经可插拔 {@link KnowledgeAuthz}（{@code app.rag.authz.mode}，默认 Noop 恒放行）。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final EmbeddingStoreRouter storeRouter;
    private final EmbeddingModel embeddingModel;
    private final DocumentMirror documentMirror;
    private final DocumentSplitterFactory splitterFactory;
    private final DocumentRegistry registry;
    private final AuditLogger audit;
    private final GraphIngestor graphIngestor;
    private final SemanticCacheInvalidator cacheInvalidator;
    // Contextual Retrieval 入库增强：默认 Noop（不改 chunk），仅 Spring @Autowired 路径按开关注入真实实现。
    // 非 final：字段初始化在终端构造器执行，各测试用委托构造器天然拿到 Noop，无需改测试。
    private ContextualEnricher contextualEnricher = new NoopContextualEnricher();
    // 切分质量打点（F5.3）：默认 null（测试委托构造器天然拿到 null → 跳过），生产由 Spring @Autowired setter 注入。
    private com.lrj.platform.knowledge.observability.ChunkMetrics chunkMetrics;
    // ES 全文索引器（es-hybrid-rerank）：默认 Noop（测试与 ES 关闭时零副作用），生产由 Spring @Autowired setter 注入。
    private SegmentIndexer segmentIndexer = new NoopSegmentIndexer();
    // 细粒度授权钩子（接 auth-platform）：默认 Noop（关时零副作用、测试不受影响），app.rag.authz.mode=shadow|enforce 时注入 Real。
    private KnowledgeAuthz knowledgeAuthz = new NoopKnowledgeAuthz();

    @Autowired
    public DocumentService(EmbeddingStoreRouter storeRouter,
                           EmbeddingModel embeddingModel,
                           DocumentMirror documentMirror,
                           DocumentSplitterFactory splitterFactory,
                           DocumentRegistry registry,
                           AuditLogger audit,
                           ObjectProvider<GraphIngestor> graphIngestorProvider,
                           ObjectProvider<SemanticCacheInvalidator> cacheInvalidatorProvider,
                           ObjectProvider<ContextualEnricher> contextualEnricherProvider) {
        this(storeRouter,
                embeddingModel,
                documentMirror,
                splitterFactory,
                registry,
                audit,
                graphIngestorProvider == null ? null : graphIngestorProvider.getIfAvailable(),
                cacheInvalidatorProvider == null ? null : cacheInvalidatorProvider.getIfAvailable());
        if (contextualEnricherProvider != null) {
            this.contextualEnricher = contextualEnricherProvider.getIfAvailable(NoopContextualEnricher::new);
        }
    }

    /** 注入自定义 Contextual 增强器（生产由 Spring @Autowired 按开关装配；也供测试/编程式覆盖）。 */
    public void setContextualEnricher(ContextualEnricher contextualEnricher) {
        this.contextualEnricher = contextualEnricher == null ? new NoopContextualEnricher() : contextualEnricher;
    }

    /** 注入切分质量打点（F5.3，生产由 Spring @Autowired；测试委托构造器天然为 null → 跳过打点）。 */
    @Autowired(required = false)
    public void setChunkMetrics(com.lrj.platform.knowledge.observability.ChunkMetrics chunkMetrics) {
        this.chunkMetrics = chunkMetrics;
    }

    /** 注入 ES 索引器（es-hybrid-rerank，生产由 Spring @Autowired；测试保持默认 Noop）。 */
    @Autowired(required = false)
    public void setSegmentIndexer(SegmentIndexer segmentIndexer) {
        if (segmentIndexer != null) {
            this.segmentIndexer = segmentIndexer;
        }
    }

    /** 注入细粒度授权钩子（生产由 Spring @Autowired 按 app.rag.authz.mode 装配；测试保持默认 Noop）。 */
    @Autowired(required = false)
    public void setKnowledgeAuthz(KnowledgeAuthz knowledgeAuthz) {
        if (knowledgeAuthz != null) {
            this.knowledgeAuthz = knowledgeAuthz;
        }
    }

    public DocumentService(EmbeddingStoreRouter storeRouter,
                           EmbeddingModel embeddingModel,
                           DocumentMirror documentMirror,
                           DocumentSplitterFactory splitterFactory,
                           DocumentRegistry registry,
                           AuditLogger audit,
                           GraphIngestor graphIngestor,
                           SemanticCacheInvalidator cacheInvalidator) {
        this.storeRouter = storeRouter;
        this.embeddingModel = embeddingModel;
        this.documentMirror = documentMirror;
        this.splitterFactory = splitterFactory;
        this.registry = registry;
        this.audit = audit;
        this.graphIngestor = graphIngestor;
        this.cacheInvalidator = cacheInvalidator;
    }

    /** 7 参 router 构造（不带缓存失效器）——向后兼容既有调用点。 */
    public DocumentService(EmbeddingStoreRouter storeRouter,
                           EmbeddingModel embeddingModel,
                           DocumentMirror documentMirror,
                           DocumentSplitterFactory splitterFactory,
                           DocumentRegistry registry,
                           AuditLogger audit,
                           GraphIngestor graphIngestor) {
        this(storeRouter, embeddingModel, documentMirror, splitterFactory, registry, audit, graphIngestor, null);
    }

    // ---- backward-compatible constructors: wrap a single store (metadata-filter isolation) ----

    public DocumentService(EmbeddingStore<TextSegment> embeddingStore,
                           EmbeddingModel embeddingModel,
                           DocumentMirror documentMirror,
                           DocumentSplitterFactory splitterFactory,
                           DocumentRegistry registry,
                           AuditLogger audit) {
        this(new SingleEmbeddingStoreRouter(embeddingStore, embeddingModel.dimension()),
                embeddingModel, documentMirror, splitterFactory, registry, audit, (GraphIngestor) null, null);
    }

    public DocumentService(EmbeddingStore<TextSegment> embeddingStore,
                           EmbeddingModel embeddingModel,
                           DocumentMirror documentMirror,
                           DocumentSplitterFactory splitterFactory,
                           DocumentRegistry registry,
                           AuditLogger audit,
                           GraphIngestor graphIngestor) {
        this(new SingleEmbeddingStoreRouter(embeddingStore, embeddingModel.dimension()),
                embeddingModel, documentMirror, splitterFactory, registry, audit, graphIngestor, null);
    }

    public DocumentService(EmbeddingStore<TextSegment> embeddingStore,
                           EmbeddingModel embeddingModel,
                           DocumentMirror documentMirror,
                           DocumentSplitterFactory splitterFactory,
                           DocumentRegistry registry,
                           AuditLogger audit,
                           GraphIngestor graphIngestor,
                           SemanticCacheInvalidator cacheInvalidator) {
        this(new SingleEmbeddingStoreRouter(embeddingStore, embeddingModel.dimension()),
                embeddingModel, documentMirror, splitterFactory, registry, audit, graphIngestor, cacheInvalidator);
    }

    public DocumentInfo upload(String displayName, String contentType, String text, String category) {
        return upload(displayName, contentType, text, category, -1);
    }

    public DocumentInfo upload(String displayName, String contentType, String text, String category, long sourceSizeBytes) {
        return upload(displayName, contentType, text, category, sourceSizeBytes, false);
    }

    /**
     * @param shared true 时写入公共/共享库保留分区（tenantId={@link PublicKb#TENANT_ID}）而非调用方租户；
     *               需 public-ingest scope（由 controller 关口把守）。四个 sink 均从该 tenantId 派生隔离。
     */
    public DocumentInfo upload(String displayName, String contentType, String text, String category,
                               long sourceSizeBytes, boolean shared) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("text is empty");
        }
        String tenantId = shared ? PublicKb.TENANT_ID : TenantContext.current().tenantId();
        String docId = computeDocId(tenantId, displayName);

        Optional<DocumentInfo> existing = registry.get(tenantId, docId);
        // 同名覆盖 = 对已存在文档的写：非公共库须有 edit 权，否则拒绝——防只有 ingest 的用户覆盖内容/夺取他人文档。
        // 默认 Noop 恒放行；enforce 时无 edit 抛 403。
        if (existing.isPresent() && !shared
                && !knowledgeAuthz.checkDocument(tenantId, TenantContext.current().userId(), docId, "edit")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no edit permission to overwrite existing document");
        }
        int nextVersion = existing
                .map(prev -> {
                    deleteInternal(prev);
                    return prev.version() + 1;
                })
                .orElse(1);

        Document doc = Document.from(text);
        doc.metadata()
                .put("tenantId", tenantId)
                .put("docId", docId)
                .put("displayName", displayName)
                .put("file_name", displayName)
                .put("version", String.valueOf(nextVersion));
        if (category != null && !category.isBlank()) {
            doc.metadata().put("category", category);
        }

        DocumentSplitter splitter = splitterFactory.create();
        List<TextSegment> segments = splitter.split(doc);
        // Contextual Retrieval：入库前给每 chunk 加文档级上下文前缀再嵌入（默认关时为 no-op，原样返回）。
        segments = contextualEnricher.enrich(text, segments);
        // 切分质量打点（F5.3）：记录最终入库形态的尺寸分布/碎块/超大块（默认注入，null 时跳过）。
        if (chunkMetrics != null) {
            chunkMetrics.record(splitterFactory.strategy(), 1, segments);
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        storeRouter.forTenant(tenantId, embeddingModel.dimension()).addAll(embeddings, segments);
        documentMirror.add(segments);
        // ES 全文索引（es-hybrid-rerank）：与向量写入同一批最终 chunk；默认 Noop，失败按 fail-fast/best-effort 由索引器处理。
        segmentIndexer.index(segments);
        if (graphIngestor != null) {
            graphIngestor.ingest(segments);
        }

        DocumentInfo info = new DocumentInfo(
                docId,
                tenantId,
                displayName,
                contentType == null ? "text/plain" : contentType,
                sourceSizeBytes > 0 ? sourceSizeBytes : text.getBytes(StandardCharsets.UTF_8).length,
                segments.size(),
                nextVersion,
                Instant.now(),
                category == null || category.isBlank() ? null : category);
        registry.put(info);
        // 双写授权关系：仅【新建】文档写 owner + home_dept（上传人部门）；同名覆盖保留原 owner（不夺权）。默认 Noop。
        if (!shared && existing.isEmpty()) {
            String department = TenantContext.current().department();
            // enforce 下无法确定上传人部门 → 拒绝新建（不写无归属的孤儿文档，只有 owner 能看）；disabled/shadow 不拦。
            if (knowledgeAuthz.mode() == AuthzMode.ENFORCE && (department == null || department.isBlank())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "cannot determine uploader's home department; refusing document creation under enforce");
            }
            knowledgeAuthz.onDocumentCreated(tenantId, docId, TenantContext.current().userId(), department);
        }
        audit.record(AuditEventType.DOCUMENT_UPLOADED, Map.of(
                "docId", docId,
                "displayName", displayName,
                "version", nextVersion,
                "segments", segments.size(),
                "sizeBytes", info.sizeBytes(),
                "category", category == null ? "" : category));
        log.info("uploaded knowledge doc tenant={} docId={} name='{}' version={} segments={}",
                tenantId, docId, displayName, nextVersion, segments.size());
        invalidateSemanticCache();
        return info;
    }

    public List<DocumentInfo> list() {
        return list(false);
    }

    /**
     * @param shared true 时列共享库保留分区（tenantId={@link PublicKb#TENANT_ID}）的文档元数据，
     *               而非调用方租户；共享元数据读取不需特殊 scope（由 controller 关口决定）。
     */
    public List<DocumentInfo> list(boolean shared) {
        List<DocumentInfo> all = registry.list(partition(shared));
        if (shared || !knowledgeAuthz.enabled() || all.isEmpty()) {
            return all;
        }
        // 列表读判权：shadow 记 would_filter 并返回全集；enforce 过滤为可 view 子集（默认 Noop 恒放行）。
        String tenantId = partition(shared);
        Set<String> docIds = all.stream().map(DocumentInfo::docId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> readable = knowledgeAuthz.filterReadable(tenantId, TenantContext.current().userId(), docIds);
        if (knowledgeAuthz.mode() != AuthzMode.ENFORCE) {
            return all;
        }
        return all.stream().filter(d -> readable.contains(d.docId())).toList();
    }

    public Optional<DocumentInfo> get(String docId) {
        return get(docId, false);
    }

    /**
     * @param shared true 时读共享库保留分区的单文档元数据（公共库不判权）。
     *               非公共库时按当前用户对该文档做 view 判权（统一 app.rag.authz.mode，默认 Noop 恒放行）。
     */
    public Optional<DocumentInfo> get(String docId, boolean shared) {
        Optional<DocumentInfo> info = registry.get(partition(shared), docId);
        if (info.isEmpty() || shared) {
            return info;
        }
        // 单文档读判权：enforce 拒绝→empty（上层转 404，不泄露文档存在性）；shadow 放行；默认 Noop 恒 true。
        String tenantId = TenantContext.current().tenantId();
        String userId = TenantContext.current().userId();
        if (!knowledgeAuthz.checkDocument(tenantId, userId, docId, "view")) {
            return Optional.empty();
        }
        return info;
    }

    public boolean delete(String docId) {
        return delete(docId, false);
    }

    /** @param shared true 时删共享库保留分区文档（各 sink 从该 tenantId 派生）；scope 关口由 controller 把守。 */
    public boolean delete(String docId, boolean shared) {
        String tenantId = partition(shared);
        Optional<DocumentInfo> info = registry.get(tenantId, docId);
        if (info.isEmpty()) {
            return false;
        }
        // 单文档删判权（非公共库）：判 edit。enforce 拒绝→false（上层转 404）；shadow 放行；默认 Noop 恒 true。
        if (!shared && !knowledgeAuthz.checkDocument(tenantId, TenantContext.current().userId(), docId, "edit")) {
            return false;
        }
        // fail-closed 顺序：【先撤 SpiceDB 关系，再删业务数据】。撤关系抛错则中止（宁留数据不留悬空权限）；
        // 撤关系成功后即便后续业务删除失败，该文档也已对所有人不可见（安全侧）。默认 Noop。
        if (!shared) {
            knowledgeAuthz.onDocumentDeleted(tenantId, docId);
        }
        deleteInternal(info.get());
        registry.remove(tenantId, docId);
        audit.record(AuditEventType.DOCUMENT_DELETED, Map.of(
                "docId", docId,
                "displayName", info.get().displayName(),
                "version", info.get().version()));
        log.info("deleted knowledge doc tenant={} docId={} name='{}'", tenantId, docId, info.get().displayName());
        invalidateSemanticCache();
        return true;
    }

    /** 知识变更后失效当前租户语义缓存（松耦合、尽力而为；未配 / 关闭时为 no-op，异常吞掉不阻断）。 */
    private void invalidateSemanticCache() {
        if (cacheInvalidator == null) {
            return;
        }
        try {
            cacheInvalidator.invalidateCurrentTenant();
        } catch (RuntimeException e) {
            log.warn("semantic cache invalidation call failed (ignored): {}", e.toString());
        }
    }

    private void deleteInternal(DocumentInfo info) {
        try {
            Filter filter = Filter.and(
                    metadataKey("tenantId").isEqualTo(info.tenantId()),
                    metadataKey("docId").isEqualTo(info.docId()));
            storeRouter.forTenant(info.tenantId(), embeddingModel.dimension()).removeAll(filter);
        } catch (UnsupportedOperationException ex) {
            log.warn("EmbeddingStore does not support removeAll(Filter); skipping vector delete for docId={}",
                    info.docId(), ex);
        }
        documentMirror.removeWhere(seg ->
                seg.metadata() != null
                        && Objects.equals(info.tenantId(), seg.metadata().getString("tenantId"))
                        && Objects.equals(info.docId(), seg.metadata().getString("docId")));
        segmentIndexer.deleteByDoc(info.tenantId(), info.docId());
        if (graphIngestor != null) {
            graphIngestor.removeBySourcePrefix(info.tenantId(), info.displayName() + "#");
        }
    }

    /** 目标分区：共享库固定为保留 tenantId，否则当前租户。 */
    private static String partition(boolean shared) {
        return shared ? PublicKb.TENANT_ID : TenantContext.current().tenantId();
    }

    static String computeDocId(String tenantId, String displayName) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((tenantId + ":" + displayName).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
