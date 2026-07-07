package com.lrj.platform.knowledge.lifecycle;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.knowledge.DocumentMirror;
import com.lrj.platform.knowledge.DocumentSplitterFactory;
import com.lrj.platform.knowledge.graph.GraphIngestor;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import com.lrj.platform.knowledge.store.SingleEmbeddingStoreRouter;
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

    @Autowired
    public DocumentService(EmbeddingStoreRouter storeRouter,
                           EmbeddingModel embeddingModel,
                           DocumentMirror documentMirror,
                           DocumentSplitterFactory splitterFactory,
                           DocumentRegistry registry,
                           AuditLogger audit,
                           ObjectProvider<GraphIngestor> graphIngestorProvider) {
        this(storeRouter,
                embeddingModel,
                documentMirror,
                splitterFactory,
                registry,
                audit,
                graphIngestorProvider == null ? null : graphIngestorProvider.getIfAvailable());
    }

    public DocumentService(EmbeddingStoreRouter storeRouter,
                           EmbeddingModel embeddingModel,
                           DocumentMirror documentMirror,
                           DocumentSplitterFactory splitterFactory,
                           DocumentRegistry registry,
                           AuditLogger audit,
                           GraphIngestor graphIngestor) {
        this.storeRouter = storeRouter;
        this.embeddingModel = embeddingModel;
        this.documentMirror = documentMirror;
        this.splitterFactory = splitterFactory;
        this.registry = registry;
        this.audit = audit;
        this.graphIngestor = graphIngestor;
    }

    // ---- backward-compatible constructors: wrap a single store (metadata-filter isolation) ----

    public DocumentService(EmbeddingStore<TextSegment> embeddingStore,
                           EmbeddingModel embeddingModel,
                           DocumentMirror documentMirror,
                           DocumentSplitterFactory splitterFactory,
                           DocumentRegistry registry,
                           AuditLogger audit) {
        this(new SingleEmbeddingStoreRouter(embeddingStore, embeddingModel.dimension()),
                embeddingModel, documentMirror, splitterFactory, registry, audit, (GraphIngestor) null);
    }

    public DocumentService(EmbeddingStore<TextSegment> embeddingStore,
                           EmbeddingModel embeddingModel,
                           DocumentMirror documentMirror,
                           DocumentSplitterFactory splitterFactory,
                           DocumentRegistry registry,
                           AuditLogger audit,
                           GraphIngestor graphIngestor) {
        this(new SingleEmbeddingStoreRouter(embeddingStore, embeddingModel.dimension()),
                embeddingModel, documentMirror, splitterFactory, registry, audit, graphIngestor);
    }

    public DocumentInfo upload(String displayName, String contentType, String text, String category) {
        return upload(displayName, contentType, text, category, -1);
    }

    public DocumentInfo upload(String displayName, String contentType, String text, String category, long sourceSizeBytes) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("text is empty");
        }
        String tenantId = TenantContext.current().tenantId();
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
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        storeRouter.forTenant(tenantId, embeddingModel.dimension()).addAll(embeddings, segments);
        documentMirror.add(segments);
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
        return info;
    }

    public List<DocumentInfo> list() {
        return registry.list(TenantContext.current().tenantId());
    }

    public Optional<DocumentInfo> get(String docId) {
        return registry.get(TenantContext.current().tenantId(), docId);
    }

    public boolean delete(String docId) {
        String tenantId = TenantContext.current().tenantId();
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
        return true;
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
        if (graphIngestor != null) {
            graphIngestor.removeBySourcePrefix(info.tenantId(), info.displayName() + "#");
        }
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
