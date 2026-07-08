package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.DocumentService;
import com.lrj.platform.knowledge.lifecycle.DocumentTextExtractor;
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
                                        @RequestParam(required = false) String category) throws IOException {
        requireIngest();
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().header("X-Error", "empty file").build();
        }
        String displayName = file.getOriginalFilename();
        String contentType = file.getContentType();
        try {
            if (MultimodalIngestionText.isImageContentType(contentType)) {
                return ingestImage(displayName, contentType, file.getBytes());
            }
            String text = extractor.extract(file.getInputStream(), displayName);
            return ResponseEntity.ok(documents.upload(displayName, contentType, text, category, file.getSize()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().header("X-Error", e.getMessage()).build();
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploadJson(@RequestBody Map<String, String> body) {
        requireIngest();
        String title = body.get("title");
        String text = body.get("text");
        String imageBase64 = body.get("imageBase64");
        String contentType = body.getOrDefault("contentType", imageBase64 == null ? "text/plain" : "image/png");
        String category = body.get("category");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().header("X-Error", "title is required").build();
        }
        if (imageBase64 != null && !imageBase64.isBlank()) {
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
        return ResponseEntity.ok(documents.upload(title, contentType, text, category));
    }

    @GetMapping
    public List<DocumentInfo> list() {
        return documents.list();
    }

    @GetMapping("/{docId}")
    public ResponseEntity<DocumentInfo> get(@PathVariable String docId) {
        Optional<DocumentInfo> info = documents.get(docId);
        return info.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{docId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String docId) {
        requireIngest();
        boolean removed = documents.delete(docId);
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
}
