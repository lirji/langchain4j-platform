package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.DocumentService;
import com.lrj.platform.knowledge.lifecycle.DocumentTextExtractor;
import com.lrj.platform.knowledge.lifecycle.PagedDocuments;
import com.lrj.platform.knowledge.lifecycle.MultimodalIngestionText;
import com.lrj.platform.knowledge.multimodal.MultimodalRetrievalService;
import com.lrj.platform.security.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RAG 文档入口。<strong>文本</strong>走切块 + 文本 embedding 入 {@code knowledge_segments}；
 * <strong>图片</strong>走原生 CLIP 多模态 embedding 入 {@code knowledge_images}（{@link MultimodalRetrievalService}），
 * 不再做「图→文字→再 embed」。图片处理依赖 {@code app.rag.multimodal-embedding.enabled=true}，
 * 未开启时上传图片返回明确错误。
 */
@RestController
@RequestMapping("/rag/documents")
public class DocumentController {

    private final DocumentService documents;
    private final DocumentTextExtractor extractor;
    private final ObjectProvider<MultimodalRetrievalService> multimodalProvider;

    public DocumentController(DocumentService documents,
                              DocumentTextExtractor extractor,
                              ObjectProvider<MultimodalRetrievalService> multimodalProvider) {
        this.documents = documents;
        this.extractor = extractor;
        this.multimodalProvider = multimodalProvider;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFile(@RequestPart("file") MultipartFile file,
                                        @RequestParam(required = false) String category,
                                        @RequestParam(required = false) String visibility) throws IOException {
        boolean shared = isPublic(visibility);
        requireWrite(shared);
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().header("X-Error", "empty file").build();
        }
        String displayName = file.getOriginalFilename();
        String contentType = file.getContentType();
        try {
            if (MultimodalIngestionText.isImageContentType(contentType)) {
                if (shared) {
                    return ResponseEntity.badRequest().header("X-Error", "public image ingestion not supported").build();
                }
                return ingestImage(displayName, contentType, file.getBytes());
            }
            String text = extractor.extract(file.getInputStream(), displayName);
            return ResponseEntity.ok(shared
                    ? documents.upload(displayName, contentType, text, category, file.getSize(), true)
                    : documents.upload(displayName, contentType, text, category, file.getSize()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().header("X-Error", e.getMessage()).build();
        }
    }

    /** 便捷重载（POJO 测试用；可见性仅取 body.visibility）。生产入口是下面带 query 回退的 @PostMapping 版本。 */
    public ResponseEntity<?> uploadJson(Map<String, String> body) {
        return uploadJson(body, null);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploadJson(@RequestBody Map<String, String> body,
                                        @RequestParam(required = false) String visibility) {
        // 可见性优先取 body.visibility，其次 query ?visibility=（供前端"共享上传"专用 cap 用路径 query 表达）。
        boolean shared = isPublic(body.get("visibility") != null ? body.get("visibility") : visibility);
        requireWrite(shared);
        String title = body.get("title");
        String text = body.get("text");
        String imageBase64 = body.get("imageBase64");
        String contentType = body.getOrDefault("contentType", imageBase64 == null ? "text/plain" : "image/png");
        String category = body.get("category");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().header("X-Error", "title is required").build();
        }
        if (imageBase64 != null && !imageBase64.isBlank()) {
            if (shared) {
                return ResponseEntity.badRequest().header("X-Error", "public image ingestion not supported").build();
            }
            try {
                byte[] imageBytes = MultimodalIngestionText.decodeBase64Image(imageBase64);
                return ingestImage(title, contentType, imageBytes);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().header("X-Error", e.getMessage()).build();
            }
        }
        if (text == null) {
            return ResponseEntity.badRequest().header("X-Error", "text is required").build();
        }
        return ResponseEntity.ok(shared
                ? documents.upload(title, contentType, text, category, -1, true)
                : documents.upload(title, contentType, text, category));
    }

    /**
     * 列文档元数据（<strong>完整数组</strong>，无分页参数时）。{@code visibility=public|shared} 列共享库保留分区
     * （普通登录用户即可读，无需特殊 scope）；缺省 {@code tenant} 列当前租户（向后兼容）。响应里
     * {@link DocumentInfo#tenantId()} 为 {@code __public__} 即共享文档，前端据此映射 visibility。
     */
    @GetMapping
    public List<DocumentInfo> list(@RequestParam(required = false) String visibility) {
        return documents.list(isPublic(visibility));
    }

    /**
     * 分页列文档元数据（带 {@code page} 参数时命中此重载，返回 {@link PagedDocuments} 信封）。
     * 租户库 / 共享库同一入口，仅 {@code visibility} 区分。分页在租户隔离 + 文档级授权过滤之后进行，
     * {@code total} 即调用方可见文档总数。{@code page} 1-based；{@code size} 缺省 {@code 10}、上限 {@code 100}
     * （越界由 service clamp）。不带 {@code page} 的旧调用仍走上面的完整数组入口（向后兼容）。
     */
    @GetMapping(params = "page")
    public PagedDocuments listPaged(@RequestParam(required = false) String visibility,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(required = false) Integer size) {
        return documents.listPaged(isPublic(visibility), page, size == null ? 0 : size);
    }

    @GetMapping("/{docId}")
    public ResponseEntity<DocumentInfo> get(@PathVariable String docId,
                                            @RequestParam(required = false) String visibility) {
        Optional<DocumentInfo> info = documents.get(docId, isPublic(visibility));
        return info.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 删文档。{@code visibility=public|shared} 删共享库文档，需 {@code public-ingest} scope（沿用上传侧
     * {@code requireWrite(shared)} 关口）；缺省 {@code tenant} 删当前租户文档，需 {@code ingest}。
     */
    @DeleteMapping("/{docId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String docId,
                                                      @RequestParam(required = false) String visibility) {
        boolean shared = isPublic(visibility);
        requireWrite(shared);
        boolean removed = documents.delete(docId, shared);
        if (!removed) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("docId", docId, "deleted", true));
    }

    /**
     * 图片入库：原生 CLIP embed 进 image collection。多模态未开启（默认）时返回 400，
     * 提示开启 {@code app.rag.multimodal-embedding.enabled=true}（不再静默转文字）。
     */
    private ResponseEntity<?> ingestImage(String fileName, String contentType, byte[] imageBytes) {
        MultimodalRetrievalService multimodal = multimodalProvider.getIfAvailable();
        if (multimodal == null) {
            return ResponseEntity.badRequest().header("X-Error",
                            "image ingestion requires app.rag.multimodal-embedding.enabled=true")
                    .build();
        }
        String id = multimodal.ingestImage(imageBytes, contentType, fileName);
        return ResponseEntity.ok(Map.of("id", id, "fileName", fileName == null ? "" : fileName, "type", "image"));
    }

    private static void requireIngest() {
        if (!TenantContext.current().hasScope("ingest")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ingest scope required");
        }
    }

    /** 写公共/共享库需要专用 public-ingest scope；普通租户写沿用 ingest。 */
    private static void requireWrite(boolean shared) {
        if (shared) {
            if (!TenantContext.current().hasScope("public-ingest")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "public-ingest scope required");
            }
        } else {
            requireIngest();
        }
    }

    private static boolean isPublic(String visibility) {
        return "public".equalsIgnoreCase(visibility) || "shared".equalsIgnoreCase(visibility);
    }
}
