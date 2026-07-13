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
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

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

        int nextVersion = registry.get(tenantId, docId)
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
        return registry.list(partition(shared));
    }

    public Optional<DocumentInfo> get(String docId) {
        return get(docId, false);
    }

    /** @param shared true 时读共享库保留分区的单文档元数据。 */
    public Optional<DocumentInfo> get(String docId, boolean shared) {
        return registry.get(partition(shared), docId);
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
