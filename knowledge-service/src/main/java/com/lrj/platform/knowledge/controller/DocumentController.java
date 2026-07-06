package com.lrj.platform.knowledge.controller;

import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.DocumentService;
import com.lrj.platform.knowledge.lifecycle.DocumentTextExtractor;
import com.lrj.platform.knowledge.lifecycle.MultimodalIngestionText;
import com.lrj.platform.security.TenantContext;
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

@RestController
@RequestMapping("/rag/documents")
public class DocumentController {

    private final DocumentService documents;
    private final DocumentTextExtractor extractor;

    public DocumentController(DocumentService documents, DocumentTextExtractor extractor) {
        this.documents = documents;
        this.extractor = extractor;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentInfo> uploadFile(@RequestPart("file") MultipartFile file,
                                                   @RequestParam(required = false) String category,
                                                   @RequestParam(required = false) String caption,
                                                   @RequestParam(required = false) String ocrText) throws IOException {
        requireIngest();
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().header("X-Error", "empty file").build();
        }
        String displayName = file.getOriginalFilename();
        String contentType = file.getContentType();
        String text;
        try {
            if (MultimodalIngestionText.isImageContentType(contentType)) {
                text = MultimodalIngestionText.build(null, caption, ocrText);
            } else {
                text = extractor.extract(file.getInputStream(), displayName);
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().header("X-Error", e.getMessage()).build();
        }
        return ResponseEntity.ok(documents.upload(displayName, contentType, text, category, file.getSize()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentInfo> uploadJson(@RequestBody Map<String, String> body) {
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
                String indexText = MultimodalIngestionText.build(text, body.get("caption"), body.get("ocrText"));
                return ResponseEntity.ok(documents.upload(title, contentType, indexText, category, imageBytes.length));
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

    private static void requireIngest() {
        if (!TenantContext.current().hasScope("ingest")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ingest scope required");
        }
    }
}
